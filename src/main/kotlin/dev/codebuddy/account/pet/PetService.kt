package dev.codebuddy.account.pet

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.messages.MessageBusConnection
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * 桌宠展示数据：当前激活账号 + 该账号的额度快照。
 *
 * 只做展示用的派生字段，不持有任何网络逻辑。
 */
data class PetData(
    val accountKey: String = "",
    val accountName: String = "",
    val domain: String = "",
    val quota: QuotaSummary? = null,
    /** refresh token 已过期（界面上提示需要重新登录） */
    val tokenExpired: Boolean = false,
) {

    /** 查询失败原因 */
    val error: String? get() = quota?.error

    /** 可用额度剩余百分比 */
    val percent: Double get() = quota?.usableRemainPercent ?: 0.0

    /** 是否处于低额度状态（含朱印提示与下弯嘴） */
    val low: Boolean
        get() {
            val q = quota ?: return false
            return q.error == null && q.usableRemainPercent < PetPainter.LOW_PERCENT
        }

    /**
     * 余额动画档位（0..5，-1 表示无数据）。
     *
     * 与原项目 `events.balance` 一致：按**已用百分比**分档，
     * `idx = 已用 >= 100 ? 5 : floor(已用 / 20)`。
     */
    val balanceTier: Int
        get() {
            val q = quota ?: return -1
            if (q.error != null) return -1
            if (q.usableSize <= 0) return 0
            val usedPercent = ((q.usableSize - q.usableRemain) / q.usableSize * 100.0).coerceIn(0.0, 100.0)
            return if (usedPercent >= 100.0) 5 else (usedPercent / 20.0).toInt().coerceIn(0, 5)
        }

    /** 宠物身上的读数文本 */
    fun readout(): String {
        val q = quota ?: return "—"
        if (q.error != null) return "!"
        return String.format(Locale.ROOT, "%.0f%%", q.usableRemainPercent)
    }

    companion object {
        fun empty(): PetData = PetData()
    }
}

/**
 * 桌宠生命周期服务：单例仲裁、窗口开关、数据轮询与即时刷新。
 *
 * 数据来源全部复用现有能力：
 * - 当前账号：`AccountLibrary.currentActive()`
 * - 额度缓存：`AccountLibrary.quotaCache`（由 [dev.codebuddy.account.service.AutoRefreshService] 与手动刷新写入）
 * - 即时刷新：`AccountLibrary.refreshQuota()`
 *
 * 多开 IDE 时通过 [PetSingleInstance] 保证全机只有一个桌宠：
 * 非持有者不创建窗口，每 3 秒尝试接管；持有者退出后自动接手。
 */
@Service(Service.Level.APP)
class PetService : Disposable {

    private val log = logger<PetService>()

