package dev.codebuddy.account.history

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.credential.CredentialPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale



/**
 * 对话活跃记录：把本地历史会话整理成「项目名 + 对话标题 + 内容摘要」。
 *
 * 摘要规则：优先取会话里第一条 user 消息，其次取最后一条 assistant 消息；
 * 正文若以「主人」之类的称呼开头，只显示称呼后面那一段话，避免气泡里全是客套前缀。
 *
 * 读盘较重（扫描 index.json + 解析项目名 + 读消息文件），必须在后台线程调用；
 * 结果缓存 [CACHE_MS]，对话完成时由 [invalidate] 主动失效。
 */
object ActivityFeed {

    private val log = logger<ActivityFeed>()

    private const val CACHE_MS = 30_000L
    private const val MAX_EXCERPT = 120

    /** 单 workspace 标题的有效期：正在跑的对话改名后要尽快反映，不能等全量扫描 */
    private const val TITLE_TTL_MS = 3_000L

    /** workspace -> index.json 定位缓存的失效时间 */
    private const val INDEX_PATH_TTL_MS = 60_000L

    /** 两种历史目录布局（见 [HistoryReader] 的目录说明） */
    private val LAYOUTS = listOf("JetBrains", "CodeBuddyIDE")

    data class Item(
        val project: String,
        val title: String,
        val excerpt: String,
        val time: String,
        val workspace: String,
    )

    /** 新对话还没生成标题时的占位文案（原会话 name 为空） */
    const val NEW_CHAT_TITLE = "新对话"

    private val lock = Any()

    @Volatile
    private var cached: List<Item> = emptyList()

    @Volatile
    private var cachedAt = 0L

    /** 会话索引扫描结果；[recent] 与 [currentTitles] 共用，避免两个入口各扫一遍盘 */
    @Volatile
    private var scanned: List<HistoryReader.Conversation> = emptyList()

    @Volatile
    private var scannedAt = 0L

    @Volatile
    private var titlesCached: Map<String, String> = emptyMap()

    @Volatile
    private var titlesAt = 0L

    /** 单 workspace 标题缓存：workspace -> (取到标题的时刻, 标题) */
    @Volatile
    private var singleTitles: Map<String, Pair<Long, String>> = emptyMap()

    /** workspace -> (定位时刻, index.json 路径) */
    @Volatile
    private var indexPaths: Map<String, Pair<Path, Long>> = emptyMap()

    fun invalidate() {
        cachedAt = 0L
        scannedAt = 0L
        titlesAt = 0L
        singleTitles = emptyMap()
        indexPaths = emptyMap()
    }

    /**
     * 单个 workspace 的当前对话标题（状态栏与代点提示共用）。
     *
     * 与 [currentTitles] 的区别：只读这一个 workspace 的 index.json，缓存 [TITLE_TTL_MS]，
     * 所以正在跑的对话一改名就能反映出来；全量扫描的 30 秒缓存留给列表页，别拿到这里用。
     */
    fun titleOf(workspace: String): String {
        val ws = workspace.trim()
        if (ws.isEmpty()) return ""
        val now = System.currentTimeMillis()
        singleTitles[ws]?.let { (at, title) -> if (now - at < TITLE_TTL_MS) return title }
        val title = try {
            readWorkspaceTitle(ws)
        } catch (t: Throwable) {
            log.warn("读取对话标题失败: $ws", t)
            ""
        }
        singleTitles = singleTitles + (ws to (now to title))
        return title
    }

