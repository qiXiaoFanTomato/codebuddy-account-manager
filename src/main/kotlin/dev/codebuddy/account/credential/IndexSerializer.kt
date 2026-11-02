package dev.codebuddy.account.credential

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.codebuddy.account.model.CredentialSnapshot

/**
 * 账号库索引的序列化。
 *
 * index.json 只存元数据 + 快照文件路径，不存 rawContent（内容在 snapshots/<uid>.info 里）。
 */
object IndexSerializer {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun serialize(items: List<CredentialSnapshot>): String {
        val arr = JsonArray()
        for (s in items) {
            val o = JsonObject()
            o.addProperty("uid", s.uid)
            o.addProperty("nickname", s.nickname)
            o.addProperty("uin", s.uin)
            o.addProperty("type", s.type)
            o.addProperty("phoneNumber", s.phoneNumber)
            o.addProperty("domain", s.domain)
            o.addProperty("expiresAt", s.expiresAt)
            o.addProperty("refreshExpiresAt", s.refreshExpiresAt)
            o.addProperty("sourcePath", s.sourcePath)
            o.addProperty("importedAt", s.importedAt)
            o.addProperty("alias", s.alias)
            arr.add(o)
        }
        return gson.toJson(arr)
    }

    /**
     * 反序列化索引。
     *
     * rawContent 在索引里没有，因此读取后由调用方按 sourcePath 回填。
     * 这里返回 rawContent 为空的快照，调用方负责 loadContent。
     */
    fun deserialize(json: String): List<CredentialSnapshot> {
        val arr = try {
            JsonParser.parseString(json).asJsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<CredentialSnapshot>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val uid = o.str("uid")
            if (uid.isEmpty()) continue
            out.add(
                CredentialSnapshot(
                    uid = uid,
                    nickname = o.str("nickname"),
                    uin = o.str("uin"),
                    type = o.str("type"),
                    phoneNumber = o.str("phoneNumber"),
                    domain = o.str("domain"),
                    expiresAt = o.long("expiresAt"),
                    refreshExpiresAt = o.long("refreshExpiresAt"),
                    rawContent = "",  // 由 AccountLibrary 按需回填
                    sourcePath = o.str("sourcePath"),
                    importedAt = o.long("importedAt"),
                    alias = o.str("alias"),
                )
            )
        }
        return out
    }

    private fun JsonObject.str(key: String): String = try {
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""
    } catch (_: Exception) { "" }

    private fun JsonObject.long(key: String): Long = try {
        if (has(key) && !get(key).isJsonNull) get(key).asLong else 0L
    } catch (_: Exception) { 0L }
}
