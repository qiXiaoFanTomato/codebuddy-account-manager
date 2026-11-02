package dev.codebuddy.account.credential

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.codebuddy.account.model.CredentialSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析 CodeBuddy 的 .info 凭据文件。
 *
 * 文件结构（实测）：
 * {
 *   "account":     { uid, nickname, uin, type, phoneNumber, ... },
 *   "accounts":    [ 仅元数据，无 token ],
 *   "allAccounts": [ 仅元数据，无 token ],
 *   "auth": {
 *      "accessToken": "...", "refreshToken": "...",
 *      "domain": "www.codebuddy.cn",
 *      "expiresAt": 1795858210022,
 *      "refreshExpiresAt": 1798450209022,
 *      ...
 *   }
 * }
 */
object CredentialParser {

    /** 解析文件；失败返回 null（不抛异常，便于批量扫描时跳过坏文件） */
    fun parse(path: Path): CredentialSnapshot? = try {
        val raw = Files.readString(path, StandardCharsets.UTF_8)
        parseContent(raw, path.toString())
    } catch (_: Exception) {
        null
    }

    fun parseContent(raw: String, sourcePath: String = ""): CredentialSnapshot? {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            return null
        }

        val account = root.objOrNull("account") ?: return null
        val auth = root.objOrNull("auth")
        val uid = account.str("uid")
        if (uid.isEmpty()) return null

        return CredentialSnapshot(
            uid = uid,
            nickname = account.str("nickname"),
            uin = account.str("uin"),
            type = account.str("type"),
            phoneNumber = account.str("phoneNumber"),
            domain = auth?.str("domain").orEmpty(),
            expiresAt = auth?.long("expiresAt") ?: 0L,
            refreshExpiresAt = auth?.long("refreshExpiresAt") ?: 0L,
            rawContent = raw,
            sourcePath = sourcePath,
        )
    }

    // ---- 索引序列化（委托给 IndexSerializer）----

    fun parseIndex(json: String): List<CredentialSnapshot> = IndexSerializer.deserialize(json)

    fun serializeIndex(items: List<CredentialSnapshot>): String = IndexSerializer.serialize(items)

    // ---- JsonObject 小工具 ----

    private fun JsonObject.objOrNull(key: String): JsonObject? = try {
        if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String = try {
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""
    } catch (_: Exception) {
        ""
    }

    private fun JsonObject.long(key: String): Long = try {
        if (has(key) && !get(key).isJsonNull) get(key).asLong else 0L
    } catch (_: Exception) {
        0L
    }
}
