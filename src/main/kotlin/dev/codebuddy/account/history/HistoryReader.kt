package dev.codebuddy.account.history

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.scan.DirectoryScanner
import dev.codebuddy.account.scan.ScanEntry
import dev.codebuddy.account.scan.ScanError
import dev.codebuddy.account.scan.ScanListener
import dev.codebuddy.account.scan.ScanOptions
import dev.codebuddy.account.scan.ScanProgress
import dev.codebuddy.account.scan.ScanSummary
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
 * 目录遍历统一交给 [DirectoryScanner]：扫描所选目录树中的 index.json，
 * 以及按需扫描会话的 messages 目录，共用取消、跳过明细与扫描汇总能力。
 *
 * 安全约束（防止 OOM / UI 卡死）：
 *  - 扫描结果按 index.json 的 mtime 缓存
 *  - 扫描阶段剪掉 messages 子树，不触碰数万个消息文件
 *  - 单条消息文件超过 MAX_MESSAGE_BYTES 跳过
 *  - 单会话最多读取 MAX_MESSAGES_PER_CONV 条
 *  - 单条消息内容最多 MAX_CONTENT_CHARS 字符
 *  - 扫描的 index.json 数量上限 MAX_INDEX_FILES
 */
object HistoryReader {

    private val log = logger<HistoryReader>()

    private const val MAX_MESSAGE_BYTES = 2L * 1024 * 1024   // 2MB
    private const val MAX_MESSAGES_PER_CONV = 500
    private const val MAX_CONTENT_CHARS = 200_000
    /** 全局 index.json 数量上限（防御性保护，覆盖多账号 × 多工作区场景） */
    private const val MAX_INDEX_FILES = 10_000
    private const val MAX_TOOL_ARGS_CHARS = 160

    /** 一次历史对话扫描的结果：会话列表 + 目录扫描汇总 */
    data class HistoryScanResult(
        val conversations: List<Conversation>,
        val summary: ScanSummary,
    )

    /**
     * 历史对话扫描选项：只收 index.json，并剪掉 messages 子树。
     * 目录扫描功能通过同一套 [ScanOptions] 参与历史对话扫描。
     */
    fun scanOptions(recursive: Boolean = true): ScanOptions = ScanOptions(
        recursive = recursive,
        nameFilters = setOf("index.json"),
        skipDirectoryNames = setOf("messages"),
        maxFiles = MAX_INDEX_FILES,
    )

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
        /** 会话类型（index.json 的 type 字段，如 craft），可能为空 */
        val type: String = "",
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
     * 调用目录扫描功能扫描 [root] 下的历史会话（去重，按最后消息时间倒序）。
     * 该方法可能耗时，调用方应在后台线程执行。
     *
     * @param root     扫描根目录（默认 Data 目录，也可指定任意目录）
     * @param options  目录扫描选项，见 [scanOptions]
     * @param progress 目录扫描进度（后台线程回调）
     * @param cancel   取消标志，置为 true 后扫描尽快中止
     */
    fun listAllConversations(
        root: Path,
        options: ScanOptions = scanOptions(),
        progress: ((ScanProgress) -> Unit)? = null,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): HistoryScanResult {
        val started = System.nanoTime()
        val indexes = mutableListOf<ScanEntry>()
        var scanned: ScanSummary? = null
        val notifyProgress = progress

        DirectoryScanner.scan(root, options, object : ScanListener {
            override fun onEntry(entry: ScanEntry) {
                if (!entry.isDirectory && entry.name.equals("index.json", ignoreCase = true)) {
                    indexes.add(entry)
                }
            }

            override fun onProgress(progress: ScanProgress) {
                notifyProgress?.invoke(progress)
            }

            override fun onFinished(summary: ScanSummary) {
                scanned = summary
            }
        }, cancel)

        val result = mutableListOf<Conversation>()
        for (entry in indexes) {
            if (cancel.get()) break
            val idx = entry.path
            val wsDir = idx.parent ?: continue
            val ws = wsDir.fileName?.toString() ?: continue
            val uid = uidOf(root, idx)

            val mtime = try { Files.getLastModifiedTime(idx).toMillis() } catch (_: Exception) { 0L }
            val key = idx.toString()
            val cached = cache[key]
            if (cached != null && cached.first == mtime) {
                result.addAll(cached.second)
                continue
            }

            val parsed = parseIndex(idx, wsDir, ws, uid) ?: continue
            cache[key] = mtime to parsed
            result.addAll(parsed)
        }

        val conversations = result
            .groupBy { it.id }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastMessageAt } }
            .sortedByDescending { it.lastMessageAt }

        val summary = scanned ?: ScanSummary(
            root = root,
            files = indexes.size,
            directories = 0,
            totalBytes = 0L,
            skipped = 1,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            cancelled = cancel.get(),
            truncated = false,
            errors = listOf(ScanError(root.toString(), "目录扫描未完成")),
        )
        return HistoryScanResult(conversations, summary)
    }

    /**
     * 从 index.json 的路径推断来源账号：Data 根目录下取第一段 uid；
     * 若扫描根本身落在某个账号目录内，则取 history 上一段 uid。
     */
    private fun uidOf(root: Path, idx: Path): String {
        val rel = try {
            root.relativize(idx)
        } catch (_: IllegalArgumentException) {
            return root.fileName?.toString() ?: "unknown"
        }
        if (rel.nameCount < 2) return root.fileName?.toString() ?: "unknown"
        val first = rel.getName(0).toString()
        val isLayoutDir = first.equals("JetBrains", ignoreCase = true) ||
            first.equals("CodeBuddyIDE", ignoreCase = true) ||
            first.equals("history", ignoreCase = true)
        if (!isLayoutDir) return first
        for (i in 1 until rel.nameCount - 1) {
            if (rel.getName(i).toString().equals("history", ignoreCase = true)) {
                return rel.getName(i - 1).toString()
            }
        }
        return first
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
                    type = o.str("type") ?: "",
                )
            )
        }
        return out
    }

    /** 会话消息目录的扫描选项：只扫当前目录的 .json 文件 */
    private fun messageScanOptions(): ScanOptions = ScanOptions(
        recursive = false,
        nameFilters = setOf(".json"),
        maxFiles = MAX_MESSAGES_PER_CONV,
    )

    /** 按需统计消息条数（列表展示用，选中时调用） */
    fun countMessages(conv: Conversation): Int {
        val dir = conv.messageDir ?: return 0
        return try {
            DirectoryScanner.scan(dir, messageScanOptions()).files
        } catch (_: Exception) {
            0
        }
    }

    /** 读取一个会话的所有消息（按 createdAt 排序，有上限保护） */
    fun readMessages(conv: Conversation): List<Message> {
        val dir = conv.messageDir ?: return emptyList()
        val entries = mutableListOf<ScanEntry>()
        try {
            DirectoryScanner.scan(dir, messageScanOptions(), object : ScanListener {
                override fun onEntry(entry: ScanEntry) {
                    entries.add(entry)
                }
            })
        } catch (_: Exception) { return emptyList() }
        val files = entries.sortedBy { it.path.toString() }

        // 文件名是随机 id，顺序与时间无关：先解包，再按 createdAt 排序
        val collected = mutableListOf<Pair<String, Message>>()
        for (entry in files) {
            val f = entry.path
            try {
                if (entry.size > MAX_MESSAGE_BYTES) continue
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
