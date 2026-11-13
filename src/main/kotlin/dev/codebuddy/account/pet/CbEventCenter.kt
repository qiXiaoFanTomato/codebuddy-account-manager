package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.history.ActivityFeed
import dev.codebuddy.account.service.AccountEvents
import java.time.Instant
import java.time.LocalDate
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

    /** 今日代点次数：日期 + 计数分开存，跨天靠日期判定归零 */
    private const val AUTO_DAY_KEY = "codebuddy.pet.autoApproveDay"
    private const val AUTO_COUNT_KEY = "codebuddy.pet.autoApproveCount"

    /** 默认放行事件在列表里的合并窗口 */
    private const val AUTO_EVENT_MERGE_MS = 60_000L

    /** 默认放行提示的最小间隔：与桌宠气泡的停留时长一致，避免连续放行时刷屏 */
    private const val AUTO_NOTICE_GAP_MS = 5_000L

    private val WHITESPACE = Regex("\\s+")
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
        val kind: String
            get() = when (code) {
                6000 -> "用量限制"
                14018 -> "额度不足"
                else -> "频率限制"
            }
        val label: String get() = if (code == 6000) "预计恢复" else "预计解封"

        /** 类型 + 解封时刻 + 剩余时间；官方没给解封时间时给出可执行建议，不编造时间 */
        fun detail(now: Long = System.currentTimeMillis()): String {
            val base = "$model $kind"
            if (resetAt <= 0L) return "$base，${advice()}"
            val clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(resetAt), ZoneId.systemDefault())
            return "$base，${label} ${clock.format(TIME_FORMAT)}（${remaining(now)}）"
        }

        private fun advice(): String = when (code) {
            6000 -> "用量已达上限，等额度周期重置后再试"
            14018 -> "套餐额度不足，建议切换账号或补充额度"
            else -> "稍等片刻再试，或换一个模型"
        }

        fun remaining(now: Long = System.currentTimeMillis()): String {
            // 没有官方解封时间时不能按 0 计算，否则会显示成「已过解封时间」
            if (resetAt <= 0L) return "时间未知"
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

    /**
     * 最近一步的输出吞吐。
     *
     * [durationMs] 取 CodeBuddy 日志 `Step execution metrics: duration=...` 的步耗时口径，
     * 含工具执行与等待，因此速度是**下界估计**，不是纯生成速率。
     */
    data class UsageSample(
        val model: String,
        val outputTokens: Int,
        val durationMs: Long,
        val at: Long,
    ) {
        val tokensPerSecond: Double
            get() = if (durationMs <= 0L || outputTokens <= 0) 0.0 else outputTokens * 1000.0 / durationMs

        fun detail(): String {
            val seconds = String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1000.0)
            val speed = String.format(java.util.Locale.ROOT, "%.0f", tokensPerSecond)
            return "$model $outputTokens tok / ${seconds}s ≈ $speed tok/s"
        }
    }

    /** 功能页展示用的最近事件 */
    data class EventRecord(
        val at: Long,
        val type: String,
        val title: String,
        val detail: String,
        val auto: Boolean,
    ) {
        val timeText: String
            get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val typeLabel: String
            get() = when (type) {
                "approval" -> "权限弹窗"
                "rateLimit" -> "模型限制"
                "interrupted" -> "会话中断"
                "phrase" -> "快捷短语"
                "growth" -> "成长活跃"
                "history" -> "会话迁移"
                else -> type
            }
    }

    private const val MAX_EVENTS = 60

    /** 最近事件（倒序，功能页直接读） */
    private val events = ArrayList<EventRecord>()

    /** 今日代点次数的落盘状态；[autoDay] 为 yyyy-MM-dd，与当天不符即视为 0 */
    @Volatile
    private var autoDay: String = ""

    @Volatile
    private var autoCount: Int = 0

    fun recentEvents(): List<EventRecord> = synchronized(events) { events.asReversed().toList() }

    fun clearEvents() {
        synchronized(events) { events.clear() }
    }

    /** 今日代点次数；跨天读到旧日期直接返回 0，不改写落盘值 */
    fun autoApprovalsToday(): Int {
        if (autoDay != LocalDate.now().toString()) return 0
        return autoCount
    }

    /**
     * 记一次「代点」（脚本自动同意 / hook 默认放行 / 日志兜底点同意）。
     *
     * 只累计今日次数，不动事件列表也不弹提示（提示由各自通道负责）；
     * hook 默认放行需要提示，走 [onHookAutoApproval]。
     */
    fun countAutoApproval() {
        val today = LocalDate.now().toString()
        synchronized(this) {
            autoCount = if (autoDay == today) autoCount + 1 else 1
            autoDay = today
            saveAutoCount()
        }
    }

    /**
     * hook 通道的默认放行：桌宠必须提示，主人才知道它刚替自己点了。
     *
     * 计数与事件分开处理：
     * - 今日代点次数每次都 +1，这是「今天一共执行了多少次」的口径；
     * - 事件列表把 1 分钟内的连续放行合并成一条，否则每次工具调用都插一条，
     *   60 条窗口会瞬间刷满，限流、中断这些真要看的事件全被挤掉。
     */
    fun onHookAutoApproval(tool: String, detail: String) {
        val name = tool.ifBlank { "工具调用" }
        val what = detail.replace(WHITESPACE, " ").trim().take(60)
        val title = "我先替主人放行了「$name」"
        val ordinal = autoApprovalsToday() + 1
        val content = if (what.isBlank()) "今日第 $ordinal 次代点" else "$what · 今日第 $ordinal 次代点"
        // hook 通道与日志兜底可能同时认出同一次放行：去重键只取命令原文
        // （hook 报的是工具名 Bash，日志报的是「命令」），提示只播一次，次数照常累计
        val duplicated = !claim("autoAllow", what)
        val now = System.currentTimeMillis()
        // 再加一个最小间隔：一轮对话里连续放行时不把提示刷成走马灯，只更新次数与事件
        if (!duplicated && now - lastAutoNoticeAt >= AUTO_NOTICE_GAP_MS) {
            lastAutoNoticeAt = now
            // 默认放行是高频路径，用 INFO 的默认 5 秒时长，别让提示一直占着桌宠
            PetNotifier.notify(PetNotifier.Level.INFO, title, content)
        }
        countAutoApproval()
        mergeAutoEvent(title, content)
    }

    /**
     * 日志通道认出的「默认放行」：宿主把命令直接放过去了（`needConfirm=false`）。
     *
     * 与 [onHookAutoApproval] 同一套文案与计数，去重键也一致，
     * 因此 hook 真正接管时同一条命令不会被提示两次。
     */
    fun onLogAutoApproval(tool: String, detail: String) {
        if (!PetSettings.getInstance().enabled) return
        onHookAutoApproval(tool, detail)
    }

    /** 默认放行的紧凑记录：上一条也是放行就原地更新，保持事件列表不被放行记录淹没 */
    private fun mergeAutoEvent(title: String, content: String) {
        val now = System.currentTimeMillis()
        synchronized(events) {
            val last = events.lastOrNull()
            if (last != null && last.type == "approval" && last.auto && now - last.at < AUTO_EVENT_MERGE_MS) {
                events[events.size - 1] = EventRecord(now, "approval", title, content, true)
                return
            }
            events.add(EventRecord(now, "approval", title, content, true))
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    private fun record(type: String, title: String, detail: String, auto: Boolean = false) {
        synchronized(events) {
            events.add(EventRecord(System.currentTimeMillis(), type, title, detail, auto))
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
        if (auto && type == "approval") countAutoApproval()
    }

    /** 去重表：key = 事件类型|业务键 */
    private val claims = HashMap<String, Long>()

    /** 限流记录：key = model|code */
    private val limits = LinkedHashMap<String, ModelLimit>()

    /** 最近一步的输出吞吐（日志通道按步写入） */
    @Volatile
    private var lastUsage: UsageSample? = null

    /** 吞吐刷新的节流时刻：一轮对话可能有几十步，看板不必每步重建 */
    @Volatile
    private var lastUsageFireAt = 0L

    /** 上一条「默认放行」提示的播放时刻，用于最小间隔节流 */
    @Volatile
    private var lastAutoNoticeAt = 0L

    init {
        loadLimits()
        loadAutoCount()
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
                    // 页面通道带结构化 modelName / modelId，缺省时才退回「当前模型」
                    model = e.str("modelName", "model", "modelId").ifBlank { "当前模型" },
                    code = e.int("code") ?: 6004,
                    resetAt = e.long("resetAt") ?: 0L,
                )

                "interrupted" -> onInterrupted(
                    project = e.str("project").ifBlank { "当前项目" },
                    reason = e.str("what").ifBlank { "回复中断" },
                    auto = e.bool("auto"),
                    blocked = e.bool("blocked"),
                )

                "phrase" -> onPhrase(e.str("status"))
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
        // 代点是替主人做决定，必须提示并留痕，不受「提醒」开关影响；
        // 危险命令即使不代点也要提示，否则关掉「权限提醒」后会静默卡在那里没人知道
        if (!auto && !dangerous && !PetSettings.getInstance().cbApproveNoticeEnabled) return
        if (!claim(if (auto) "approvalAuto" else "approval", "$project|$what")) return
        val (level, title) = when {
            auto -> PetNotifier.Level.INFO to "我先替主人点了「运行」"
            dangerous -> PetNotifier.Level.WARN to "有危险命令在等主人点头"
            else -> PetNotifier.Level.WARN to "CodeBuddy 在等主人点头"
        }
        val tail = if (dangerous && !auto) "$what（危险命令，不代点）" else what
        val content = "「$project」$tail"
        PetNotifier.notify(level, title, content, durationMs = 12_000)
        record("approval", title, content, auto)
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
        // 记录已落库，看板的「模型频率限制」卡片需要立刻看到新的解封时间
        AccountEvents.fireRuntimeChanged()
        if (!settings.cbLimitNoticeEnabled) return
        if (!claim("rateLimit", "$key|$resetAt")) return
        val limit = ModelLimit(model, code, resetAt, now)
        val detail = limit.detail(now)
        PetNotifier.notify(PetNotifier.Level.WARN, "模型被限流了", detail, durationMs = 12_000)
        record("rateLimit", "模型被限流了", detail)
    }

    /**
     * 快捷短语投递回执（页面侧脚本回报）。
     *
     * 正常投递成功不额外弹提示——管理窗口在投递时已经反馈过一次；
     * 只有页面里找不到输入框 / 脚本异常才补一条警告，避免主人以为已经发出去了。
     */
    private fun onPhrase(status: String) {
        val (title, detail) = when (status) {
            "sent" -> "快捷短语已发送" to "CodeBuddy 输入框已提交"
            "filled" -> "快捷短语已填入" to "等主人确认后再发送"
            "noinput" -> "没找到 CodeBuddy 输入框" to "面板可能还没加载完，稍后再试"
            else -> "快捷短语投递失败" to "页面脚本执行异常，可重开 CodeBuddy 侧边栏再试"
        }
        record("phrase", title, detail)
        if (status == "noinput" || status == "error" || status.isBlank()) {
            PetNotifier.warn(title, detail)
        }
    }

    private fun onInterrupted(project: String, reason: String, auto: Boolean, blocked: Boolean = false) {
        // 自动继续同样是替主人动作，必须提示并留痕
        if (!auto && !blocked && !PetSettings.getInstance().cbProgressNoticeEnabled) return
        val claimType = when {
            auto -> "interruptedAuto"
            blocked -> "interruptedBlocked"
            else -> "interrupted"
        }
        if (!claim(claimType, "$project|$reason")) return
        if (blocked) {
            val content = "「$project」$reason（10 分钟内已自动续跑 5 次，先停一下）"
            PetNotifier.notify(
                PetNotifier.Level.WARN,
                "连续中断，已停止自动继续",
                content,
                durationMs = 12_000,
            )
            record("interrupted", "连续中断，已停止自动继续", content)
            return
        }
        val (level, title, content) = if (auto) {
            Triple(
                PetNotifier.Level.INFO,
                "会话中断，已替主人继续",
                "「$project」$reason，已自动点「继续」",
            )
        } else {
            Triple(
                PetNotifier.Level.WARN,
                "CodeBuddy 这轮没跑顺",
                "「$project」$reason，可能需要手动点「继续」",
            )
        }
        PetNotifier.notify(level, title, content)
        record("interrupted", title, content, auto)
    }

    /** 账号间会话迁移完成：由切号 / 批量同步流程调用，走桌宠反馈 */
    fun onHistoryMigrated(target: String, added: Int, copied: Int, failed: Int = 0) {
        val body = "已把其它账号的历史会话合并到 $target：新增 $added 个，复制消息目录 $copied 个" +
            (if (failed > 0) "，失败 $failed 个" else "") + "。重启 IDE 后侧边栏可见。"
        val title = if (failed > 0) "会话迁移部分失败" else "会话已迁移"
        record("history", title, body)
        if (!PetSettings.getInstance().cbHistoryNoticeEnabled) return
        if (failed > 0) {
            PetNotifier.warn(title, body)
        } else {
            PetNotifier.success(title, body)
        }
    }

    /** 成长计划 / 活跃状态查询结果：由成长中心调用，走桌宠反馈 */
    fun onGrowthStatus(summary: String) {
        record("growth", "成长计划活跃状态", summary)
        if (!PetSettings.getInstance().cbGrowthNoticeEnabled) return
        PetNotifier.notify(PetNotifier.Level.INFO, "成长计划活跃状态", summary)
    }

    /** 一轮对话正常完成：桌宠强提醒补上项目名、对话标题与「主人」后的正文摘要 */
    fun onConversationFinished(project: String, steps: Int) {
        record("growth", "对话完成", "「$project」共 $steps 步")
        if (!PetSettings.getInstance().cbProgressNoticeEnabled) return
        ActivityFeed.invalidate()
        ApplicationManager.getApplication().executeOnPooledThread {
            // 日志早于 index.json 落盘，稍等一拍才读得到刚结束的这轮
            try {
                Thread.sleep(800L)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }
            val item = ActivityFeed.recent(limit = 1, maxConversations = 4).firstOrNull()
            val detail = if (item == null) {
                "「$project」共 $steps 步，跑完了"
            } else {
                "「${item.project}」${item.title}：${item.excerpt}"
            }
            ApplicationManager.getApplication().invokeLater {
                PetNotifier.notify(
                    PetNotifier.Level.SUCCESS,
                    "对话完成啦，回来看看",
                    detail,
                    durationMs = 12_000,
                    highlight = true,
                )
            }
        }
    }

    /** 最近一步的输出吞吐；没有样本时为 null */
    fun recentUsage(): UsageSample? = lastUsage

    /**
     * 日志通道：一步结束时的 usage 与步耗时。
     *
     * 只看数量级，不弹桌宠提示；看板的吞吐行与工具提示读这里。
     */
    fun onLogUsage(model: String, outputTokens: Int, durationMs: Long) {
        if (outputTokens <= 0 || durationMs <= 0L) return
        val now = System.currentTimeMillis()
        lastUsage = UsageSample(
            model = model.trim().ifBlank { "当前模型" },
            outputTokens = outputTokens,
            durationMs = durationMs,
            at = now,
        )
        if (now - lastUsageFireAt < 10_000L) return
        lastUsageFireAt = now
        AccountEvents.fireRuntimeChanged()
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

    private fun saveAutoCount() {
        try {
            val props = PropertiesComponent.getInstance()
            props.setValue(AUTO_DAY_KEY, autoDay)
            props.setValue(AUTO_COUNT_KEY, autoCount.toString())
        } catch (t: Throwable) {
            log.warn("保存今日代点次数失败", t)
        }
    }

    private fun loadAutoCount() {
        try {
            val props = PropertiesComponent.getInstance()
            autoDay = props.getValue(AUTO_DAY_KEY) ?: ""
            autoCount = props.getValue(AUTO_COUNT_KEY)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            log.warn("读取今日代点次数失败", t)
        }
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
