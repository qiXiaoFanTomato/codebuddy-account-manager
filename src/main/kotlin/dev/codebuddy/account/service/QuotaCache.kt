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

    /** 文件指纹（mtime + size）：未变化时直接复用解析结果 */
    private var cacheStamp: String? = null

    /** 与 [cacheStamp] 对应的解析结果，避免 UI 线程每次刷新都读盘 + 全量解析 */
    private var cacheSnapshot: Map<String, QuotaSummary> = emptyMap()

    /** 读取全部缓存：uid -> QuotaSummary */
    fun loadAll(): Map<String, QuotaSummary> {
        synchronized(lock) {
            if (!Files.isRegularFile(cacheFile)) {
                cacheStamp = null
                cacheSnapshot = emptyMap()
                return emptyMap()
            }
            val stamp = stampOf(cacheFile)
            if (stamp != null && stamp == cacheStamp) return cacheSnapshot
            return readAllLocked(stamp)
        }
    }

    /** 文件指纹：mtime + 大小，任一变化即视为内容已变 */
    private fun stampOf(file: Path): String? = try {
        val attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
        "${attrs.lastModifiedTime().toMillis()}:${attrs.size()}"
    } catch (_: Exception) {
        null
    }

    /** 真正读盘解析；解析失败不污染缓存，下次仍会重试 */
    private fun readAllLocked(stamp: String?): Map<String, QuotaSummary> {
        return try {
            val root = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).asJsonObject
            val out = mutableMapOf<String, QuotaSummary>()
            for ((uid, el) in root.entrySet()) {
                if (!el.isJsonObject) continue
                out[uid] = toSummary(el.asJsonObject)
            }
            cacheStamp = stamp
            cacheSnapshot = out
            out
        } catch (e: Exception) {
            log.warn("读取配额缓存失败", e)
            emptyMap()
        }
    }

    /**
     * 更新单个账号的配额缓存。
     * 「读-改-写」在跨进程锁内完成，避免多开时两个实例互相覆盖其它账号刚刷新的结果。
     *
     * 失败结果不覆盖已有数据：本次查询失败时保留上一次成功的 packages，
     * 只把失败原因写进 lastError（界面可继续显示旧读数并标过期）。
     */
    fun put(uid: String, summary: QuotaSummary) {
        synchronized(lock) {
            val locked = MultiInstanceCoordinator.withLockFor(cacheFile) {
                putLocked(uid, mergeForStore(uid, summary))
                true
            }
            // 锁不可用时降级为仅 JVM 锁，至少保证本进程缓存可更新
            if (locked == null) putLocked(uid, mergeForStore(uid, summary))
        }
    }

    /** 失败时保留旧数据，仅记录 lastError */
    private fun mergeForStore(uid: String, fresh: QuotaSummary): QuotaSummary {
        if (fresh.error == null) return fresh.copy(lastError = null)
        val old = loadAll()[uid] ?: return fresh
        if (old.packages.isEmpty() || old.error != null) return fresh
        return old.copy(error = null, lastError = fresh.error)
    }

    private fun putLocked(uid: String, summary: QuotaSummary) {
        try {
            val all = loadAll().toMutableMap()
            all[uid] = summary
            writeAll(all)
        } catch (e: Exception) {
            log.warn("写入配额缓存失败: $uid", e)
        }
    }

    fun remove(uid: String) {
        synchronized(lock) {
            val locked = MultiInstanceCoordinator.withLockFor(cacheFile) {
                removeLocked(uid)
                true
            }
            if (locked == null) removeLocked(uid)
        }
    }

    private fun removeLocked(uid: String) {
        try {
            val all = loadAll().toMutableMap()
            if (all.remove(uid) != null) writeAll(all)
        } catch (_: Exception) { }
    }

    private fun writeAll(all: Map<String, QuotaSummary>) {
        val root = JsonObject()
        for ((uid, s) in all) {
            val o = JsonObject()
            o.addProperty("fetchedAt", s.fetchedAt)
            if (s.error != null) o.addProperty("error", s.error)
            if (s.lastError != null) o.addProperty("lastError", s.lastError)
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
        // 写盘后直接更新内存快照：省掉紧随其后的重新读盘解析
        cacheSnapshot = HashMap(all)
        cacheStamp = stampOf(cacheFile)
    }

    private fun toSummary(o: JsonObject): QuotaSummary {
        val error = if (o.has("error") && !o.get("error").isJsonNull) o.get("error").asString else null
        val lastError = if (o.has("lastError") && !o.get("lastError").isJsonNull) o.get("lastError").asString else null
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
        return QuotaSummary(packages, fetchedAt, error, lastError)
    }

    private fun JsonObject.str(k: String): String =
        try { if (has(k) && !get(k).isJsonNull) get(k).asString else "" } catch (_: Exception) { "" }

    private fun JsonObject.dbl(k: String): Double =
        try { if (has(k) && !get(k).isJsonNull) get(k).asDouble else 0.0 } catch (_: Exception) { 0.0 }
}
