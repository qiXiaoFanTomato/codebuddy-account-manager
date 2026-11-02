package dev.codebuddy.account.history

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.credential.CredentialPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 读取 CodeBuddy 本地历史对话。
 *
 * 目录结构（实测，两种布局并存）：
 *   Data/<uid>/JetBrains/<uid>/history/<workspaceHash>/        ← 新版
 *   Data/<uid>/CodeBuddyIDE/<uid>/history/<workspaceHash>/     ← 旧版
 *     index.json                        会话列表（conversations / current）
 *     <conversationId>/messages/         每条消息一个 json 文件
 *
 * 性能说明：单个账号可达 34K+ 消息文件，扫描阶段不再逐个会话统计消息条数
 * （messageCount = -1 表示未统计），选中会话时按需统计，避免 UI 长时间「加载中」。
 *
 * 安全约束（防止 OOM / UI 卡死）：
 *  - 扫描结果按目录 mtime 缓存
 *  - 单条消息文件超过 MAX_MESSAGE_BYTES 跳过
 *  - 单会话最多读取 MAX_MESSAGES_PER_CONV 条
 *  - 单条消息内容最多 MAX_CONTENT_CHARS 字符
 *  - 工作区目录扫描上限 MAX_WORKSPACES
 */
object HistoryReader {

    private val log = logger<HistoryReader>()

    private const val MAX_MESSAGE_BYTES = 2L * 1024 * 1024   // 2MB
    private const val MAX_MESSAGES_PER_CONV = 500
    private const val MAX_CONTENT_CHARS = 200_000
    private const val MAX_WORKSPACES = 200