    private val instance = PetSingleInstance()

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-pet").apply { isDaemon = true }
        }

    @Volatile private var window: PetWindow? = null
    @Volatile private var data: PetData = PetData.empty()

    private var pollTask: ScheduledFuture<*>? = null
    private var takeoverTask: ScheduledFuture<*>? = null
    private var connection: MessageBusConnection? = null

    /** 随机小动作的定时器（EDT 单次定时，触发后重新排下一次） */
    private var randomTimer: javax.swing.Timer? = null

    /** 碎碎念的定时器 */
    private var whisperTimer: javax.swing.Timer? = null

    private val started = AtomicBoolean(false)

    /** 按设置启动（启动时与设置页开启时调用，幂等） */
    fun startFromSettings() {
        val app = ApplicationManager.getApplication()
        if (app.isHeadlessEnvironment) return
        if (!PetSettings.getInstance().enabled) return
        if (!started.compareAndSet(false, true)) return

        connectBus()
        schedulePoll()
        ensureOwnership()
        refreshDataAsync()
    }

    /** 设置页开关：立即生效（关闭时释放单例锁，其他 IDE 实例可接管） */
    fun setEnabled(enabled: Boolean) {
        PetSettings.getInstance().enabled = enabled
        if (enabled) {
            startFromSettings()
            return
        }
        cancelTakeover()
        pollTask?.cancel(false)
        pollTask = null
        connection?.disconnect()
        connection = null
        started.set(false)
        SwingUtilities.invokeLater {
            stopRandomActions()
            stopWhisper()
            clearNotices()
            destroyWindow()
        }
        instance.release()
    }

    /** 外观设置（尺寸 / 不透明度 / 置顶 / 动画）变更后立即应用 */
    fun applySettings() {
        val settings = PetSettings.getInstance()
        if (!settings.enabled) {
            setEnabled(false)
            return
        }
        if (!started.get()) {
            startFromSettings()
            return
        }
        SwingUtilities.invokeLater {
            val w = window
            if (w == null) ensureOwnership() else w.applyAppearance()
            scheduleRandomActions()
            scheduleWhisper()
        }
    }

    // ---------------------------------------------------------------
    // 随机小动作
    // ---------------------------------------------------------------

    /** 重新排一次随机小动作；[delayMs] 为 null 时按设置间隔并在 ±20% 内抖动 */
    private fun scheduleRandomActions(delayMs: Long? = null) {
        randomTimer?.stop()
        randomTimer = null
        val settings = PetSettings.getInstance()
        if (!settings.randomActionsEnabled) return
        if (window == null) return
        val base = settings.randomActionMinutes * 60_000L
        val jitter = ((Math.random() - 0.5) * 0.4 * base).toLong()
        val delay = delayMs ?: (base + jitter).coerceAtLeast(MIN_RANDOM_DELAY_MS)
        randomTimer = javax.swing.Timer(delay.toInt()) { runRandomAction() }.apply {
            isRepeats = false
            start()
        }
    }

    private fun runRandomAction() {
        randomTimer = null
        val w = window
        if (w == null || !w.isShowing) {
            scheduleRandomActions()
            return
        }
        // 提示正在展示时不抢动作，让提示与动画成对呈现
        if (w.noticeActive) {
            scheduleRandomActions(RANDOM_RETRY_MS)
            return
        }
        // 一部分概率改为「在屏幕上跑一段」，其余演随机小动作
        val played = if (Math.random() < MOVE_CHANCE) {
            w.performMove() || w.playRandomAction()
        } else {
            w.playRandomAction()
        }
        scheduleRandomActions(if (played) null else RANDOM_RETRY_MS)
    }

    private fun stopRandomActions() {
        randomTimer?.stop()
        randomTimer = null
    }

    /** 重新排一次碎碎念；[delayMs] 为 null 时按设置间隔并在 ±20% 内抖动 */
    private fun scheduleWhisper(delayMs: Long? = null) {
        whisperTimer?.stop()
        whisperTimer = null
        val settings = PetSettings.getInstance()
        if (!settings.whisperEnabled) return
        if (window == null) return
        val base = settings.whisperMinutes * 60_000L
        val jitter = ((Math.random() - 0.5) * 0.4 * base).toLong()
        val delay = delayMs ?: (base + jitter).coerceAtLeast(MIN_RANDOM_DELAY_MS)
        whisperTimer = javax.swing.Timer(delay.toInt()) { runWhisper() }.apply {
            isRepeats = false
            start()
        }
    }

    private fun runWhisper() {
        whisperTimer = null
        val w = window
        if (w == null || !w.isShowing) {
            scheduleWhisper()
            return
        }
        // 提示优先：正在展示系统提示时本轮碎碎念顺延
        if (w.noticeActive) {
            scheduleWhisper(RANDOM_RETRY_MS)
            return
        }
        w.whisper(PetWhisper.nextLine())
        scheduleWhisper()
    }

    private fun stopWhisper() {
        whisperTimer?.stop()
        whisperTimer = null
    }

    // ---------------------------------------------------------------
    // 系统提示队列（成功 / 警告 / 错误等由桌宠承载）
    // ---------------------------------------------------------------

    private class Notice(
        val level: PetNotifier.Level,
        val title: String,
        val content: String,
        val durationMs: Int,
    )

    private val pendingNotices = ArrayList<Notice>()
    private var activeNotice: Notice? = null
    private var noticeTimer: javax.swing.Timer? = null
    private var lastNoticeKey = ""
    private var lastNoticeAt = 0L

    /**
     * 由 [PetNotifier] 调用：桌宠可用时接管这条提示并返回 true，
     * 返回 false 时调用方退回 IDE 气泡通知。
     */
    fun showNotice(
        level: PetNotifier.Level,
        title: String,
        content: String,
        durationMs: Int? = null,
    ): Boolean {
        val settings = PetSettings.getInstance()
        if (!settings.enabled || !settings.noticePetEnabled) return false
        if (window == null) return false
        val notice = Notice(level, title, content, durationMs ?: durationFor(level, content))
        SwingUtilities.invokeLater { enqueueNotice(notice) }
        return true
    }

    /**
     * 入队规则：
     * - 同一条内容 5 秒内只播一次（自动刷新一类的重复告警不会刷屏）
     * - 空闲直接播；否则按等级优先级排队，同级先到先播
     * - 更高优先级会**抢占**当前提示（当前这条直接收起，不回头重播）
     * - 队列上限 6 条，超出时挤掉优先级最低、最早的一条
     */
    private fun enqueueNotice(notice: Notice) {
        if (window == null) return
        val key = notice.level.name + '|' + notice.title + '|' + notice.content
        val now = System.currentTimeMillis()
        if (key == lastNoticeKey && now - lastNoticeAt < NOTICE_DEDUPE_MS) return
        lastNoticeKey = key
        lastNoticeAt = now

        val current = activeNotice
        if (current == null) {
            presentNotice(notice)
            return
        }
        if (notice.level.priority > current.level.priority) {
            finishNotice(showNext = false)
            presentNotice(notice)
            return
        }
        if (pendingNotices.size >= MAX_PENDING_NOTICES) {
            val lowest = pendingNotices.minByOrNull { it.level.priority }
            if (lowest != null) pendingNotices.remove(lowest)
        }
        pendingNotices.add(notice)
    }

    private fun presentNotice(notice: Notice) {
        val w = window ?: return
        activeNotice = notice
        w.showNotice(notice.level, notice.title, notice.content, notice.durationMs)
        noticeTimer?.stop()
        noticeTimer = javax.swing.Timer(notice.durationMs) { finishNotice(showNext = true) }.apply {
            isRepeats = false
            start()
        }
    }

    private fun finishNotice(showNext: Boolean) {
        noticeTimer?.stop()
        noticeTimer = null
        activeNotice = null
        window?.hideNotice()
        if (!showNext) return
        val next = pendingNotices.maxByOrNull { it.level.priority } ?: return
        pendingNotices.remove(next)
        presentNotice(next)
    }

    private fun clearNotices() {
        noticeTimer?.stop()
        noticeTimer = null
        activeNotice = null
        pendingNotices.clear()
        window?.hideNotice()
    }

    /** 展示时长：错误 9s / 警告 7s / 其它 5s，长文本 +2s，封顶 12s */
    private fun durationFor(level: PetNotifier.Level, content: String): Int {
        val base = when (level) {
            PetNotifier.Level.ERROR -> 9_000
            PetNotifier.Level.WARN -> 7_000
            else -> 5_000
        }
        val extra = if (content.length > 60) 2_000 else 0
        return (base + extra).coerceAtMost(12_000)
    }

    /** 立即联网刷新当前账号额度，并广播给状态栏 / 面板 */
    fun refreshNow() {
        executor.execute {
            try {
                val library = AccountLibrary.getInstance()
                val active = library.currentActive()
                if (active != null) library.refreshQuota(active.accountKey)
                AccountEvents.fireQuotaChanged()
            } catch (e: Exception) {
                log.warn("刷新桌宠余额失败", e)
            } finally {
                publish(loadData())
            }
        }
    }

    /** 最近一次读取到的展示数据（供窗口与气泡复用） */
    fun currentData(): PetData = data

    // ---------------------------------------------------------------
    // 单例仲裁
    // ---------------------------------------------------------------

    private fun ensureOwnership() {
        if (window != null) return
        if (instance.tryAcquire()) {
            cancelTakeover()
            SwingUtilities.invokeLater { showWindow() }
            return
        }
        if (takeoverTask != null) return
        takeoverTask = executor.scheduleWithFixedDelay(
            { tryTakeover() },
            TAKEOVER_INTERVAL_SECONDS,
            TAKEOVER_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        log.info("本实例不是桌宠持有者，进入接管轮询")
    }

    private fun tryTakeover() {
        try {
            if (window != null) {
                cancelTakeover()
                return
            }
            if (!PetSettings.getInstance().enabled) {
                cancelTakeover()
                return
            }
            if (instance.tryAcquire()) {
                cancelTakeover()
                log.info("已接管桌宠")
                SwingUtilities.invokeLater { showWindow() }
            }
        } catch (e: Exception) {
            log.warn("接管桌宠失败", e)
        }
    }

    private fun cancelTakeover() {
        try {
            takeoverTask?.cancel(false)
        } catch (_: Exception) {
        }
        takeoverTask = null
    }

    private fun showWindow() {
        if (window != null) return
        if (!PetSettings.getInstance().enabled) return
        try {
            val w = PetWindow(
                onRefresh = { refreshNow() },
                onHide = { setEnabled(false) },
            )
            w.show()
            w.update(data)
            window = w
            refreshDataAsync()
            scheduleRandomActions()
            scheduleWhisper()
        } catch (e: Exception) {
            log.warn("创建桌宠窗口失败，已释放所有权", e)
            instance.release()
            started.set(false)
        }
    }

    private fun destroyWindow() {
        val w = window ?: return
        window = null
        try {
            w.dispose()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------
    // 数据
    // ---------------------------------------------------------------

    private fun schedulePoll() {
        if (pollTask != null) return
        pollTask = executor.scheduleWithFixedDelay(
            { publishQuietly() },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun refreshDataAsync() {
        executor.execute { publishQuietly() }
    }

    private fun publishQuietly() {
        try {
            publish(loadData())
        } catch (_: Exception) {
            // 读取失败保持上一次展示
        }
    }

    /**
     * 推送最新数据到窗口。
     *
     * 同一账号的剩余额度发生变化时，额外算出变化量交给窗口：
     * 浮字显示本次增减，并按新档位重播对应的余额动画（联动动作）。
     */
    private fun publish(d: PetData) {
        data = d
        val remain = d.quota?.takeIf { it.error == null }?.usableRemain
        val delta = if (remain != null && lastAccountKey == d.accountKey && lastRemain.isFinite()) {
            remain - lastRemain
        } else {
            Double.NaN
        }
        if (remain != null && d.accountKey.isNotBlank()) {
            lastRemain = remain
            lastAccountKey = d.accountKey
        }

        SwingUtilities.invokeLater {
            val w = window ?: return@invokeLater
            w.update(d)
            if (delta.isFinite() && Math.abs(delta) > 0.004) {
                w.showQuotaDelta(delta, d.balanceTier)
            }
            w.refreshBubbleAnchor()
        }
    }

    /** 上次推送时的剩余额度（用于计算变化量） */
    private var lastRemain: Double = Double.NaN
    private var lastAccountKey: String = ""

    private fun loadData(): PetData {
        val library = AccountLibrary.getInstance()
        val snapshot = library.currentActive() ?: return PetData.empty()
        val quota = library.quotaCache.loadAll()[snapshot.accountKey]
        return PetData(
            accountKey = snapshot.accountKey,
            accountName = snapshot.displayName,
            domain = snapshot.domainHost,
            quota = quota,
            tokenExpired = snapshot.isRefreshTokenExpired,
        )
    }

    private fun connectBus() {
        if (connection != null) return
        try {
            val c = ApplicationManager.getApplication().messageBus.connect(this)
            c.subscribe(
                AccountEvents.TOPIC,
                object : AccountEvents {
                    override fun accountsChanged() = refreshDataAsync()

                    override fun quotaChanged() = refreshDataAsync()

                    override fun dashboardLayoutChanged(origin: Any) = Unit
                },
            )
            connection = c
        } catch (e: Exception) {
            log.warn("桌宠事件订阅失败", e)
        }
    }

    override fun dispose() {
        cancelTakeover()
        pollTask?.cancel(false)
        pollTask = null
        connection?.disconnect()
        connection = null
        stopRandomActions()
        stopWhisper()
        clearNotices()
        SwingUtilities.invokeLater { destroyWindow() }
        instance.release()
        executor.shutdownNow()
    }

    companion object {
        /** 额度缓存轮询间隔：与状态栏同量级，够「实时」又不压 IO */
        private const val POLL_INTERVAL_MS = 5_000L

        /** 非持有者尝试接管桌宠的间隔 */
        private const val TAKEOVER_INTERVAL_SECONDS = 3L

        /** 随机动作最小延迟（避免把间隔设得过小时过于频繁） */
        private const val MIN_RANDOM_DELAY_MS = 15_000L

        /** 随机动作被打断（睡着 / 被抓起）后的重试间隔 */
        private const val RANDOM_RETRY_MS = 60_000L

        /** 同一内容提示的去重窗口 */
        private const val NOTICE_DEDUPE_MS = 5_000L

        /** 提示队列上限 */
        private const val MAX_PENDING_NOTICES = 6

        /** 随机触发时改用「跑一段」的概率 */
        private const val MOVE_CHANCE = 0.3

        fun getInstance(): PetService =
            ApplicationManager.getApplication().getService(PetService::class.java)
    }
}
