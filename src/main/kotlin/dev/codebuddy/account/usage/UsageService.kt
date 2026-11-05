package dev.codebuddy.account.usage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 拉取「请求消耗明细」（对应网页 https://<domain>/profile/plans-usage 的「消耗明细」页）。
 *
 * 接口（实测有效）：
 *   POST https://<domain>/billing/meter/get-user-request-usage
 *   Authorization: Bearer <accessToken>
 *   User-Agent: <必须，否则可能被网关拦截>
 *   Body: {"startTime":"yyyy-MM-dd 00:00:00","endTime":"yyyy-MM-dd 23:59:59","pageNum":1,"pageSize":1000}
 *
 * 返回结构（节选）：
 * {
 *   "code": 0,
 *   "data": {
 *     "total": 947,
 *     "data": [ { "requestId":"...", "credit":6.32, "model":"deepseek-v4.1-flash",
 *                 "client":"JetBrainsIntelliJIdea", "requestTime":"2026-11-02 14:40:00",
 *                 "input":"...", "inputTrunc":"...", "appType":"codebuddy",
 *                 "subscribeType":"free", "agentPurpose":"" } ]
 *   }
 * }
 *
 * 注意：该路径不带 /v2 前缀（与额度接口 /v2/billing/meter/get-user-resource 不同）。
 */
object UsageService {

    private val log = logger<UsageService>()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 单次拉取上限（接口实测一次返回上千条正常） */
    const val MAX_PAGE_SIZE = 1000

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * 查询指定 accessToken 在 [startTime, endTime] 内的请求消耗。
     * 网络/解析失败时返回带 error 的 UsagePage，不抛异常。
     *
     * @param pageNum 页码，从 1 开始
     */
    fun fetch(
        domain: String,
        accessToken: String,
        startTime: String,
        endTime: String,
        pageSize: Int = MAX_PAGE_SIZE,
        pageNum: Int = 1,
    ): UsagePage {
        if (accessToken.isBlank()) return UsagePage(error = "accessToken 为空")

        val host = if (domain.isBlank()) "www.codebuddy.cn" else domain
        val url = "https://$host/billing/meter/get-user-request-usage"
        val body = JsonObject().apply {
            addProperty("startTime", startTime)
            addProperty("endTime", endTime)
            addProperty("pageNum", pageNum.coerceAtLeast(1))
            addProperty("pageSize", pageSize.coerceIn(1, MAX_PAGE_SIZE))
        }.toString()

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> parse(response.body())
                401 -> UsagePage(error = "HTTP 401：凭据已过期或不被接受，请重新登录该账号后再试")
                else -> UsagePage(error = "HTTP ${response.statusCode()}")
            }
        } catch (t: Throwable) {
            // 连 Exception 之外的 Error 也接住：调用方要保证「加载中」一定有出口
            log.warn("请求消耗查询失败: $url", t)
            UsagePage(error = t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 翻页拉取区间内的全部消耗记录（用于周期内按模型聚合统计）。
     *
     * 最多拉 [maxPages] 页，避免超大区间把接口打爆；
     * 首页失败直接返回错误，中途失败则保留已取到的数据并带上 error。
     */
    fun fetchAll(
        domain: String,
        accessToken: String,
        startTime: String,
        endTime: String,
        pageSize: Int = MAX_PAGE_SIZE,
        maxPages: Int = 20,
    ): UsagePage {
        val records = ArrayList<UsageRecord>()
        var total = 0
        var firstError: String? = null

        for (page in 1..maxPages.coerceAtLeast(1)) {
            val result = fetch(domain, accessToken, startTime, endTime, pageSize, page)
            if (result.error != null) {
                if (records.isEmpty()) return result
                firstError = result.error
                break
            }
            total = maxOf(total, result.total)
            records.addAll(result.records)
            if (result.records.isEmpty()) break
            if (records.size >= result.total) break
        }

        return UsagePage(records, total, firstError)
    }

    private fun parse(body: String): UsagePage {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                val msg = str(root, "msg").ifBlank { "未知错误" }
                return UsagePage(error = "接口返回 code=$code: $msg")
            }
            val data = root.get("data")
            if (data == null || !data.isJsonObject) return UsagePage(error = "返回结构异常")
            val dataObj = data.asJsonObject

            val total = int(dataObj, "total")
            val arr: JsonArray = dataObj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return UsagePage(emptyList(), total)

            val records = arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                UsageRecord(
                    requestId = str(o, "requestId"),
                    credit = dbl(o, "credit"),
                    model = str(o, "model"),
                    client = str(o, "client"),
                    requestTime = str(o, "requestTime"),
                    prompt = str(o, "input"),
                    promptTrunc = str(o, "inputTrunc"),
                    appType = str(o, "appType"),
                    subscribeType = str(o, "subscribeType"),
                    agentPurpose = str(o, "agentPurpose"),
                )
            }
            UsagePage(records, if (total > 0) total else records.size)
        } catch (e: Exception) {
            log.warn("请求消耗解析失败", e)
            UsagePage(error = "解析失败: ${e.message}")
        }
    }

    private fun str(o: JsonObject, key: String): String =
        try {
            val el = o.get(key)
            if (el == null || el.isJsonNull) "" else el.asString
        } catch (_: Exception) {
            ""
        }

    private fun int(o: JsonObject, key: String): Int =
        try {
            val el = o.get(key)
            if (el == null || el.isJsonNull) 0 else el.asInt
        } catch (_: Exception) {
            0
        }

    private fun dbl(o: JsonObject, key: String): Double {
        val el = o.get(key) ?: return 0.0
        return try {
            when {
                el.isJsonNull -> 0.0
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toDoubleOrNull() ?: 0.0
                else -> el.asDouble
            }
        } catch (_: Exception) {
            0.0
        }
    }
}
