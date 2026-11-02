package dev.codebuddy.account.quota

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 调用 CodeBuddy 的配额查询接口。
 *
 * 接口（实测有效）：
 *   POST https://<domain>/v2/billing/meter/get-user-resource
 *   Authorization: Bearer <accessToken>
 *   User-Agent: <必须，否则 403 / code 10085>
 *
 * 返回结构（节选）：
 * {
 *   "code": 0,
 *   "data": { "Response": { "Data": { "Accounts": [
 *      { "SubProductName": "...", "CapacitySizePrecise": "100",
 *        "CapacityRemainPrecise": "96.66", "CycleStartTime": "...", ... }
 *   ] } } }
 * }
 */
object QuotaService {

    private val log = logger<QuotaService>()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * 查询指定 accessToken 的配额。
     * 网络/解析失败时返回带 error 的 QuotaSummary，不抛异常。
     */
    fun fetch(domain: String, accessToken: String): QuotaSummary {
        if (accessToken.isBlank()) {
            return QuotaSummary(emptyList(), error = "accessToken 为空")
        }
        val host = if (domain.isBlank()) "www.codebuddy.cn" else domain
        val url = "https://$host/v2/billing/meter/get-user-resource"

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return QuotaSummary(emptyList(), error = "HTTP ${response.statusCode()}")
            }
            parse(response.body())
        } catch (e: Exception) {
            log.warn("配额查询失败: $url", e)
            QuotaSummary(emptyList(), error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parse(body: String): QuotaSummary {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.asString ?: "未知错误"
                return QuotaSummary(emptyList(), error = "接口返回 code=$code: $msg")
            }

            val accounts = nestedArray(root, "data", "Response", "Data", "Accounts")
                ?: return QuotaSummary(emptyList(), error = "返回结构异常")

            val now = System.currentTimeMillis()
            val packages = accounts.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val expiredTime = str(o, "ExpiredTime")
                val cycleEnd = str(o, "CycleEndTime")

                // 过期判定：ExpiredTime 优先，其次 CycleEndTime
                val expireMs = QuotaTime.parse(expiredTime) ?: QuotaTime.parse(cycleEnd)
                val expired = expireMs != null && expireMs < now

                QuotaPackage(
                    name = firstNonBlank(o, "SubProductName", "PackageName", "ProductName"),
                    size = dbl(o, "CapacitySizePrecise", "CapacitySize"),
                    used = dbl(o, "CapacityUsedPrecise", "CapacityUsed"),
                    remain = dbl(o, "CapacityRemainPrecise", "CapacityRemain"),
                    unit = str(o, "CapacityUnit"),
                    cycleStart = str(o, "CycleStartTime"),
                    cycleEnd = cycleEnd,
                    expired = expired,
                    groupName = str(o, "PackageName"),
                    subProductName = str(o, "SubProductName"),
                    expiredTime = expiredTime,
                    grantSource = attrValue(o, "grantSource"),
                )
            }
            QuotaSummary(packages)
        } catch (e: Exception) {
            log.warn("配额解析失败", e)
            QuotaSummary(emptyList(), error = "解析失败: ${e.message}")
        }
    }

    /** 逐层取嵌套对象下的数组，任一层缺失返回 null */
    private fun nestedArray(root: JsonObject, vararg path: String): com.google.gson.JsonArray? {
        var cur: JsonObject = root
        for (i in 0 until path.size - 1) {
            val next = cur.get(path[i])
            if (next == null || !next.isJsonObject) return null
            cur = next.asJsonObject
        }
        val last = cur.get(path.last())
        return if (last != null && last.isJsonArray) last.asJsonArray else null
    }

    private fun str(o: JsonObject, key: String): String =
        try { if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else "" } catch (_: Exception) { "" }

    /** 从 AccountAttributes 数组里取指定 Key 的 Value（如 grantSource） */
    private fun attrValue(o: JsonObject, key: String): String {
        val arr = o.get("AccountAttributes") ?: return ""
        if (!arr.isJsonArray) return ""
        for (el in arr.asJsonArray) {
            if (!el.isJsonObject) continue
            val ao = el.asJsonObject
            if (str(ao, "Key") == key) return str(ao, "Value")
        }
        return ""
    }

    private fun firstNonBlank(o: JsonObject, vararg keys: String): String {
        for (k in keys) { val v = str(o, k); if (v.isNotBlank()) return v }
        return ""
    }

    /** 优先取 precise 字段（字符串数字），退回整数/浮点字段 */
    private fun dbl(o: JsonObject, vararg keys: String): Double {
        for (k in keys) {
            if (!o.has(k) || o.get(k).isJsonNull) continue
            val el = o.get(k)
            try {
                return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.toDoubleOrNull() ?: 0.0
                else el.asDouble
            } catch (_: Exception) { }
        }
        return 0.0
    }
}
