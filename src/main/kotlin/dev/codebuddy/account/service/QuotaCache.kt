package dev.codebuddy.account.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 配额结果的本地缓存，避免每次打开面板都打网络请求。
 *
 * 存储：<pluginConfig>/codebuddy-accounts/quota-cache.json
 * 结构：{ "<uid>": { fetchedAt, error, packages: [...] } }
 */
class QuotaCache(private val cacheFile: Path) {

    private val log = logger<QuotaCache>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()

    /** 读取全部缓存：uid -> QuotaSummary */
    fun loadAll(): Map<String, QuotaSummary> {
        synchronized(lock) {
            if (!Files.isRegularFile(cacheFile)) return emptyMap()
            return try {
                val root = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).asJsonObject
                val out = mutableMapOf<String, QuotaSummary>()
                for ((uid, el) in root.entrySet()) {
                    if (!el.isJsonObject) continue
                    out[uid] = toSummary(el.asJsonObject)
                }
                out
            } catch (e: Exception) {
                log.warn("读取配额缓存失败", e)
                emptyMap()
            }
        }
    }

    /** 更新单个账号的配额缓存 */
    fun put(uid: String, summary: QuotaSummary) {
        synchronized(lock) {
            try {
                val all = loadAll().toMutableMap()
                all[uid] = summary
                writeAll(all)
            } catch (e: Exception) {
                log.warn("写入配额缓存失败: $uid", e)
            }
        }
    }

    fun remove(uid: String) {
        synchronized(lock) {
            try {
                val all = loadAll().toMutableMap()
                if (all.remove(uid) != null) writeAll(all)
            } catch (_: Exception) { }
        }
    }

    private fun writeAll(all: Map<String, QuotaSummary>) {
        val root = JsonObject()
        for ((uid, s) in all) {
            val o = JsonObject()
            o.addProperty("fetchedAt", s.fetchedAt)
            if (s.error != null) o.addProperty("error", s.error)
            val arr = com.google.gson.JsonArray()
            for (p in s.packages) {
                val po = JsonObject()
                po.addProperty("name", p.name)
                po.addProperty("size", p.size)
                po.addProperty("used", p.used)
                po.addProperty("remain", p.remain)
                po.addProperty("unit", p.unit)
                po.addProperty("cycleStart", p.cycleStart)
                po.addProperty("cycleEnd", p.cycleEnd)
                po.addProperty("expired", p.expired)
                po.addProperty("groupName", p.groupName)
                po.addProperty("subProductName", p.subProductName)
                po.addProperty("expiredTime", p.expiredTime)
                po.addProperty("grantSource", p.grantSource)
                arr.add(po)
            }
            o.add("packages", arr)
            root.add(uid, o)
        }
        Files.createDirectories(cacheFile.parent)
        val tmp = cacheFile.resolveSibling("quota-cache.json.tmp")
        Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8)
        try {
            Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun toSummary(o: JsonObject): QuotaSummary {
        val error = if (o.has("error") && !o.get("error").isJsonNull) o.get("error").asString else null
        val fetchedAt = if (o.has("fetchedAt")) o.get("fetchedAt").asLong else 0L
        val packages = mutableListOf<QuotaPackage>()
        val arr = o.get("packages")
        if (arr != null && arr.isJsonArray) {
            for (el in arr.asJsonArray) {
                if (!el.isJsonObject) continue
                val po = el.asJsonObject
                val cycleEnd = po.str("cycleEnd")
                val expiredTime = po.str("expiredTime")
                // 过期状态按当前时间重新判定：缓存里的 boolean 会随时间失效
                val expireMs = QuotaTime.parse(expiredTime) ?: QuotaTime.parse(cycleEnd)
                packages.add(
                    QuotaPackage(
                        name = po.str("name"),
                        size = po.dbl("size"),
                        used = po.dbl("used"),
                        remain = po.dbl("remain"),
                        unit = po.str("unit"),
                        cycleStart = po.str("cycleStart"),
                        cycleEnd = cycleEnd,
                        expired = expireMs != null && expireMs < System.currentTimeMillis(),
                        groupName = po.str("groupName"),
                        subProductName = po.str("subProductName"),
                        expiredTime = expiredTime,
                        grantSource = po.str("grantSource"),
                    )
                )
            }
        }
        return QuotaSummary(packages, fetchedAt, error)
    }

    private fun JsonObject.str(k: String): String =
        try { if (has(k) && !get(k).isJsonNull) get(k).asString else "" } catch (_: Exception) { "" }

    private fun JsonObject.dbl(k: String): Double =
        try { if (has(k) && !get(k).isJsonNull) get(k).asDouble else 0.0 } catch (_: Exception) { 0.0 }
}
