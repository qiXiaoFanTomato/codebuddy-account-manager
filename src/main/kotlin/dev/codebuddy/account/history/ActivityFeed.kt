package dev.codebuddy.account.history

import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.credential.CredentialPaths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    data class Item(
        val project: String,
        val title: String,
        val excerpt: String,
        val time: String,
        val workspace: String,
    )

    private val lock = Any()

    @Volatile
    private var cached: List<Item> = emptyList()

    @Volatile
    private var cachedAt = 0L

    fun invalidate() {
        cachedAt = 0L
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
        val root = CredentialPaths.dataRoot() ?: return emptyList()
        val all = HistoryReader.listAllConversations(root).conversations
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
