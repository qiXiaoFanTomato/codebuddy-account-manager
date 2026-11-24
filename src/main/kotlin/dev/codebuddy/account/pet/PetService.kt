package dev.codebuddy.account.pet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.Messages
import com.intellij.util.messages.MessageBusConnection
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.Locale
import javax.swing.SwingUtilities



/** 强提醒的优先级加权：必须大于所有普通等级（最高 ERROR = 3） */
private const val HIGHLIGHT_PRIORITY_BOOST = 10

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
 * 桌宠启动状态的只读快照（供看板等只读界面展示）。
 *
 * 全部为即时值，不做缓存：多开 IDE 时的所有权、窗口是否建好都可能在两次读取之间变化。
 */
data class PetStatus(
    /** 设置里的「显示桌面宠物」总开关 */
    val enabled: Boolean,
    /** 本进程已按设置启动（轮询与事件订阅已接上） */
    val started: Boolean,
    /** 本进程持有全机唯一的桌宠所有权 */
    val owner: Boolean,
    /** 桌面窗口已创建 */
    val windowShowing: Boolean,
    /** hook 确认通道的回环端口已就绪 */
    val hookReady: Boolean,
)

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

    /** CodeBuddy hook 审批服务（仅持有者进程开启），null 表示未启用 */
    @Volatile private var hookServer: HookApprovalServer? = null

    /** 确认气泡；只在 EDT 上创建与释放 */
    private var approvalBubble: PetApprovalBubble? = null

    private var pollTask: ScheduledFuture<*>? = null
    private var takeoverTask: ScheduledFuture<*>? = null
    private var connection: MessageBusConnection? = null

    /** 随机小动作的定时器（EDT 单次定时，触发后重新排下一次） */
    private var randomTimer: javax.swing.Timer? = null

    /** 碎碎念的定时器 */
    private var whisperTimer: javax.swing.Timer? = null

    /** 主动讲笑话的定时器 */
    private var jokeTimer: javax.swing.Timer? = null

    private val started = AtomicBoolean(false)

    /** 按设置启动（启动时与设置页开启时调用，幂等） */
    fun startFromSettings() {
        val app = ApplicationManager.getApplication()
        if (app.isHeadlessEnvironment) return
        if (!PetSettings.getInstance().enabled) return
        if (!started.compareAndSet(false, true)) return

        connectBus()
        schedulePoll()
        // 静默的到期检查独立于桌宠动画：起来就挂上，20 秒一轮
        SilentMode.startWatch()
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
        // 桌宠关闭后 hook 与页面通道都停了，静默留着只会让状态栏显示一个假的「静默中」
        SilentMode.disable(PetSettings.getInstance(), "桌宠已关闭，静默随之结束")
        SwingUtilities.invokeLater {
            stopRandomActions()
            stopWhisper()
            stopJoke()
            clearNotices()
            CodeBuddyWatcher.getInstance().stop()
            CbWebObserver.stop()
            HostConfirmWatcher.stop()
            destroyBubble()
            destroyWindow()
        }
        executor.execute { syncHookServer() }
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
            scheduleJoke()
            executor.execute { syncHookServer() }
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
    // 主动讲笑话
    // ---------------------------------------------------------------

    /** 重新排一次主动讲笑话；[delayMs] 为 null 时按设置间隔并在 ±20% 内抖动 */
    private fun scheduleJoke(delayMs: Long? = null) {
        jokeTimer?.stop()
        jokeTimer = null
        val settings = PetSettings.getInstance()
        if (!settings.jokeEnabled) return
        if (window == null) return
        val base = settings.jokeMinutes * 60_000L
        val jitter = ((Math.random() - 0.5) * 0.4 * base).toLong()
        val delay = delayMs ?: (base + jitter).coerceAtLeast(MIN_RANDOM_DELAY_MS)
        jokeTimer = javax.swing.Timer(delay.toInt()) { runJoke() }.apply {
            isRepeats = false
            start()
        }
    }

    /** 定时触发：提示中或上一条还没讲完就顺延，不打断当前表演 */
    private fun runJoke() {
        jokeTimer = null
        val w = window
        if (w == null || !w.isShowing) {
            scheduleJoke()
            return
        }
        if (w.noticeActive) {
            scheduleJoke(RANDOM_RETRY_MS)
            return
        }
        val told = w.tellJoke()
        scheduleJoke(if (told) null else RANDOM_RETRY_MS)
    }

    private fun stopJoke() {
        jokeTimer?.stop()
        jokeTimer = null
    }

    // ---------------------------------------------------------------
    // 录制演示脚本（开发 / 录屏专用）
    // ---------------------------------------------------------------

    private var demoTimer: javax.swing.Timer? = null

    /**
     * 按固定时间线把桌宠的主要表现依次演一遍，供录制演示视频使用：
     * 点击反馈 → 随机动作 → 碎碎念 → 成功提示 → 错误提示 → 额度减少 → 强提醒 → 跑动。
     *
     * 只复用已有入口，不修改任何用户设置；重复触发会先取消上一条时间线。
     * 桌宠窗口未显示（未启用 / 非持有者）时直接跳过。
     */
    fun runDemo() {
        demoTimer?.stop()
        demoTimer = null
        val w = window
        if (w == null || !w.isShowing) {
            log.info("桌宠未显示，跳过演示脚本")
            return
        }
        val steps = listOf<Pair<Long, () -> Unit>>(
            1_000L to { w.demoClick() },
            5_000L to { w.playRandomAction() },
            12_000L to { w.whisper(PetWhisper.nextLine()) },
            19_000L to {
                showNotice(
                    PetNotifier.Level.SUCCESS,
                    "操作成功",
                    "已切换到「演示账号」，重启 IDE 后生效",
                    5_000,
                )
            },
            27_000L to {
                showNotice(
                    PetNotifier.Level.ERROR,
                    "出错了",
                    "导出账号库失败：目标目录不可写",
                    9_000,
                )
            },
            38_000L to { w.demoQuotaDelta(-3.34) },
            45_000L to {
                showNotice(
                    PetNotifier.Level.SUCCESS,
                    "对话完成",
                    "本项目这轮对话已结束，共 12 步",
                    12_000,
                    highlight = true,
                )
            },
            60_000L to { w.performMove() },
        )
        runDemoStep(steps, 0)
    }

    /** 时间线按「距上一步的延迟」串行推进，每步独立计时，单步异常不影响后续 */
    private fun runDemoStep(steps: List<Pair<Long, () -> Unit>>, index: Int) {
        val step = steps.getOrNull(index) ?: return
        demoTimer = javax.swing.Timer(step.first.toInt()) {
            try {
                step.second()
            } catch (e: Exception) {
                log.warn("桌宠演示步骤失败", e)
            }
            runDemoStep(steps, index + 1)
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ---------------------------------------------------------------
    // 系统提示队列（成功 / 警告 / 错误等由桌宠承载）
    // ---------------------------------------------------------------

    private class Notice(
        val level: PetNotifier.Level,
        val title: String,
        val content: String,
        val durationMs: Int,
        /** 强提醒：插到普通提示之前，且桌宠侧有额外表现 */
        val highlight: Boolean,
    ) {
        /** 强提醒优先级高于所有普通等级，避免被错误 / 警告抢占或挤出队列 */
        val priority: Int get() = level.priority + if (highlight) HIGHLIGHT_PRIORITY_BOOST else 0
    }

    private val pendingNotices = ArrayList<Notice>()

    /** 窗口尚未建好时收到的提示，桌宠出现后补播（启动早期的 token 告警会落在这里） */
    private val earlyNotices = ArrayList<Notice>()
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
        highlight: Boolean = false,
    ): Boolean {
        val settings = PetSettings.getInstance()
        if (!settings.enabled || !settings.noticePetEnabled) return false
        // 本实例没拿到桌宠所有权：窗口不会出现，直接让调用方走 IDE 通知
        if (window == null && !instance.isOwner) return false
        val notice = Notice(level, title, content, durationMs ?: durationFor(level, content), highlight)
        SwingUtilities.invokeLater {
            // 启动阶段窗口还没建好：先攒着，等桌宠出现再补播，不能整条丢掉
            if (window == null) {
                if (earlyNotices.size < MAX_PENDING_NOTICES) earlyNotices.add(notice)
            } else {
                enqueueNotice(notice)
            }
        }
        return true
    }

    /** 窗口建好后补播攒下的提示（此时才有气泡定位） */
    private fun flushEarlyNotices() {
        if (earlyNotices.isEmpty()) return
        val list = ArrayList(earlyNotices)
        earlyNotices.clear()
        list.forEach { enqueueNotice(it) }
    }

    /**
     * 入队规则：
     * - 同一条内容 5 秒内只播一次（自动刷新一类的重复告警不会刷屏）
     * - 空闲直接播；否则按有效优先级排队，同级先到先播
     * - 更高优先级会**抢占**当前提示（当前这条直接收起，不回头重播）
     * - 强提醒（对话完成）优先级最高；两条强提醒互不抢占，按顺序都展示
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
        // 两条强提醒按先后顺序排队，避免后一轮完成把前一轮提示吞掉
        if ((!notice.highlight || !current.highlight) && notice.priority > current.priority) {
            finishNotice(showNext = false)
            presentNotice(notice)
            return
        }
        if (pendingNotices.size >= MAX_PENDING_NOTICES) {
            val lowest = pendingNotices.minByOrNull { it.priority }
            if (lowest != null) pendingNotices.remove(lowest)
        }
        pendingNotices.add(notice)
    }

    private fun presentNotice(notice: Notice) {
        val w = window ?: return
        activeNotice = notice
        w.showNotice(notice.level, notice.title, notice.content, notice.durationMs, notice.highlight)
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
        val next = pendingNotices.maxByOrNull { it.priority } ?: return
        pendingNotices.remove(next)
        presentNotice(next)
    }

    private fun clearNotices() {
        noticeTimer?.stop()
        noticeTimer = null
        activeNotice = null
        pendingNotices.clear()
        earlyNotices.clear()
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

    /** 启动状态快照：设置开关 + 本进程启动 / 所有权 / 窗口 / hook 通道 */
    fun status(): PetStatus = PetStatus(
        enabled = PetSettings.getInstance().enabled,
        started = started.get(),
        owner = instance.isOwner,
        windowShowing = window != null,
        hookReady = hookServer?.isRunning == true,
    )

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
            flushEarlyNotices()
            refreshDataAsync()
            scheduleRandomActions()
            scheduleWhisper()
            scheduleJoke()
            // 桌宠就绪后才开始盯 CodeBuddy 日志、聊天面板与宿主原生确认框，避免提示无处可显示
            CodeBuddyWatcher.getInstance().start()
            CbWebObserver.start()
            HostConfirmWatcher.start()
            // hook 只由持有者进程提供，避免多开 IDE 时两个审批端口抢答
            executor.execute { syncHookServer() }
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
    // CodeBuddy hook 审批通道
    // ---------------------------------------------------------------

    /**
     * 按设置同步 hook 服务。
     *
     * - 开关关闭 → 停服务（`settings.json` 里的 hook 配置保留，再开不必重装）
     * - 开启 → 释放脚本 + 写入 hook 配置 + 监听回环端口
     *
     * 任何一步失败都只是回到 CodeBuddy 原生确认框，不影响主流程。
     */
    private fun syncHookServer() {
        val settings = PetSettings.getInstance()
        if (!settings.enabled || settings.cbApprovalMode != CbApprovalMode.HOOK) {
            stopHookServer()
            SwingUtilities.invokeLater { destroyBubble() }
            // 只在本人持有桌宠时动全局配置，避免多开 IDE 时误删另一个实例的 hook
            if (instance.isOwner && CodeBuddyHookInstaller.isInstalled()) {
                // 关掉就卸干净：否则 CodeBuddy 每次工具调用仍会起一个 node 进程空等
                CodeBuddyHookInstaller.uninstall()
            }
            return
        }
        // 宿主配置是主人自己的文件：没点头、也没装过，就一个字都不写，等按钮 / 设置页发起询问
        if (settings.cbHookConsent != CbHookConsent.APPROVED && !CodeBuddyHookInstaller.isInstalled()) {
            stopHookServer()
            return
        }
        if (hookServer?.isRunning == true) return
        ensureHookServer()
    }

    /**
     * 询问主人是否允许改写宿主配置，同意后写入并启动回环端口。
     *
     * 三个入口：设置里切到「桌宠弹窗」、看板「启用桌宠弹窗」按钮、已装过时直接走修复。
     * 必须在 EDT 调用（会弹对话框）。
     *
     * @return true 表示通道已就绪
     */
    fun requestHookInstall(): Boolean {
        val settings = PetSettings.getInstance()
        // 已经装过（老用户升级上来的）：不再问一遍，直接补齐端口
        if (CodeBuddyHookInstaller.isInstalled()) {
            settings.cbHookConsent = CbHookConsent.APPROVED
            val ok = ensureHookServer()
            ApprovalChannel.resetHookBroken()
            if (!ok) {
                PetNotifier.notify(
                    PetNotifier.Level.WARN,
                    "桌宠弹窗通道没起来",
                    "回环端口被占用或配置不可写，确认仍走 CodeBuddy 原生弹框",
                )
            }
            return ok
        }
        val backupTip = if (CodeBuddyHookInstaller.hasBackup()) {
            "原配置备份已存在（${CodeBuddyHookInstaller.backupFile().toAbsolutePath()}），会一直留着。"
        } else {
            "写入前会先把原文件备份成 settings.json.pet-bak，之后可在看板「撤销写入」撤掉。"
        }
        val message = "桌宠弹窗需要往 CodeBuddy 的配置里加一条 PreToolUse hook：\n" +
            CodeBuddyHookInstaller.settingsFile().toAbsolutePath().toString() + "\n\n" +
            "$backupTip\n" +
            "只增加桌宠自己这一条，主人原有的 hooks 与插件设置一个字都不动。\n" +
            "选「以后再说」就不写，确认仍交给 CodeBuddy 原生弹框或页面代点。"
        val yes = Messages.showYesNoDialog(
            message,
            "启用桌宠弹窗确认",
            "写入并备份",
            "以后再说",
            Messages.getQuestionIcon(),
        )
        if (yes != Messages.YES) {
            settings.cbHookConsent = CbHookConsent.DECLINED
            // 主人自己的选择，不再重复报警；只把通道判为不可用，让页面代点接管
            ApprovalChannel.markHookUnavailable()
            return false
        }
        settings.cbHookConsent = CbHookConsent.APPROVED
        val ok = ensureHookServer()
        if (ok) {
            ApprovalChannel.resetHookBroken()
            PetNotifier.notify(
                PetNotifier.Level.SUCCESS,
                "桌宠弹窗通道已启用",
                "CodeBuddy 下次需要确认时，桌宠会弹出气泡等主人裁决",
            )
        } else {
            PetNotifier.notify(
                PetNotifier.Level.WARN,
                "桌宠弹窗通道没启用",
                "找不到 node 或配置不可写，确认仍走 CodeBuddy 原生弹框",
            )
        }
        return ok
    }

    /** 释放脚本 + 写入配置 + 起回环端口；任何一步失败都退回原生确认框 */
    private fun ensureHookServer(): Boolean {
        if (hookServer?.isRunning == true) return true
        if (!CodeBuddyHookInstaller.ensureInstalled()) {
            log.info("CodeBuddy hook 未就绪（缺少 node 或配置不可写），确认请求继续走原生流程")
            return false
        }
        val server = HookApprovalServer { decideHookApproval(it) }
        if (!server.start()) return false
        hookServer = server
        return true
    }

    private fun stopHookServer() {
        hookServer?.stop()
        hookServer = null
    }

    /**
     * hook 线程调用（不是 EDT）：阻塞等主人裁决。
     *
     * 返回 [ApprovalDecision.PASSTHROUGH] 的分支都是「不干预」：
     * 通道未启用 / 敏感调用（凭证、扣费、对外分发、摘要截断）/ 宿主 SafetyRule 必拦 /
     * 桌宠不在 / 主人没点。
     *
     * 危险命令不再硬性人工：由 `cbApprovalDanger` 决定——
     * 「自动放行」直接代办，「问，超时没人管就放行」等气泡超时后代办，
     * 两者都由桌宠 WARN 提示留痕；敏感调用不受该策略影响。
     */
    private fun decideHookApproval(request: HookApprovalRequest): ApprovalDecision {
        val settings = PetSettings.getInstance()
        val risk = RiskClassifier.classify(request)

        // ① 静默运行：优先级高于一切通道判定与分类策略。
        //    必须放在 ApprovalChannel 判定之前——那里会在 hook 被判失效时把通道让位给页面，
        //    静默若排在后面，就会跟着一起失效（这正是「开了静默却还在弹窗」的典型来源）。
        if (SilentMode.isActive(settings)) {
            return decideSilent(request, risk, settings)
        }

        if (settings.cbApprovalMode != CbApprovalMode.HOOK) return ApprovalDecision.PASSTHROUGH
        val w = window ?: return ApprovalDecision.PASSTHROUGH
        val ctx = approvalContext(request)

        // ② 宿主 SafetyRule 会在 hook 之外再拦一次（实测 `Safety rule triggered despite hook presence`），
        //    hook 返回 allow 也逃不掉它的原生三选一框。立刻让位、不阻塞脚本，
        //    由日志通道弹常驻只读卡提醒。静默模式对这三类同样无效——这是宿主的硬约束。
        if (risk.hostSafety) {
            PetAuditLog.approval(
                source = "hook", auto = false, kind = "hostSafety", via = "passthrough",
                label = request.toolName, what = request.detail,
                project = ctx.project, sessionId = request.sessionId,
                danger = !risk.normal, risk = risk.top.name,
            )
            return ApprovalDecision.PASSTHROUGH
        }

        // ③ 取该类别的策略。UNKNOWN（摘要为空）与截断（看不全）恒按「一直问」：
        //    截断时 top 可能仍是 DELETE 这类高风险类别，只看类别会让「删除=直接放行」
        //    的配置把一条看不全的命令放过去，所以这里必须单独判 truncated
        val policy = if (risk.top == CbRiskKind.UNKNOWN || risk.truncated) {
            CbRiskPolicy.ASK
        } else {
            settings.riskPolicyOf(risk.top)
        }

        if (policy == CbRiskPolicy.ALLOW) {
            CbEventCenter.onHookAutoApproval(
                request.toolName, request.detail,
                danger = !risk.normal, project = ctx.project,
                workspace = ctx.workspace, sessionTitle = ctx.title,
            )
            PetAuditLog.approval(
                source = "hook", auto = true, kind = "risk", via = "risk:${risk.top.name}",
                label = request.toolName, what = request.detail,
                project = ctx.project, sessionId = request.sessionId,
                credit = CbRiskKind.BILLING in risk.kinds,
                danger = !risk.normal, risk = risk.top.name,
            )
            return ApprovalDecision.ALLOW
        }

        // 摘要被截断时不提供「超时放行」：看不全就不替主人做决定
        val timeoutAllow = policy == CbRiskPolicy.ASK_THEN_ALLOW && !risk.truncated
        // 气泡即将弹出：告诉日志通道「这条确认有人管了」，别让状态对账再补一张兜底卡
        CbEventCenter.markConfirmNoticed(ctx.workspace.ifBlank { ctx.project })
        val answer = approvalBubble().ask(
            request,
            { w.characterBounds() },
            settings.cbApprovalTimeoutSeconds * 1_000,
            // 任何风险类别都用朱红卡强调，只有 NORMAL 用普通样式
            !risk.normal,
            timeoutAllow,
            // 会话索引查询放在这条 hook 线程上，别让 EDT 去扫盘
            ctx.where,
            // 卡片顶部直接写类别名，主人点之前就知道这是删除还是提权
            if (risk.normal) "" else risk.top.label,
        )
        return when (answer) {
            "allow" -> {
                PetAuditLog.approval(
                    source = "hook", auto = false, kind = "once", via = "risk:${risk.top.name}",
                    label = request.toolName, what = request.detail,
                    project = ctx.project, sessionId = request.sessionId,
                    danger = !risk.normal, risk = risk.top.name,
                )
                ApprovalDecision.ALLOW
            }
            "deny" -> ApprovalDecision.DENY
            APPROVAL_ANSWER_TIMEOUT -> {
                // 超时代办按实际级别留痕：只有配置成「超时放行」才会走到这里
                CbEventCenter.onHookAutoApproval(
                    request.toolName, request.detail,
                    danger = !risk.normal, project = ctx.project,
                    workspace = ctx.workspace, sessionTitle = ctx.title,
                )
                PetAuditLog.approval(
                    source = "hook", auto = true, kind = "timeout", via = "risk:${risk.top.name}",
                    label = request.toolName, what = request.detail,
                    project = ctx.project, sessionId = request.sessionId,
                    danger = !risk.normal, risk = risk.top.name,
                )
                ApprovalDecision.ALLOW
            }
            else -> ApprovalDecision.PASSTHROUGH
        }
    }

    /**
     * 静默运行下的裁决：默认全部放行，红线（凭证 / 扣费）看主人是否勾选放开。
     *
     * 之所以保留「静默但拦红线」这一档：凭证泄露与扣费是所有风险里唯二
     * 连事后补救都做不到的，默认替主人守住，想放开必须单独勾选。
     */
    private fun decideSilent(
        request: HookApprovalRequest,
        risk: RiskProfile,
        settings: PetSettings,
    ): ApprovalDecision {
        val ctx = approvalContext(request)
        val keepRedline = risk.redline && !settings.cbSilentAllowRedline

        if (keepRedline) {
            PetAuditLog.approval(
                source = "hook", auto = false, kind = "redline", via = "silent-hold",
                label = request.toolName, what = request.detail,
                project = ctx.project, sessionId = request.sessionId,
                credit = CbRiskKind.BILLING in risk.kinds,
                danger = true, risk = risk.top.name, silent = true,
            )
            val w = window
            if (w == null) {
                showManualNotice(
                    "凭证 / 扣费确认",
                    ctx.where,
                    request.detail,
                    "静默模式保留了红线，这条需要你自己点",
                )
                return ApprovalDecision.PASSTHROUGH
            }
            CbEventCenter.markConfirmNoticed(ctx.workspace.ifBlank { ctx.project })
            val answer = approvalBubble().ask(
                request,
                { w.characterBounds() },
                settings.cbApprovalTimeoutSeconds * 1_000,
                true,
                false,
                ctx.where,
                // 静默下仍被拦的红线，卡片要写明是哪一类（凭证 / 扣费）
                risk.top.label,
            )
            return when (answer) {
                "allow" -> ApprovalDecision.ALLOW
                "deny" -> ApprovalDecision.DENY
                else -> ApprovalDecision.PASSTHROUGH
            }
        }

        // 逐条计数：每 20 次提醒一次，状态栏同时有常驻标识
        SilentMode.noteApproval(risk.redline)
        CbEventCenter.onHookAutoApproval(
            request.toolName, request.detail,
            danger = risk.redline, project = ctx.project,
            workspace = ctx.workspace, sessionTitle = ctx.title,
        )
        PetAuditLog.approval(
            source = "hook", auto = true, kind = "silent", via = "silent",
            label = request.toolName, what = request.detail,
            project = ctx.project, sessionId = request.sessionId,
            credit = CbRiskKind.BILLING in risk.kinds,
            danger = risk.redline, risk = risk.top.name, silent = true,
        )
        return ApprovalDecision.ALLOW
    }

    /** 一次确认的归属信息：项目 / workspace / 对话标题 / 展示用文案 */
    private class ApprovalContext(
        val project: String,
        val workspace: String,
        val title: String,
        val where: String,
    )

    /**
     * 解析这条确认属于哪个项目、哪个对话。
     *
     * 优先按 sessionId 反查会话索引，cwd 只作兜底；标题优先用这条会话自己的名字，
     * 索引还没写进这条会话时按 workspace 现读当前对话。
     */
    private fun approvalContext(request: HookApprovalRequest): ApprovalContext {
        val resolved = SessionContext.of(request.sessionId, request.transcriptPath)
        val fallback = CbEventCenter.resolveWhere(request.cwd)
        val proj = resolved?.project?.takeIf { it.isNotBlank() } ?: fallback.first
        val ws = resolved?.workspace?.takeIf { it.isNotBlank() } ?: fallback.second
        val title = resolved?.title?.takeIf { it.isNotBlank() }
            ?: if (ws.isNotBlank()) CbEventCenter.titleOf(ws) else ""
        // 项目、标题都可能只认出一半：认不全就只显示认得的那部分，别把空的拼进去
        val where = when {
            proj.isNotBlank() && title.isNotBlank() -> "$proj · $title"
            proj.isNotBlank() -> proj
            else -> title
        }
        return ApprovalContext(proj, ws, title, where)
    }

    /**
     * 必须主人亲手点的确认：弹**常驻只读卡**，不代点、不超时，[reason] 说明为什么不代点。
     *
     * 覆盖三类：扣费确认（永不代点）、宿主安全规则拦截（hook allow 也豁免不了）、
     * 设置成「一直问」的普通 / 危险确认（没有通道会替主人点）。
     * 卡片一直挂着，直到主人点「知道了」或宿主动作完成（[dismissManualNotice]）。
     */
    fun showManualNotice(title: String, where: String, detail: String, reason: String) {
        if (window == null) {
            // 桌宠不在（关掉 / 非持有者）：退回 IDE 通知，别让这条确认静默挂着
            PetNotifier.notify(
                PetNotifier.Level.WARN,
                title,
                "「${where.ifBlank { "当前项目" }}」$detail（桌宠不代点）",
                durationMs = 12_000,
            )
            return
        }
        try {
            approvalBubble().notice(
                { window?.characterBounds() },
                title,
                where.ifBlank { "当前项目" },
                detail,
                reason,
            )
        } catch (e: Exception) {
            log.warn("手动确认提醒卡显示失败", e)
        }
    }

    /** 宿主动作已完成（命令开始执行 / 被拒绝 / 扣费已确认）：收起提醒卡；没有卡片时什么都不做 */
    fun dismissManualNotice() {
        try {
            approvalBubble?.dismissNotice()
        } catch (_: Exception) {
        }
    }

    /** 气泡只能在 EDT 创建；重复调用返回同一个实例 */
    private fun approvalBubble(): PetApprovalBubble {
        approvalBubble?.let { return it }
        var created: PetApprovalBubble? = null
        try {
            SwingUtilities.invokeAndWait {
                created = approvalBubble ?: PetApprovalBubble().also { approvalBubble = it }
            }
        } catch (e: Exception) {
            log.warn("创建确认气泡失败", e)
        }
        return created ?: PetApprovalBubble().also { approvalBubble = it }
    }

    private fun destroyBubble() {
        try {
            approvalBubble?.dispose()
        } catch (_: Exception) {
        }
        approvalBubble = null
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

                    override fun runtimeChanged() = Unit
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
        stopJoke()
        demoTimer?.stop()
        demoTimer = null
        clearNotices()
        CodeBuddyWatcher.getInstance().stop()
        CbWebObserver.stop()
        HostConfirmWatcher.stop()
        SwingUtilities.invokeLater {
            destroyBubble()
            destroyWindow()
        }
        // 退出时卸掉 hook，配置里不留一条指向已停止服务的记录；非持有者不能动全局配置
        if (instance.isOwner) {
            stopHookServer()
            CodeBuddyHookInstaller.uninstall()
        }
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
