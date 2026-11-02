package dev.codebuddy.account.history

import com.google.gson.JsonElement
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
 * 消息文件的实际结构（实测）：
 *   { "role": "assistant", "message": "<被序列化成字符串的 JSON>", "extra": "<字符串>", "createdAt": "ISO-8601" }
 * 其中 `message` 才是真正的消息体：
 *   { "role": "assistant", "content": [ { "type": "text" | "reasoning" | "tool-call" | "tool-result", ... } ] }
 * 因此必须先解包再按 content 数组逐块渲染，否则界面里只会显示一大串转义 JSON。
 *
 * 性能说明：单个账号可达 34K+ 消息文件，扫描阶段不再逐个会话统计消息条数
 * （messageCount = -1 表示未统计），选中会话时按需统计，避免 UI 长时间「加载中」。
 *
 * 安全约束（防止 OOM / UI 卡死）：
 *  - 扫描结果按 index.json 的 mtime 缓存
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
    private const val MAX_TOOL_ARGS_CHARS = 160

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

    /** index.json 路径 -> (mtime, 会话列表)。按文件粒度缓存，新增会话也能及时刷新 */
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
        val out = mutableListOf<Conversation>()
        try {
            val wsDirs = Files.list(historyRoot).use { s ->
                s.filter { Files.isDirectory(it) }.limit(MAX_WORKSPACES.toLong()).toList()
            }
            for (wsDir in wsDirs) {
                val ws = wsDir.fileName.toString()
                val idx = wsDir.resolve("index.json")
                if (!Files.isRegularFile(idx)) continue

                val mtime = try { Files.getLastModifiedTime(idx).toMillis() } catch (_: Exception) { 0L }
                val key = idx.toString()
                val cached = cache[key]
                if (cached != null && cached.first == mtime) {
                    out.addAll(cached.second)
                    continue
                }

                val parsed = parseIndex(idx, wsDir, ws, uid) ?: continue
                cache[key] = mtime to parsed
                out.addAll(parsed)
            }
        } catch (e: Exception) {
            log.warn("扫描历史失败: $historyRoot", e)
        }
        return out
    }

    /** 解析单个工作区的 index.json；损坏时返回 null（保留旧缓存） */
    private fun parseIndex(idx: Path, wsDir: Path, ws: String, uid: String): List<Conversation>? {
        val root = try {
            JsonParser.parseString(Files.readString(idx, StandardCharsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            log.warn("解析 index.json 失败: $idx", e)
            return null
        }
        val arr = root.get("conversations") ?: return emptyList()
        if (!arr.isJsonArray) return emptyList()

        val out = mutableListOf<Conversation>()
        for (el in arr.asJsonArray) {
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

    /** 读取一个会话的所有消息（按 createdAt 排序，有上限保护） */
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

        // 文件名是随机 id，顺序与时间无关：先解包，再按 createdAt 排序
        val collected = mutableListOf<Pair<String, Message>>()
        for (f in files) {
            try {
                if (Files.size(f) > MAX_MESSAGE_BYTES) continue
                val root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                val fileTime = if (root.isJsonObject) root.asJsonObject.str("createdAt") ?: "" else ""
                for (m in parseFile(root)) {
                    collected.add((fileTime.ifBlank { m.timestamp }) to m)
                    if (collected.size >= MAX_MESSAGES_PER_CONV) break
                }
            } catch (_: Exception) {
                // 单条消息损坏不影响其余内容
            }
            if (collected.size >= MAX_MESSAGES_PER_CONV) break
        }
        return collected.sortedBy { it.first }.map { it.second }
    }

    // ---------------------------------------------------------------
    // 消息解析
    // ---------------------------------------------------------------

    private fun parseFile(root: JsonElement): List<Message> = when {
        root.isJsonArray -> root.asJsonArray.toList()
            .mapNotNull { el -> (el as? JsonObject)?.let { parseRecord(it) } }
            .flatten()

        root.isJsonObject -> parseRecord(root.asJsonObject)
        else -> emptyList()
    }

    /** 一个消息文件 -> 若干条 Message（兼容 {"messages":[...]} 旧格式） */
    private fun parseRecord(file: JsonObject): List<Message> {
        val batch = file.get("messages")
        if (batch != null && batch.isJsonArray) {
            return batch.asJsonArray.toList()
                .mapNotNull { el -> (el as? JsonObject)?.let { toMessage(it, file) } }
        }
        return listOfNotNull(toMessage(file, file))
    }

    /**
     * 单条记录转 Message。
     *
     * @param o     记录本身（可能还包着字符串化的 message）
     * @param file  外层文件对象，用于读取 createdAt / extra
     */
    private fun toMessage(o: JsonObject, file: JsonObject): Message? {
        if (isHelperMessage(o) || isHelperMessage(file)) return null

        val inner = asObject(o.str("message")) ?: o
        val role = (inner.str("role") ?: file.str("role") ?: return null).trim()
        if (role.isBlank()) return null

        val text = renderContent(inner).ifBlank { renderContent(file) }
        if (text.isBlank()) return null

        val ts = file.str("createdAt")
            ?: inner.str("createdAt")
            ?: inner.str("timestamp")
            ?: file.str("timestamp")
            ?: ""
        val body = if (text.length > MAX_CONTENT_CHARS) {
            text.take(MAX_CONTENT_CHARS) + "\n…（内容过长已截断）"
        } else {
            text
        }
        return Message(role.lowercase(), body, ts)
    }

    /** 把字符串化的 JSON 解包成对象；非 JSON 返回 null */
    private fun asObject(text: String?): JsonObject? {
        if (text.isNullOrBlank() || !text.trimStart().startsWith("{")) return null
        return try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** 是否为内部辅助消息（extra.isHelperMessage = true），这类消息不进会话正文 */
    private fun isHelperMessage(o: JsonObject): Boolean {
        val extra = asObject(o.str("extra")) ?: return false
        val el = extra.get("isHelperMessage") ?: return false
        return try {
            el.isJsonPrimitive && el.asBoolean
        } catch (_: Exception) {
            false
        }
    }

    /** 渲染消息内容：字符串直接返回，content 数组按块渲染 */
    private fun renderContent(o: JsonObject): String {
        val c = o.get("content") ?: o.get("text") ?: o.get("message") ?: return ""
        return try {
            when {
                c.isJsonPrimitive -> c.asString
                c.isJsonArray -> c.asJsonArray.toList()
                    .mapNotNull { el ->
                        when {
                            el.isJsonPrimitive -> el.asString
                            el.isJsonObject -> renderBlock(el.asJsonObject)
                            else -> null
                        }
                    }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")

                c.isJsonObject -> renderBlock(c.asJsonObject) ?: ""
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 单个内容块（text / reasoning / tool-call / tool-result / image …） */
    private fun renderBlock(el: JsonObject): String? = when (val type = (el.str("type") ?: "").lowercase()) {
        "text", "input_text", "output_text", "markdown" -> el.str("text") ?: el.str("content")

        // 思考过程噪音大，默认不展示
        "reasoning" -> null

        "tool-call" -> {
            val name = el.str("toolName") ?: "tool"
            val args = el.get("args")?.let { oneLine(it.toString(), MAX_TOOL_ARGS_CHARS) } ?: ""
            if (args.isBlank()) "▶ 调用 $name" else "▶ 调用 $name $args"
        }

        "tool-result" -> {
            val name = el.str("toolName")
            if (name.isNullOrBlank()) "◀ 工具返回结果" else "◀ $name 返回结果"
        }

        "image", "image_url", "input_image" -> "[图片]"

        "" -> el.str("text") ?: el.str("content")
        else -> el.str("text") ?: el.str("content") ?: "[$type]"
    }

    /** 压缩成单行并截断，用于工具调用参数 */
    private fun oneLine(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }

    private fun JsonObject.str(key: String): String? = try {
        val el = get(key) ?: return null
        if (el.isJsonNull) null
        else if (el.isJsonPrimitive) el.asString
        else null
    } catch (_: Exception) { null }
}
