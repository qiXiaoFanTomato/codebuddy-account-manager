package dev.codebuddy.account.checkin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 每日签到（领取积分）。
 *
 * ⚠️ 接口来源说明：以下两个路径来自社区逆向资料，不是官方文档接口，
 * CodeBuddy 客户端内也未内置该能力，因此路径与返回字段名都可能随版本变化。
 * 实现上做了两件事来降低风险：
 *  1. 路径可在设置页修改（默认即下面两个常量）；
 *  2. 响应按「宽松解析」处理：只认 code=0 / code=10001，字段取不到就置 null，
 *     绝不因为字段缺失把结果判成失败以外的异常状态。
 *
 * 请求与额度 / 消耗接口一致：POST + Bearer accessToken + User-Agent（否则被网关拦）。
 * 实测环境本机凭据里的 accessToken 已失效（额度接口同样 401），因此**未做端到端验证**。
 */
object CheckinService {

    private val log = logger<CheckinService>()

    /** 查询今日签到状态 */
    const val DEFAULT_STATUS_PATH = "/v2/billing/meter/checkin-activity-status"

    /** 执行签到 */
    const val DEFAULT_SUBMIT_PATH = "/v2/billing/meter/daily-checkin"

    /** 社区资料中「今天已签到」的业务码 */
    private const val CODE_ALREADY = 10001

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** 今日签到状态查询结果 */
    data class CheckinStatus(
        /** 今日是否已签到；接口未给出可判断字段时为 null */
        val checked: Boolean? = null,
        /** 今日签到可得 / 已得积分 */
        val credit: Double? = null,
        /** 连续签到天数 */
        val continuousDays: Int? = null,
        /** 接口原始 msg，用于排查 */
        val message: String? = null,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** 执行签到结果 */
    data class CheckinResult(
        /** 本次签到成功（拿到积分） */
        val success: Boolean = false,
        /** 今天此前已签到 */
        val already: Boolean = false,
        val credit: Double? = null,
        val message: String? = null,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private fun host(domain: String): String = if (domain.isBlank()) "www.codebuddy.cn" else domain

    fun queryStatus(domain: String, accessToken: String, uid: String, path: String): CheckinStatus {
        if (accessToken.isBlank()) return CheckinStatus(error = "accessToken 为空")
        val (status, body) = post(host(domain), path, accessToken, uid)
        if (status != 200 || body == null) return CheckinStatus(error = "HTTP $status")
        return parseStatus(body)
    }

    fun submit(domain: String, accessToken: String, uid: String, path: String): CheckinResult {
        if (accessToken.isBlank()) return CheckinResult(error = "accessToken 为空")
        val (status, body) = post(host(domain), path, accessToken, uid)
        if (status != 200 || body == null) return CheckinResult(error = "HTTP $status")
        return parseResult(body)
    }

    // ---------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------

    private fun post(host: String, path: String, token: String, uid: String): Pair<Int, String?> {
        val url = "https://$host${path.ifBlank { "/" }}"
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
            if (uid.isNotBlank()) builder.header("X-User-Id", uid)
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body()
        } catch (t: Throwable) {
            log.warn("签到请求失败: $url", t)
            -1 to null
        }
    }

    // ---------------------------------------------------------------
    // 解析（宽松：字段缺失一律置 null，不臆造语义）
    // ---------------------------------------------------------------

    private fun parseStatus(body: String): CheckinStatus {
        val root = parseRoot(body) ?: return CheckinStatus(error = "返回不是 JSON")
        val code = int(root, "code") ?: -1
        val msg = str(root, "msg").ifBlank { null }
        if (code != 0) return CheckinStatus(error = "接口返回 code=$code${msg?.let { ": $it" } ?: ""}")

        val data = root.get("data")
        val scope: JsonObject? = when {
            data == null || data.isJsonNull -> root
            data.isJsonObject -> data.asJsonObject
            else -> root
        }
        return CheckinStatus(
            checked = bool(scope, "checked", "signed", "isCheckin", "hasCheckin", "checkinStatus", "isSigned"),
            credit = num(scope, "credit", "credits", "amount", "points", "rewardCredit", "todayCredit"),
            continuousDays = int(scope, "continuousDays", "continueDays", "signDays", "continuousSignDays", "streak") ,
            message = msg,
        )
    }

    private fun parseResult(body: String): CheckinResult {
        val root = parseRoot(body) ?: return CheckinResult(error = "返回不是 JSON")
        val code = int(root, "code") ?: -1
        val msg = str(root, "msg").ifBlank { null }
        if (code == CODE_ALREADY) return CheckinResult(already = true, message = msg ?: "今天已签到")
        if (code != 0) return CheckinResult(error = "接口返回 code=$code${msg?.let { ": $it" } ?: ""}")

        val data = root.get("data")
        val scope: JsonObject? = when {
            data == null || data.isJsonNull -> root
            data.isJsonObject -> data.asJsonObject
            else -> root
        }
        return CheckinResult(
            success = true,
            credit = num(scope, "credit", "credits", "amount", "points", "rewardCredit", "todayCredit"),
            message = msg,
        )
    }

    private fun parseRoot(body: String): JsonObject? = try {
        val el = JsonParser.parseString(body)
        if (el.isJsonObject) el.asJsonObject else null
    } catch (e: Exception) {
        log.warn("签到响应解析失败", e)
        null
    }

    private fun str(o: JsonObject?, key: String): String = try {
        val el = o?.get(key) ?: return ""
        if (el.isJsonNull) "" else el.asString
    } catch (_: Exception) {
        ""
    }

    private fun int(o: JsonObject?, vararg keys: String): Int? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.toIntOrNull()
                else el.asInt
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun num(o: JsonObject?, vararg keys: String): Double? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.toDoubleOrNull()
                else el.asDouble
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /** 布尔字段：兼容 true/false、1/0、"1"/"0" */
    private fun bool(o: JsonObject?, vararg keys: String): Boolean? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            try {
                return when {
                    el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                    el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt != 0
                    el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                        el.asString.trim().let { it == "1" || it.equals("true", true) }
                    else -> null
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }
}
