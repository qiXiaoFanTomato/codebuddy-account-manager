package dev.codebuddy.account.checkin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.growth.GrowthService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 每日签到（领取积分）。
 *
 * 对齐 workbuddy2api-panel 的 DailyCheckin：
 *  - 只有签到接口，没有「签到状态查询」接口；今日是否已签由 [CheckinTracker] 本地按日记录；
 *  - 上游对重复签到返回 code!=0（实测 10001 / 14001）且 msg 含「已签到」/「already」，
 *    按 msg 判定幂等成功，不绑定固定业务码；
 *  - billing/meter 族按 realm 分流：cn 走 /v2 前缀，global 先试无 /v2、404 再回落 /v2；
 *  - 5xx / 网络抖动最多补打 2 次（间隔 2s、4s），4xx 与业务错误不重试。
 *
 * 请求头对齐参考项目 BillingHeaders：Bearer、Accept、X-CodeBuddy-Request、
 * X-User-Id、X-Domain、Accept-Language 与官方桌面端 billing UA。
 * 本机没有 X-Device-Token 凭据，按参考项目的降级策略不注入。
 *
 * ⚠️ 接口非官方公开接口，路径可在设置页覆盖。
 */
object CheckinService {

    private val log = logger<CheckinService>()

    /** CN realm 签到路径（默认） */
    const val DEFAULT_SUBMIT_PATH = "/v2/billing/meter/daily-checkin"

    /** global realm 首选路径，404 时回落 [DEFAULT_SUBMIT_PATH] */
    private const val GLOBAL_SUBMIT_PATH = "/billing/meter/daily-checkin"

    /** 官方桌面端 billing 白名单 UA（wb2api-panel billingUA 默认口径） */
    private const val USER_AGENT = "WorkBuddy/5.5.4"

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BASE_MS = 2_000L

    /** 重复签到的幂等标记（wb2api-panel alreadyCheckinMarkers） */
    private val ALREADY_MARKERS = listOf("已签到", "already")

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** 执行签到结果 */
    data class CheckinResult(
        /** 本次签到成功 */
        val success: Boolean = false,
        /** 今天此前已签到（上游幂等拒绝） */
        val already: Boolean = false,
        val message: String? = null,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** 单次请求结果；transient 表示 5xx / 网络抖动，可重试 */
    private data class Attempt(
        val status: Int = -1,
        val body: String? = null,
        val transient: Boolean = false,
    )

    /**
     * 执行签到。
     *
     * @param path 设置页自定义路径；使用默认路径且为 global realm 时才做双路径 fallback
     */
    fun submit(domain: String, accessToken: String, uid: String, path: String): CheckinResult {
        if (accessToken.isBlank()) return CheckinResult(error = "accessToken 为空")
        val paths = candidatePaths(domain, path)
        var attempt = Attempt()
        for (i in 1..MAX_ATTEMPTS) {
            if (i > 1) {
                try {
                    Thread.sleep(RETRY_BASE_MS * (i - 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return parse(attempt)
                }
            }
            attempt = tryPaths(domain, accessToken, uid, paths)
            if (!attempt.transient) break
        }
        return parse(attempt)
    }

    private fun candidatePaths(domain: String, path: String): List<String> {
        val configured = path.ifBlank { DEFAULT_SUBMIT_PATH }
        val normalized = if (configured.startsWith("/")) configured else "/$configured"
        // 自定义路径不做 realm 分流
        if (normalized != DEFAULT_SUBMIT_PATH || !GrowthService.isGlobal(domain)) return listOf(normalized)
        return listOf(GLOBAL_SUBMIT_PATH, DEFAULT_SUBMIT_PATH)
    }

    /** 仅 404 换路径；其余错误（含业务 code!=0）直接返回 */
    private fun tryPaths(domain: String, token: String, uid: String, paths: List<String>): Attempt {
        var last = Attempt()
        for ((index, p) in paths.withIndex()) {
            last = post(domain, p, token, uid)
            if (last.status != 404 || index == paths.lastIndex) return last
        }
        return last
    }

    private fun post(domain: String, path: String, token: String, uid: String): Attempt {
        val host = domain.ifBlank { "www.codebuddy.cn" }
        val url = "https://$host$path"
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("X-CodeBuddy-Request", "1")
                .header("Accept-Language", if (GrowthService.isGlobal(domain)) "en-US" else "zh-CN")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
            if (uid.isNotBlank()) builder.header("X-User-Id", uid)
            if (domain.isNotBlank()) builder.header("X-Domain", domain)
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            Attempt(status = status, body = response.body(), transient = status >= 500)
        } catch (t: Throwable) {
            log.warn("签到请求失败: $url", t)
            Attempt(status = -1, transient = true)
        }
    }

    private fun parse(attempt: Attempt): CheckinResult {
        if (attempt.status == -1) return CheckinResult(error = "签到请求失败（网络错误）")
        val body = attempt.body
        if (body.isNullOrBlank()) return CheckinResult(error = "HTTP ${attempt.status}（空响应）")

        val root = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            log.warn("签到响应解析失败", e)
            null
        }
        val msg = root?.message()
        if (attempt.status !in 200..299) {
            // 4xx 也可能带「今天已签到」业务体；非 JSON 文本按原文判定（对齐 doJSON 的错误文案口径）
            if (isAlready(msg) || isAlready(body)) {
                return CheckinResult(already = true, message = msg ?: "今天已签到")
            }
            return CheckinResult(error = "HTTP ${attempt.status}${msg?.let { ": $it" } ?: ""}")
        }
        if (root == null) return CheckinResult(error = "返回不是 JSON")

        val code = root.code() ?: 0
        return when {
            code == 0 -> CheckinResult(success = true, message = msg)
            isAlready(msg) -> CheckinResult(already = true, message = msg)
            else -> CheckinResult(error = "接口返回 code=$code${msg?.let { ": $it" } ?: ""}")
        }
    }

    private fun isAlready(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        val lower = msg.lowercase()
        return ALREADY_MARKERS.any { lower.contains(it.lowercase()) }
    }

    private fun JsonObject.code(): Int? = try {
        get("code")?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.message(): String? = try {
        get("msg")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: get("message")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