    /**
     * 直读某个 workspace 的 index.json 取标题。
     *
     * `current` 是 CodeBuddy 面板里「打开的那条」，但它不一定跟着正在跑的新对话走；
     * 所以取两者中**最近有消息**的那条：只有当 current 自己就是最新会话时才用它，
     * 否则用 lastMessageAt 最大的会话——那才是状态栏要显示的「正在运行」的标题。
     */
    private fun readWorkspaceTitle(ws: String): String {
        val index = indexFileFor(ws) ?: return ""
        val root = try {
            JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8)).asJsonObject
        } catch (_: Throwable) {
            return ""
        }
        val arr = root.get("conversations") ?: return ""
        if (!arr.isJsonArray) return ""
        val currentId = try {
            root.get("current")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        var currentName = ""
        var currentAt = ""
        var latestName = ""
        var latestAt = ""
        for (el in arr.asJsonArray) {
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = cleanTitle(o.text("name"))
            if (name.isEmpty()) continue
            val at = o.text("lastMessageAt")
            if (currentId.isNotEmpty() && o.text("id") == currentId) {
                currentName = name
                currentAt = at
            }
            if (at > latestAt) {
                latestAt = at
                latestName = name
            }
        }
        return when {
            currentName.isEmpty() -> latestName
            latestName.isEmpty() -> currentName
            // ISO 时间串可直接字典序比较：current 不是最新会话时以最新那条为准
            currentAt >= latestAt -> currentName
            else -> latestName
        }
    }

    /** index.json 字段读取：缺失或不是字符串时返回空串 */
    private fun JsonObject.text(key: String): String =
        try {
            get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        } catch (_: Throwable) {
            ""
        }

    /** 标题里的换行 / 制表符会把提示框撑成多行，读出来后压成一行 */
    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t\\u2028\\u2029]+"), " ").trim().take(80)

    /** workspace 的 index.json 路径：按需定位一次，[INDEX_PATH_TTL_MS] 内复用 */
    private fun indexFileFor(ws: String): Path? {
        val now = System.currentTimeMillis()
        indexPaths[ws]?.let { (path, at) ->
            if (now - at < INDEX_PATH_TTL_MS && Files.isRegularFile(path)) return path
        }
        val found = findIndexFile(ws) ?: return null
        indexPaths = indexPaths + (ws to (found to now))
        return found
    }

    /** Data/<uid>/<布局>/<uid>/history/<workspace>/index.json：两种布局都试一次 */
    private fun findIndexFile(ws: String): Path? {
        val root = try {
            CredentialPaths.dataRoot()
        } catch (_: Throwable) {
            null
        } ?: return null
        val uids = try {
            Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        } catch (_: Throwable) {
            return null
        }
        for (uid in uids) {
            for (layout in LAYOUTS) {
                val base = uid.resolve(layout)
                if (!Files.isDirectory(base)) continue
                val accounts = try {
                    Files.list(base).use { s -> s.filter { Files.isDirectory(it) }.toList() }
                } catch (_: Throwable) {
                    continue
                }
                for (account in accounts) {
                    // 目录名固定小写 MD5：日志文件名里的 hash 可能带大写，统一按小写找
                    val index = account.resolve("history").resolve(ws.lowercase(Locale.ROOT))
                        .resolve("index.json")
                    if (Files.isRegularFile(index)) return index
                }
            }
        }
        return null
    }

    /**
     * workspace hash -> 该项目「当前打开」的对话标题（全量扫描，30 秒缓存）。
     *
     * 优先取 index.json 的 `current`（面板里当前打开的那条），
     * 没有 current 时退回该项目最近一条；标题为空时统一显示 [NEW_CHAT_TITLE]。
     * 只读会话索引，不读消息正文。
     *
     * 注意：`current` 不一定跟着正在跑的对话走（实测会停在更早的会话），
     * 所以状态栏、代点提示这类要「正在运行」的标题请用 [titleOf]，别用这里。
     */
    fun currentTitles(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - titlesAt < CACHE_MS) return titlesCached
        return synchronized(lock) {
            if (System.currentTimeMillis() - titlesAt < CACHE_MS) return@synchronized titlesCached
            val result = try {
                loadCurrentTitles()
            } catch (t: Throwable) {
                log.warn("读取当前对话标题失败", t)
                emptyMap()
            }
            titlesCached = result
            titlesAt = System.currentTimeMillis()
            result
        }
    }

    private fun loadCurrentTitles(): Map<String, String> {
        val titles = HashMap<String, String>()
        // 索引按最近消息倒序：先 putIfAbsent 得到最近一条，遇到 current 再覆盖成当前打开的
        for (conv in conversations()) {
            val title = conv.name.ifBlank { NEW_CHAT_TITLE }
            if (conv.isCurrent) titles[conv.workspace] = title else titles.putIfAbsent(conv.workspace, title)
        }
        return titles
    }

    /** 会话索引（已按最近消息时间倒序），[CACHE_MS] 内复用；不读消息正文 */
    private fun conversations(): List<HistoryReader.Conversation> {
        val now = System.currentTimeMillis()
        if (scanned.isNotEmpty() && now - scannedAt < CACHE_MS) return scanned
        return synchronized(lock) {
            if (scanned.isNotEmpty() && System.currentTimeMillis() - scannedAt < CACHE_MS) {
                return@synchronized scanned
            }
            val root = CredentialPaths.dataRoot() ?: return@synchronized emptyList()
            val list = try {
                HistoryReader.listAllConversations(root).conversations
            } catch (t: Throwable) {
                log.warn("扫描会话索引失败", t)
                emptyList()
            }
            scanned = list
            scannedAt = System.currentTimeMillis()
            list
        }
    }

    fun recent(limit: Int = 20, maxConversations: Int = 12, force: Boolean = false): List<Item> {
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && now - cachedAt < CACHE_MS) return cached.take(limit)
        return synchronized(lock) {
            if (!force && cached.isNotEmpty() && now - cachedAt < CACHE_MS) {
                return@synchronized cached.take(limit)
            }
            val items = try {
                load(maxConversations)
            } catch (t: Throwable) {
                log.warn("读取对话活跃记录失败", t)
                emptyList()
            }
            cached = items
            cachedAt = System.currentTimeMillis()
            items.take(limit)
        }
    }

    private fun load(maxConversations: Int): List<Item> {
        val all = conversations()
        if (all.isEmpty()) return emptyList()
        val picked = all.take(maxConversations)
        val projects = try {
            ProjectResolver.resolve(picked)
        } catch (_: Throwable) {
            emptyMap()
        }
        return picked.map { conv ->
            val messages = try {
                HistoryReader.readMessages(conv)
            } catch (_: Throwable) {
                emptyList()
            }
            Item(
                project = projects[conv.workspace]?.name ?: conv.workspace.take(8),
                title = conv.name.ifBlank { "（未命名对话）" },
                excerpt = excerptOf(messages),
                time = timeText(conv.lastMessageAt),
                workspace = conv.workspace,
            )
        }
    }

    private fun excerptOf(messages: List<HistoryReader.Message>): String {
        val user = messages.firstOrNull { it.role.equals("user", true) }?.content
        val assistant = messages.lastOrNull { it.role.equals("assistant", true) }?.content
        val raw = listOfNotNull(user, assistant).firstOrNull { it.isNotBlank() } ?: return "（无正文）"
        return excerpt(raw)
    }

    /** 「主人」后面那一段话：去掉称呼与紧随标点，正文过长时截断 */
    fun excerpt(raw: String): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        if (flat.isBlank()) return "（无正文）"
        // 只有开头的称呼才剥离，正文中间出现「主人」时保持原文
        val idx = flat.indexOf("主人")
        val body = if (idx in 0..12) {
            flat.substring(idx + 2).trimStart(
                '，', ',', '：', ':', ' ', '、', '。', '.', '-', '~', '！', '!', '*', '·',
            )
        } else {
            flat
        }
        val text = body.ifBlank { flat }
        return if (text.length <= MAX_EXCERPT) text else text.take(MAX_EXCERPT - 1) + "…"
    }

    private fun timeText(iso: String): String = try {
        DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    } catch (_: Exception) {
        iso.take(16)
    }
}
