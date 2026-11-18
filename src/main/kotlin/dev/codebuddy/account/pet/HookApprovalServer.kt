package dev.codebuddy.account.pet

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService



/** 一次待裁决的工具调用（白名单字段，与 hook 脚本的投影一一对应） */
data class HookApprovalRequest(
    val toolName: String,
    val detail: String,
    val event: String,
    /** 摘要超长被截断：看不全就不允许自动放行 */
    val truncated: Boolean = false,
    /** 宿主的当前工作目录；实测是 IDE 进程目录，只作 sessionId 查不到时的兜底 */
    val cwd: String = "",
    /** CodeBuddy 会话 ID：用它反查项目与对话标题，比 cwd 准 */
    val sessionId: String = "",
    /** 会话转写文件路径：索引还没落盘时，用它兜底认 workspace */
    val transcriptPath: String = "",
)

/** 裁决结果；[PASSTHROUGH] 表示不表态，交回 CodeBuddy 自己的确认框 */
enum class ApprovalDecision {
    ALLOW,
    DENY,
    PASSTHROUGH,
    ;

    /** 回给 hook 脚本的裸文本，脚本只认 allow / deny */
    fun wireValue(): String = when (this) {
        ALLOW -> "allow"
        DENY -> "deny"
        PASSTHROUGH -> "pass"
    }
}

/**
 * 极简回环 HTTP 服务：CodeBuddy 的 `PreToolUse` hook 脚本把待确认的调用送过来，
 * 这里等主人（或免打扰规则）给出裁决，再把 `allow` / `deny` / `pass` 回给脚本。
 *
 * 信任边界：只绑 127.0.0.1，无鉴权。本机任意进程都能冒充调用方，
 * 所以**真正的安全阀在 [decider]**：危险命令只能人工点，免打扰不生效，
 * 超时一律 [ApprovalDecision.PASSTHROUGH] 交回宿主。
 *
 * 不用 `SO_REUSEADDR`：多开 IDE 时第二个实例必须绑定失败，
 * 避免两个进程抢同一端口导致裁决来源不确定。
 */
class HookApprovalServer(
    private val decider: (HookApprovalRequest) -> ApprovalDecision,
) {

    private val log = logger<HookApprovalServer>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "codebuddy-pet-hook").apply { isDaemon = true }
    }

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    /** @return true 表示已在监听 */
    fun start(): Boolean {
        if (isRunning) return true
        val socket = try {
            ServerSocket().apply {
                reuseAddress = false
                bind(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), CodeBuddyHookInstaller.PORT),
                    BACKLOG,
                )
            }
        } catch (e: Exception) {
            log.info("审批端口 ${CodeBuddyHookInstaller.PORT} 不可用: ${e.message}")
            return false
        }
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "codebuddy-pet-hook-accept").apply {
            isDaemon = true
            start()
        }
        log.info("桌宠审批服务已监听 127.0.0.1:${CodeBuddyHookInstaller.PORT}")
        return true
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                return
            }
            try {
                workers.execute { handle(client) }
            } catch (_: Exception) {
                // 线程池已关闭：丢弃本次请求，hook 会 fail-open
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val req = try {
                readRequest(socket.getInputStream())
            } catch (_: Exception) {
                null
            }
            // 结果上报（PostToolUse）：不需要裁决，记一笔就回 ok 结束
            if (req != null && req.path.contains(RESULT_PATH)) {
                onResult(req.body)
                write(socket, "ok")
                return
            }
            val decision = when {
                req?.body == null -> ApprovalDecision.PASSTHROUGH
                else -> decide(req.body)
            }
            write(socket, decision.wireValue())
        }
    }

    /** PostToolUse 回传的执行结果：按项目找到最近一条待回填的代点记录 */
    private fun onResult(body: String?) {
        if (body == null) return
        try {
            val obj = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return
            val cwd = obj.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val sessionId = obj.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val text = obj.get("result")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val okText = obj.get("ok")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val ok = when (okText.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            // 与记账时同一套解析：先 sessionId，再 cwd，保证两边落在同一个项目键上
            PetApprovalHistory.attachResult(SessionContext.projectOf(sessionId, cwd), ok, text)
        } catch (e: Exception) {
            log.info("解析执行结果失败: ${e.message}")
        }
    }

    private fun decide(body: String): ApprovalDecision = try {
        val json = JsonParser.parseString(body)
        if (!json.isJsonObject) {
            ApprovalDecision.PASSTHROUGH
        } else {
            val obj = json.asJsonObject
            decider(
                HookApprovalRequest(
                    toolName = obj.get("tool_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    detail = obj.get("detail")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    event = obj.get("event")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    truncated = obj.get("detail_truncated")?.takeIf { it.isJsonPrimitive }?.asBoolean
                        ?: false,
                    cwd = obj.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    sessionId = obj.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    transcriptPath = obj.get("transcript_path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                ),
            )
        }
    } catch (e: Exception) {
        log.info("解析审批请求失败: ${e.message}")
        ApprovalDecision.PASSTHROUGH
    }

    /** 一次请求：path + body（无 body 时为 null） */
    private class Request(val path: String, val body: String?)

    /** 读 header 至空行，按 Content-Length 读 body；超限或畸形时 body 为 null */
    private fun readRequest(input: InputStream): Request? {
        val header = ByteArrayOutputStream()
        var tail = 0
        while (header.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            header.write(b)
            tail = (tail shl 8) or b
            if (tail == CRLFCRLF) break
        }
        val head = header.toString(StandardCharsets.ISO_8859_1.name())
        // 首行形如 `POST /approve HTTP/1.1`
        val path = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1) ?: "/"
        val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY_BYTES) return Request(path, null)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        if (read <= 0) return Request(path, null)
        return Request(path, String(buffer, 0, read, StandardCharsets.UTF_8))
    }

    private fun write(socket: Socket, text: String) {
        try {
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(StandardCharsets.ISO_8859_1))
            out.write(payload)
            out.flush()
        } catch (_: Exception) {
            // 对端已断开：hook 侧按 fail-open 处理
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        const val CRLFCRLF = 0x0D0A0D0A

        /** PostToolUse 结果上报路径 */
        const val RESULT_PATH = "/result"

        val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")
    }
}
