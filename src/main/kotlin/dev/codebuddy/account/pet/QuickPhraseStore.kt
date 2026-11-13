package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger

/**
 * 快捷短语库（仿 WorkDaddy「快捷短语」）。
 *
 * 只存标题 + 正文，顺序即展示顺序；放在 [PropertiesComponent] 里而不是
 * [PetSettings] 的 XML 状态，避免「一组可变列表」混进外观设置、也便于整体导入导出。
 *
 * 空标题按正文首行自动补，正文超长会被截断到 [MAX_TEXT]，避免把整个聊天面板卡住。
 */
object QuickPhraseStore {

    private val log = logger<QuickPhraseStore>()

    private const val STORE_KEY = "codebuddy.pet.quickPhrases"
    private const val MAX_PHRASES = 80
    private const val MAX_TITLE = 40
    private const val MAX_TEXT = 4000

    /** 一条快捷短语 */
    data class Phrase(val title: String, val text: String)

    private val lock = Any()

    /** 当前短语列表（按展示顺序） */
    fun list(): List<Phrase> = synchronized(lock) { load() }

    /** 覆盖保存；空正文条目会被丢弃，条数与长度按上限收敛 */
    fun save(items: List<Phrase>) {
        synchronized(lock) {
            write(items.mapNotNull { normalize(it) })
        }
    }

    /** 标题为空时用正文首行补，正文与标题都做长度收敛 */
    fun normalize(phrase: Phrase): Phrase? {
        val text = phrase.text.trim().take(MAX_TEXT)
        if (text.isEmpty()) return null
        val title = phrase.title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE)
            .ifBlank { text.lineSequence().first().trim().take(MAX_TITLE).ifBlank { "未命名短语" } }
        return Phrase(title, text)
    }

    private fun load(): List<Phrase> {
        val raw = try {
            PropertiesComponent.getInstance().getValue(STORE_KEY)
        } catch (t: Throwable) {
            log.warn("读取快捷短语失败", t)
            null
        } ?: return emptyList()
        return try {
            val array = JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            array.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                normalize(
                    Phrase(
                        title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                        text = o.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    )
                )
            }.take(MAX_PHRASES)
        } catch (t: Throwable) {
            log.warn("解析快捷短语失败", t)
            emptyList()
        }
    }

    private fun write(items: List<Phrase>) {
        try {
            val array = JsonArray()
            items.take(MAX_PHRASES).forEach { phrase ->
                array.add(JsonObject().apply {
                    addProperty("title", phrase.title)
                    addProperty("text", phrase.text)
                })
            }
            PropertiesComponent.getInstance().setValue(STORE_KEY, array.toString())
        } catch (t: Throwable) {
            log.warn("保存快捷短语失败", t)
        }
    }
}