    data class Conversation(
        val id: String,
        val name: String,
        val chatMode: String,
        val selectedModelId: String,
        val createdAt: String,
        val lastMessageAt: String,
        val workspace: String,
        val sourceUid: String,
        val messageDir: Path?,
        /** -1 表示尚未统计（扫描阶段不遍历消息目录） */
        val messageCount: Int,
    ) {
        /** 列表渲染用的单行摘要（避免在渲染器里做类型推断） */
        fun summaryLine(accountLabel: String): String {
            val t = try {
                if (lastMessageAt.isBlank()) "" else
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.parse(lastMessageAt))
            } catch (_: Exception) { "" }
            val n = name.take(40)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val a = accountLabel.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val countText = if (messageCount >= 0) "$messageCount 条 · " else ""
            return "<html><div style='width:360px'><b>$n</b><br/>" +
                "<span style='color:#888'>$t · $countText$a</span></div></html>"
        }
    }

    data class Message(val role: String, val content: String, val timestamp: String)

    /** 目录 -> (mtime, 会话列表) 缓存 */
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Conversation>>>()

    fun clearCache() = cache.clear()

    /**
     * 扫描所有账号的历史会话（去重，按最后消息时间倒序）。
     * 该方法可能耗时，调用方应在后台线程执行。
     */
    fun listAllConversations(
        dataRoot: Path,
        /** 进度回调：已扫描账号数 / 总账号数 / 当前账号标签（在后台线程调用） */
        progress: ((scanned: Int, total: Int, label: String) -> Unit)? = null,
    ): List<Conversation> {
        val result = mutableListOf<Conversation>()

        val uids = try {
            Files.list(dataRoot).use { s ->
                s.filter { Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        } catch (e: Exception) {
            log.warn("列出 Data 目录失败", e)
            return emptyList()
        }

        var scannedAccounts = 0
        for (uid in uids) {
            scannedAccounts++
            progress?.invoke(scannedAccounts, uids.size, uid.take(8))
            for (historyRoot in CredentialPaths.historyRoots(dataRoot, uid)) {
                result.addAll(scanWorkspaceRoot(historyRoot, uid))
            }
        }

        return result
            .groupBy { it.id }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastMessageAt } }
            .sortedByDescending { it.lastMessageAt }
    }

    /** 扫描一个账号的 history 根目录 */
    private fun scanWorkspaceRoot(historyRoot: Path, uid: String): List<Conversation> {
        val key = historyRoot.toString()
        val mtime = try { Files.getLastModifiedTime(historyRoot).toMillis() } catch (_: Exception) { 0L }
        cache[key]?.let { (cachedMtime, cached) -> if (cachedMtime == mtime) return cached }

        val out = mutableListOf<Conversation>()
        try {
            val wsDirs = Files.list(historyRoot).use { s ->
                s.filter { Files.isDirectory(it) }.limit(MAX_WORKSPACES.toLong()).toList()
            }
            for (wsDir in wsDirs) {
                val ws = wsDir.fileName.toString()
                val idx = wsDir.resolve("index.json")
                if (!Files.isRegularFile(idx)) continue
                val root = try {
                    JsonParser.parseString(Files.readString(idx, StandardCharsets.UTF_8)).asJsonObject
                } catch (_: Exception) { continue }
                val arr = root.getAsJsonArray("conversations") ?: continue
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val id = o.str("id") ?: continue
                    if (id.isBlank()) continue
                    val msgDir = wsDir.resolve(id).resolve("messages")
                    out.add(
                        Conversation(
                            id = id,
                            name = o.str("name") ?: "(未命名)",
                            chatMode = o.str("chatMode") ?: "",
                            selectedModelId = o.str("selectedModelId") ?: "",
                            createdAt = o.str("createdAt") ?: "",
                            lastMessageAt = o.str("lastMessageAt") ?: "",
                            workspace = ws,
                            sourceUid = uid,
                            messageDir = if (Files.isDirectory(msgDir)) msgDir else null,
                            messageCount = -1,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("扫描历史失败: $historyRoot", e)
        }

        cache[key] = mtime to out
        return out
    }

    /** 按需统计消息条数（列表展示用，选中时调用） */
    fun countMessages(conv: Conversation): Int {
        val dir = conv.messageDir ?: return 0
        return try {
            Files.list(dir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .limit(MAX_MESSAGES_PER_CONV.toLong())
                    .count()
                    .toInt()
            }
        } catch (_: Exception) {
            0
        }
    }

    /** 读取一个会话的所有消息（有上限保护） */
    fun readMessages(conv: Conversation): List<Message> {
        val dir = conv.messageDir ?: return emptyList()
        val files = try {
            Files.list(dir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .sorted()
                    .limit(MAX_MESSAGES_PER_CONV.toLong())
                    .toList()
            }
        } catch (_: Exception) { return emptyList() }

        val out = mutableListOf<Message>()
        for (f in files) {
            try {
                if (Files.size(f) > MAX_MESSAGE_BYTES) continue
                val text = Files.readString(f, StandardCharsets.UTF_8)
                val root = JsonParser.parseString(text)
                when {
                    root.isJsonArray -> root.asJsonArray.forEach { el ->
                        if (el.isJsonObject) parseMessage(el.asJsonObject)?.let { out.add(it) }
                    }
                    root.isJsonObject -> {
                        val o = root.asJsonObject
                        val arr = o.get("messages")
                        if (arr != null && arr.isJsonArray) {
                            arr.asJsonArray.forEach { el ->
                                if (el.isJsonObject) parseMessage(el.asJsonObject)?.let { out.add(it) }
                            }
                        } else {
                            parseMessage(o)?.let { out.add(it) }
                        }
                    }
                }
                if (out.size >= MAX_MESSAGES_PER_CONV) break
            } catch (_: Exception) { }
        }
        return out
    }

    private fun parseMessage(o: JsonObject): Message? {
        val role = o.str("role") ?: o.str("type") ?: o.str("sender") ?: return null
        val content = extractContent(o) ?: return null
        val truncated = if (content.length > MAX_CONTENT_CHARS) content.take(MAX_CONTENT_CHARS) + "\n…（内容过长已截断）" else content
        val ts = o.str("timestamp") ?: o.str("createdAt") ?: o.str("time") ?: ""
        return Message(role, truncated, ts)
    }

    private fun extractContent(o: JsonObject): String? {
        val c = o.get("content") ?: o.get("text") ?: o.get("message") ?: return null
        return try {
            when {
                c.isJsonPrimitive -> c.asString
                c.isJsonArray -> c.asJsonArray.mapNotNull { el ->
                    when {
                        el.isJsonPrimitive -> el.asString
                        el.isJsonObject -> el.asJsonObject.str("text") ?: el.asJsonObject.str("content")
                        else -> null
                    }
                }.joinToString("\n")
                c.isJsonObject -> c.asJsonObject.str("text") ?: c.asJsonObject.str("content")
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun JsonObject.str(key: String): String? = try {
        val el = get(key) ?: return null
        if (el.isJsonNull) null
        else if (el.isJsonPrimitive) el.asString
        else null
    } catch (_: Exception) { null }
}
