package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.intellij.util.messages.MessageBusConnection
import dev.codebuddy.account.history.ActivityFeed
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.pet.CbEventCenter
import dev.codebuddy.account.pet.CodeBuddyWatcher
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** 状态栏工厂 */
class CodeBuddyStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = CodeBuddyStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "CodeBuddy 账号"

    /**
     * 输出速度是独立读数：主人关掉「账号与剩余额度」时，
     * 只要速度开关还开着，这个 widget 也要继续存在，否则速度开关等于没生效。
     */
    override fun isAvailable(project: Project): Boolean {
        val settings = AccountSettings.getInstance()
        return settings.showStatusBarWidget || settings.showStatusBarThroughput
    }

    override fun createWidget(project: Project): StatusBarWidget =
        CodeBuddyStatusBarWidget(project)

    // 2022.3 的 StatusBarWidgetFactory 里这两个方法还没有默认实现，必须显式提供；
    // 行为与新版平台的默认实现保持一致。
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
}

/**
 * 状态栏展示当前激活账号与剩余额度。
 *
 * 数据读取做了 3 秒节流缓存：状态栏会频繁重绘，
 * 避免每次绘制都去读凭据文件与配额缓存。
 *
 * 额度（积分）数值下降时做缓动递减滚动：widget 安装即视为进入视口，
 * 每次数值变化只播放一次，之后不重置；数值上升则直接显示新值。
 */
class CodeBuddyStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var connection: MessageBusConnection? = null

    /** 当前展示文本（EDT 读写） */
    private var renderedText: String = ""

    /**
     * 每个项目最近一条对话（标题 + 时间）。
     *
     * 数据来自本地会话索引，读盘较重，只在后台线程刷新并写这里；
     * 渲染路径（含 EDT）只读缓存，绝不阻塞状态栏。
     */
    @Volatile
    private var projectBriefs: List<ActivityFeed.ProjectBrief> = emptyList()

    /** 额度读数滚动：状态栏常驻，没有滚动视口，安装后按已可见处理 */
    private val rolling = RollingReadout(decreaseOnly = true) { text ->
        renderedText = text
        updateWidget()
    }

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        connection?.disconnect()
        // 运行态广播（吞吐 / 限流）走应用级消息总线，订阅项目级总线收不到
        connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection?.subscribe(
            AccountEvents.TOPIC,
            object : AccountEvents {
                override fun accountsChanged() {
                    requestRefresh()
                }

                override fun quotaChanged() {
                    requestRefresh()
                }

                // 看板布局只影响工具窗内容，状态栏读数无需重算
                override fun dashboardLayoutChanged(origin: Any) = Unit

                // 运行态变化主要是「最近一步输出速度」更新：只需重算文本，不算额度缓存
                override fun runtimeChanged() {
                    requestRefresh()
                }
            }
        )

        // 先同步取一次，保证状态栏首帧就有内容
        rolling.setVisible(true)
        rolling.offer(computeDisplay(), animate = false)

        // 全进程共用一个轮询任务：多开项目窗口时不再每个窗口各起一个 3 秒任务
        INSTANCES.add(this)
        ensureThroughputWatcher()
        ensurePoller()
        // 首次对话列表在后台读，装好后 3 秒内由轮询补上，期间只显示项目名
        ApplicationManager.getApplication().executeOnPooledThread {
            refreshProjectBriefs()
            requestRefresh()
        }
    }

    override fun dispose() {
        rolling.dispose()
        connection?.disconnect()
        connection = null
        INSTANCES.remove(this)
        statusBar = null
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        // 兜底：若动态移除 widget 失败（内部 API 变动），至少让内容为空；
        // 只关掉额度展示时仍需留出速度读数，所以两个开关都关才彻底清空
        val settings = AccountSettings.getInstance()
        if (!settings.showStatusBarWidget && !settings.showStatusBarThroughput) return ""
        if (renderedText.isEmpty()) {
            // 尚未完成首次加载时同步取一次，保证状态栏不会空白
            rolling.offer(computeDisplay(), animate = false)
        }
        return renderedText
    }

    override fun getTooltipText(): String {
        val sb = StringBuilder()
        val snapshot = snapshot()
        if (snapshot == null) {
            sb.append("CodeBuddy 未登录\n")
        } else {
            val settings = AccountSettings.getInstance()
            if (settings.showStatusBarWidget) {
                sb.append("${snapshot.displayName}（${snapshot.domainHost}）\n")
                sb.append(
                    "Token：${
                        when {
                            snapshot.isRefreshTokenExpired -> "已过期"
                            snapshot.isAccessTokenExpired -> "access token 已过期"
                            else -> "有效"
                        }
                    }\n"
                )
                val quota = quotaOf(snapshot.accountKey)
                if (quota != null && quota.error == null) {
                    sb.append("额度：${quota.summaryText()}（${"%.0f".format(quota.usableRemainPercent)}%）\n")
                    sb.append("细项：${quota.packages.size} 条，其中仍有效 ${quota.usablePackages.size} 条\n")

                    // 分组明细（总的 + 细的）
                    for (group in quota.groups().take(4)) {
                        sb.append("· ${group.name}：${group.usageText()}（${group.packages.size} 条）\n")
                    }

                    quota.nextExpiring?.let {
                        sb.append("最近到期：${it.displayGroup}（${QuotaTime.remainingText(it.expireMillis)}）\n")
                    }
                }
            }
        }
        // 段数超过正文上限时把完整列表铺开，避免状态栏被挤爆后看不全
        if (AccountSettings.getInstance().showStatusBarThroughput) {
            val segments = collectProjectSegments()
            if (segments.size > 1) {
                sb.append("当前有活动的项目：\n")
                segments.forEach { sb.append("· ${segmentText(it)}\n") }
            }
        }
        // 速度明细放 tooltip：状态栏正文只留一个读数，避免挤压账号与额度
        throughputSample()?.let { sb.append("输出速度：${it.detail()}\n") }
        sb.append("点击打开账号管理器")
        return "<html>${sb.toString().trimEnd().replace("\n", "<br/>")}</html>"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        ToolWindowManager.getInstance(project)
            .getToolWindow("CodeBuddy Accounts")
            ?.show()
    }

    // ---------------------------------------------------------------
    // 刷新
    // ---------------------------------------------------------------

    /** 后台线程读取数据，再切回 EDT 应用到状态栏 */
    private fun requestRefresh() {
        try {
            val display = computeDisplay()
            SwingUtilities.invokeLater {
                if (statusBar == null) return@invokeLater
                rolling.offer(display)
            }
        } catch (_: Exception) {
            // 读取失败保持上一次的展示
        }
    }

    private fun updateWidget() {
        val bar = statusBar ?: return
        try {
            bar.updateWidget(WIDGET_ID)
        } catch (_: Throwable) {
            // 平台内部异常时静默降级
        }
    }

    // ---------------------------------------------------------------
    // 内容构建
    // ---------------------------------------------------------------

    /** 按当前设置拼出状态栏文本，并记录其中数值的槽位 */
    private fun computeDisplay(): NumberDisplay {
        val settings = AccountSettings.getInstance()
        val b = NumberDisplayBuilder()
        // 账号与额度是可选段：主人只想要速度时，这两段整体不占位
        if (settings.showStatusBarWidget) appendAccountPart(b, settings)
        // 对话段（项目-标题-速度/状态）由速度开关控制，走 text 段不参与额度缓动滚动
        if (settings.showStatusBarThroughput) appendThroughputPart(b)
        return b.build()
    }

    /**
     * 多项目段：`项目名称-对话标题-状态/速度`，按 ` · ` 并排。
     *
     * 排序：正在跑（思考中 / 等确认）的项目在前，其次是 2 分钟内有步样本的项目；
     * 每个项目只取最近一条对话标题。超过 [MAX_PROJECT_SEGMENTS] 段折叠成 `(+N)`，
     * 完整列表放 tooltip。日志只能判定「这个项目在忙」，
     * 无法判定是哪条对话，因此状态词标在项目这一段上。
     */
    private fun appendThroughputPart(b: NumberDisplayBuilder) {
        val segments = collectProjectSegments()
        if (segments.isEmpty()) return
        if (!b.isEmpty) b.text(" · ")
        val shown = segments.take(MAX_PROJECT_SEGMENTS)
        b.text(shown.joinToString(" · ") { segmentText(it) })
        if (segments.size > shown.size) b.text(" (+${segments.size - shown.size})")
    }

    /** 一段项目读数：[tail] 为状态词或速度，为 null 时只显示项目名与对话标题 */
    private class ProjectSegment(val project: String, val tail: String?)

    /** 收集当前有活动的项目；一个都没有时退回本项目，保证状态栏不会只剩空白 */
    private fun collectProjectSegments(): List<ProjectSegment> {
        val now = System.currentTimeMillis()
        val segments = LinkedHashMap<String, ProjectSegment>()
        for (activity in CbEventCenter.activeProjects(now)) {
            segments.putIfAbsent(activity.project, ProjectSegment(activity.project, phaseText(activity.phase)))
        }
        for (usage in CbEventCenter.projectUsages()) {
            if (now - usage.at > THROUGHPUT_TTL_MS) continue
            if (usage.tokensPerSecond <= 0.0) continue
            segments.putIfAbsent(usage.project, ProjectSegment(usage.project, speedText(usage)))
        }
        if (segments.isEmpty()) {
            val own = project.name.ifBlank { "未命名项目" }
            segments[own] = ProjectSegment(own, phaseText(CbEventCenter.phaseOf(own)))
        }
        return segments.values.toList()
    }

    /** 一段的正文：项目名-对话标题-状态/速度；缺标题时只留项目名与状态 */
    private fun segmentText(segment: ProjectSegment): String {
        val sb = StringBuilder(truncate(segment.project, PROJECT_MAX_CHARS))
        val title = projectBriefs.firstOrNull { it.project.equals(segment.project, ignoreCase = true) }?.title
        if (!title.isNullOrBlank()) sb.append('-').append(truncate(title, TITLE_MAX_CHARS))
        segment.tail?.let { sb.append('-').append(it) }
        return sb.toString()
    }

    /** 运行态文案；空闲返回 null，由速度读数接替第三段 */
    private fun phaseText(phase: CbEventCenter.Phase): String? = when (phase) {
        CbEventCenter.Phase.THINKING -> "思考中"
        CbEventCenter.Phase.WAITING -> "等确认"
        CbEventCenter.Phase.IDLE -> null
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /** 账号名 + 按展示维度选择的额度读数；没有账号或额度数据时只留可用信息 */
    private fun appendAccountPart(b: NumberDisplayBuilder, settings: AccountSettings) {
        val snapshot = snapshot() ?: run {
            b.text("CodeBuddy: 未登录")
            return
        }
        val quota = quotaOf(snapshot.accountKey)
        if (quota == null || quota.error != null) {
            b.text("CodeBuddy: ${snapshot.displayName}")
            return
        }

        b.text("CodeBuddy: ${snapshot.displayName} · ")
        when (settings.statusBarMode) {
            QuotaDisplayMode.TOTAL_PERCENT -> {
                b.num(quota.usableRemainPercent, intText(quota.usableRemainPercent))
                b.text("%")
            }

            QuotaDisplayMode.REMAIN -> {
                b.num(quota.usableRemain, formatOf(quota.usableRemain))
                b.text("/")
                b.num(quota.usableSize, formatOf(quota.usableSize))
            }

            QuotaDisplayMode.GROUP_DETAIL -> {
                val parts = quota.detailParts(2)
                if (parts.isEmpty()) {
                    b.text(quota.summaryText())
                } else {
                    parts.forEachIndexed { index, part ->
                        if (index > 0) b.text(" · ")
                        b.text("${part.name} ")
                        b.num(part.remain, formatOf(part.remain))
                        b.text("/")
                        b.num(part.size, formatOf(part.size))
                    }
                    if (quota.groups().size > parts.size) b.text(" …")
                }
            }

            QuotaDisplayMode.LOWEST_PACKAGE -> {
                val tightest = quota.tightest
                if (tightest == null) {
                    b.text(quota.summaryText())
                } else {
                    b.text("${tightest.displayGroup.take(8)} ")
                    b.num(tightest.remainPercent, intText(tightest.remainPercent))
                    b.text("%")
                }
            }

            QuotaDisplayMode.PACKAGE -> {
                val group = quota.groupByKeyword(settings.statusBarPackage)
                if (group == null) {
                    b.text("未匹配到「${settings.statusBarPackage}」")
                } else {
                    b.text("${group.name.take(10)} ")
                    b.num(group.remain, formatOf(group.remain))
                    b.text("/")
                    b.num(group.size, formatOf(group.size))
                }
            }
        }
    }

    /** 速度读数正文：只取整，后缀统一 tok/s */
    private fun speedText(usage: CbEventCenter.UsageSample): String =
        String.format(Locale.ROOT, "%.0f", usage.tokensPerSecond) + " tok/s"

    /** 与 [dev.codebuddy.account.quota.QuotaPackage.fmt] 保持一致的显示格式 */
    private fun formatOf(value: Double): String =
        if (value == value.toLong().toDouble()) intText(value)
        else String.format(Locale.ROOT, "%.2f", value)

    private fun intText(value: Double): String = String.format(Locale.ROOT, "%.0f", value)

    // ---------------------------------------------------------------
    // 输出速度（tok/s）
    // ---------------------------------------------------------------

    /**
     * 最近一步的输出速度；开关关闭、没有样本或样本过期时返回 null。
     *
     * 口径是 CodeBuddy 日志 `Step execution metrics` 的「本步输出 token / 本步耗时」，
     * 步耗时含工具执行与等待，所以读数是实际生成速度的下界。
     */
    private fun throughputSample(): CbEventCenter.UsageSample? {
        if (!AccountSettings.getInstance().showStatusBarThroughput) return null
        val usage = CbEventCenter.recentUsage() ?: return null
        if (System.currentTimeMillis() - usage.at > THROUGHPUT_TTL_MS) return null
        if (usage.tokensPerSecond <= 0.0) return null
        return usage
    }

    /**
     * 需要速度读数时确保日志监控在跑：桌宠关闭时它不会启动，这里用「仅统计」模式拉起，
     * 只解析吞吐、不判权限确认。桌宠已在跑时不会降级成仅统计。
     */
    private fun ensureThroughputWatcher() {
        if (!AccountSettings.getInstance().showStatusBarThroughput) return
        try {
            CodeBuddyWatcher.getInstance().startMetricsOnly()
        } catch (_: Exception) {
            // 监控不可用时只是没有速度读数，不影响账号与额度展示
        }
    }

    /**
     * 刷新各项目的最近对话标题（只能在后台线程调用）。
     *
     * 只读会话索引的标题与时间，不读消息正文；读不到就留空，
     * 该项目的段只显示项目名与状态/速度。
     */
    private fun refreshProjectBriefs() {
        if (!AccountSettings.getInstance().showStatusBarThroughput) return
        try {
            projectBriefs = ActivityFeed.latestPerProject(PROJECT_BRIEF_LIMIT)
        } catch (_: Exception) {
            // 读不到标题时只是少一段，不影响项目名与状态
        }
    }

    // ---------------------------------------------------------------
    // 数据（3 秒节流）
    // ---------------------------------------------------------------

    private fun snapshot(): CredentialSnapshot? {
        refreshIfStale()
        return cachedSnapshot
    }

    private fun quotaOf(accountKey: String): QuotaSummary? {
        refreshIfStale()
        return cachedQuotas[accountKey]
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastLoadedAt < CACHE_TTL_MS) return
        synchronized(LOCK) {
            if (System.currentTimeMillis() - lastLoadedAt < CACHE_TTL_MS) return
            try {
                cachedSnapshot = AccountLibrary.getInstance().currentActive()
                cachedQuotas = AccountLibrary.getInstance().quotaCache.loadAll()
            } catch (_: Exception) {
                // 读取失败保持上一次的缓存
            } finally {
                lastLoadedAt = System.currentTimeMillis()
            }
        }
    }

    companion object {
        const val WIDGET_ID = "CodeBuddyAccountWidget"

        private const val CACHE_TTL_MS = 3000L

        /** 额度变化轮询间隔（毫秒） */
        private const val POLL_INTERVAL_MS = 3000L

        /** 吞吐样本有效期：超过 2 分钟没有新的一步，视为空闲，不再显示旧速度 */
        private const val THROUGHPUT_TTL_MS = 2 * 60_000L

        /** 状态栏空间有限：多段并排时项目名与对话标题各自截断的字数 */
        private const val PROJECT_MAX_CHARS = 12
        private const val TITLE_MAX_CHARS = 14

        /** 正文最多并排几段；超出的项目折叠成 (+N)，完整列表在 tooltip */
        private const val MAX_PROJECT_SEGMENTS = 3

        /** 取标题时最多覆盖几个项目（一个项目只取最近一条） */
        private const val PROJECT_BRIEF_LIMIT = 8

        private val LOCK = Any()

        /** 后台轮询线程（守护线程，随 IDE 退出） */
        private val POLLER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "codebuddy-statusbar-poll").apply { isDaemon = true }
            }

        /** 所有已安装的状态栏实例：一个进程只跑一个轮询任务，逐个通知 */
        private val INSTANCES = java.util.concurrent.CopyOnWriteArraySet<CodeBuddyStatusBarWidget>()
        private val pollerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun ensurePoller() {
            if (!pollerStarted.compareAndSet(false, true)) return
            POLLER.scheduleWithFixedDelay(
                {
                    for (widget in INSTANCES) {
                        try {
                            // 设置页重新打开速度开关后无需重启 IDE：轮询顺手把监控拉起（幂等）
                            widget.ensureThroughputWatcher()
                            widget.refreshProjectBriefs()
                            widget.requestRefresh()
                        } catch (_: Exception) { }
                    }
                },
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        @Volatile private var cachedSnapshot: CredentialSnapshot? = null
        @Volatile private var cachedQuotas: Map<String, QuotaSummary> = emptyMap()
        @Volatile private var lastLoadedAt: Long = 0L

        /** 清空缓存，使下一次读取立即反映最新凭据 / 配额 */
        fun invalidateCache() {
            lastLoadedAt = 0L
        }
    }
}
