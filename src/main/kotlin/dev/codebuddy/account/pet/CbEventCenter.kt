package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CodeBuddy 运行态事件的统一入口。
 *
 * 两个事件源在这里汇合，避免「日志认不出就整条丢失」造成的漏提示：
 *  - [CbWebObserver]：JCEF 页面里的 DOM 常驻观察（权限弹窗 / 限流 / 中断）；
 *  - [CodeBuddyWatcher]：CodeBuddy 运行日志（兜底通道）。
 *
 * 同一事件在 [CLAIM_WINDOW_MS] 内只提示一次，两个通道谁先到谁提示；
 * 模型限流记录会持久化，重启 IDE 后仍保留「预计解封时间」。
 */
object CbEventCenter {

    private val log = logger<CbEventCenter>()

    private const val STORE_KEY = "codebuddy.pet.modelLimits"
    private const val CLAIM_WINDOW_MS = 60_000L
    private const val LIMIT_KEEP_MS = 30 * 60_000L
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 一条模型限制记录；[code] 6004=频率限制，6000=用量限制，14018=额度耗尽 */
    data class ModelLimit(
        val model: String,
        val code: Int,
        val resetAt: Long,
        val at: Long,
    ) {
        val kind: String get() = if (code == 6000) "用量限制" else "频率限制"
        val label: String get() = if (code == 6000) "预计恢复" else "预计解封"

        /** 与 WorkDaddy 的账号页一致：类型 + 解封时刻 + 剩余时间 */
        fun detail(now: Long = System.currentTimeMillis()): String {
            val base = "$model $kind"
            if (resetAt <= 0L) return "$base，${label}时间未知"
            val clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(resetAt), ZoneId.systemDefault())
            return "$base，${label} ${clock.format(TIME_FORMAT)}（${remaining(now)}）"
        }

        fun remaining(now: Long = System.currentTimeMillis()): String {
            val millis = resetAt - now
            if (millis <= 0L) return "已过解封时间"
            val minutes = millis / 60_000L
            val hours = minutes / 60
            return when {
                hours >= 1L -> "约 $hours 小时${if (minutes % 60 > 0) " ${minutes % 60} 分钟" else ""}"
                else -> "约 ${minutes.coerceAtLeast(1L)} 分钟"
            }
        }
    }

    /** 去重表：key = 事件类型|业务键 */
    private val claims = HashMap<String, Long>()

    /** 限流记录：key = model|code */
    private val limits = LinkedHashMap<String, ModelLimit>()

    init {
        loadLimits()
    }

    // ---------------------------------------------------------------
    // 事件入口
    // ---------------------------------------------------------------

    /** 处理一条来自 [CbWebObserver] 的 DOM 事件；解析失败静默跳过 */
    fun onWebEvent(json: String) {
        val e = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return
        } catch (_: Throwable) {
            return
        }
        try {
            when (e.str("t")) {
                "approval" -> onApproval(
                    project = e.str("project").ifBlank { "当前项目" },
                    what = e.str("what").ifBlank { "一个操作需要确认" },
                    dangerous = e.bool("danger"),
                    auto = e.bool("auto"),
                )

                "rateLimit" -> onRateLimit(
                    model = e.str("model").ifBlank { "当前模型" },
                    code = e.int("code") ?: 6004,
                    resetAt = e.long("resetAt") ?: 0L,
                )

                "interrupted" -> onInterrupted(
                    project = e.str("project").ifBlank { "当前项目" },
                    reason = e.str("what").ifBlank { "回复中断" },
                    auto = e.bool("auto"),
                )
            }
        } catch (t: Throwable) {
            log.warn("处理 CodeBuddy 页面事件失败", t)
        }
    }

    /**
     * 日志通道的「等确认」事件。与 DOM 通道共用去重表：
     * DOM 已经提示过的弹窗不会再从日志提示一次，反之亦然。
     */
    fun onLogApproval(project: String, what: String, dangerous: Boolean) =
        onApproval(project, what, dangerous, auto = false)

    /** 日志通道的异常结束事件 */
    fun onLogInterrupted(project: String, reasons: List<String>) =
        onInterrupted(project, reasons.joinToString("、").ifBlank { "回复中断" }, auto = false)

    /** 日志通道识别到的模型限流（业务码 / 解封时间可能在日志里比页面更早出现） */
    fun onLogRateLimit(model: String, code: Int, resetAt: Long) =
        onRateLimit(model, code, resetAt)

    // ---------------------------------------------------------------
    // 分类处理 + 桌宠联动
    // ---------------------------------------------------------------

    private fun onApproval(project: String, what: String, dangerous: Boolean, auto: Boolean) {
        val settings = PetSettings.getInstance()
        if (!settings.cbApproveNoticeEnabled) return
        if (!claim("approval", "$project|$what")) return
        val (level, title) = when {
            auto -> PetNotifier.Level.INFO to "我先替主人点了「运行」"
            dangerous -> PetNotifier.Level.WARN to "有危险命令在等主人点头"
            else -> PetNotifier.Level.WARN to "CodeBuddy 在等主人点头"
        }
        val tail = if (dangerous) "$what（危险命令，不代点）" else what
        PetNotifier.notify(level, title, "「$project」$tail", durationMs = 12_000)
    }

    private fun onRateLimit(model: String, code: Int, resetAt: Long) {
        val settings = PetSettings.getInstance()
        val key = "$model|$code"
        val now = System.currentTimeMillis()
        synchronized(limits) {
            limits[key] = ModelLimit(model, code, resetAt, now)
            limits.entries.removeIf { (_, v) ->
                (v.resetAt in 1 until now - LIMIT_KEEP_MS) || (v.resetAt <= 0L && now - v.at > LIMIT_KEEP_MS)
            }
            saveLimits()
        }
        if (!settings.cbLimitNoticeEnabled) return
        if (!claim("rateLimit", "$key|$resetAt")) return
        val limit = ModelLimit(model, code, resetAt, now)
        PetNotifier.notify(
            PetNotifier.Level.WARN,
            "模型被限流了",
            limit.detail(now),
            durationMs = 12_000,
        )
    }

    private fun onInterrupted(project: String, reason: String, auto: Boolean) {
        if (!PetSettings.getInstance().cbProgressNoticeEnabled) return
        if (!claim("interrupted", "$project|$reason")) return
        if (auto) {
            PetNotifier.notify(
                PetNotifier.Level.INFO,
                "会话中断，已替主人继续",
                "「$project」$reason，已自动点「继续」",
            )
        } else {
            PetNotifier.notify(
                PetNotifier.Level.WARN,
                "CodeBuddy 这轮没跑顺",
                "「$project」$reason，可能需要手动点「继续」",
            )
        }
    }

    /** 账号间会话迁移完成：由切号 / 批量同步流程调用，走桌宠反馈 */
    fun onHistoryMigrated(target: String, added: Int, copied: Int, failed: Int = 0) {
        if (!PetSettings.getInstance().cbHistoryNoticeEnabled) return
        val body = "已把其它账号的历史会话合并到 $target：新增 $added 个，复制消息目录 $copied 个" +
            (if (failed > 0) "，失败 $failed 个" else "") + "。重启 IDE 后侧边栏可见。"
        if (failed > 0) {
            PetNotifier.warn("会话迁移部分失败", body)
        } else {
            PetNotifier.success("会话已迁移", body)
        }
    }

    /** 成长计划 / 活跃状态查询结果：由成长中心调用，走桌宠反馈 */
    fun onGrowthStatus(summary: String) {
        if (!PetSettings.getInstance().cbGrowthNoticeEnabled) return
        PetNotifier.notify(PetNotifier.Level.INFO, "成长计划活跃状态", summary)
    }

    /** 当前仍然有效（未过解封时间太久）的模型限制记录 */
    fun activeLimits(): List<ModelLimit> = synchronized(limits) {
        val now = System.currentTimeMillis()
        limits.values.filter { it.resetAt <= 0L || it.resetAt > now - LIMIT_KEEP_MS }.toList()
    }

    /** 桌宠 / 设置页用的一句话概览 */
    fun limitsSummary(): String = activeLimits().joinToString("；") { it.detail() }

    // ---------------------------------------------------------------
    // 去重与持久化
    // ---------------------------------------------------------------

    /** 抢占一次提示权；同 key 在窗口内只有第一个事件源能提示 */
    fun claim(type: String, key: String): Boolean = synchronized(claims) {
        val now = System.currentTimeMillis()
        if (claims.size > 512) claims.entries.removeIf { (_, at) -> now - at > CLAIM_WINDOW_MS }
        val prev = claims["$type|$key"]
        if (prev != null && now - prev < CLAIM_WINDOW_MS) return false
        claims["$type|$key"] = now
        true
    }

    private fun saveLimits() {
        try {
            val array = JsonArray()
            synchronized(limits) {
                for (v in limits.values) {
                    array.add(JsonObject().apply {
                        addProperty("model", v.model)
                        addProperty("code", v.code)
                        addProperty("resetAt", v.resetAt)
                        addProperty("at", v.at)
                    })
                }
            }
            PropertiesComponent.getInstance().setValue(STORE_KEY, array.toString())
        } catch (t: Throwable) {
            log.warn("保存模型限流记录失败", t)
        }
    }

    private fun loadLimits() {
        try {
            val raw = PropertiesComponent.getInstance().getValue(STORE_KEY) ?: return
            val array = JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray ?: return
            val now = System.currentTimeMillis()
            synchronized(limits) {
                for (el in array) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val at = o.long("at") ?: 0L
                    if (now - at > 24 * 60 * 60_000L) continue
                    val model = o.str("model")
                    val code = o.int("code") ?: 6004
                    if (model.isBlank()) continue
                    limits["$model|$code"] = ModelLimit(model, code, o.long("resetAt") ?: 0L, at)
                }
            }
        } catch (t: Throwable) {
            log.warn("读取模型限流记录失败", t)
        }
    }

    // ---------------------------------------------------------------
    // Gson 取值小工具
    // ---------------------------------------------------------------

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = get(k) ?: continue
            if (v.isJsonPrimitive && v.asJsonPrimitive.isString) return v.asString.trim()
        }
        return ""
    }

    private fun JsonObject.int(key: String): Int? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.long(key: String): Long? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.bool(key: String): Boolean = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    } catch (_: Throwable) {
        false
    }
}
