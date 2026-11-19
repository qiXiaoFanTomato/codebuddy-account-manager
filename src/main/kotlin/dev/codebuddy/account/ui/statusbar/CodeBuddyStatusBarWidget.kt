package dev.codebuddy.account.ui.statusbar

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
import dev.codebuddy.account.history.ProjectResolver
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.pet.CbEventCenter
import dev.codebuddy.account.pet.CbWebObserver
import dev.codebuddy.account.pet.CodeBuddyWatcher
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.ui.foundation.NumberDisplay
import dev.codebuddy.account.ui.foundation.NumberDisplayBuilder
import dev.codebuddy.account.ui.foundation.RollingReadout
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.Locale
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
        // 首次快照在后台建，装好即可用；之后快路径 1 秒刷新、标题每 3 秒重读一次
        ApplicationManager.getApplication().executeOnPooledThread {
            rebuildStatusSnapshot(true, listOf(ownWorkspace()))
            requestRefresh()
        }
    }

    override fun dispose() {
        rolling.dispose()
        connection?.disconnect()
        connection = null
        INSTANCES.remove(this)
        stopPollerIfIdle()
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
        // 正文只放得下前几段：完整情况在这里铺开
        if (AccountSettings.getInstance().showStatusBarThroughput) {
            val snap = statusSnapshot
            if (snap != null) {
                val ownKey = ownWorkspace()
                sb.append("当前项目：${project.name}\n")
                val own = snap.segments.firstOrNull { it.workspace == ownKey }
                sb.append("· ${own?.title ?: snap.titles[ownKey] ?: "空闲"}\n")
                val others = snap.segments.filter { it.workspace != ownKey }
                if (others.isNotEmpty()) {
                    sb.append("其他活动项目：\n")
                    // tooltip 不参与状态栏宽度，项目名与标题给全
                    others.forEach { sb.append("· ${segmentText(it.project, it.title, it.tail, Int.MAX_VALUE, Int.MAX_VALUE)}\n") }
                }
            }
        }
        // 速度明细放 tooltip：状态栏正文只留一个读数，避免挤压账号与额度。
        // 两个口径分开标：实时是页面字符估算，步末是日志里的真实 token（含工具耗时，属下限）
        throughputSample()?.let { sb.append("上一步实测：${it.detail()}\n") }
        CbEventCenter.recentStream()?.let {
            sb.append("实时速度：${speedTextOf(it.tokensPerSecond)}（页面字符估算）\n")
        }
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
     * 当前窗口项目永远排第一（即使空闲）；其余按「等确认 → 思考中 → 速度」排序。
     * 超过 [MAX_PROJECT_SEGMENTS] 段折叠成 `(+N)`，完整列表放 tooltip。
     */
    private fun appendThroughputPart(b: NumberDisplayBuilder) {
        val segments = renderSegments()
        if (segments.isEmpty()) return
        if (!b.isEmpty) b.text(" · ")
        val shown = segments.take(MAX_PROJECT_SEGMENTS)
        b.text(shown.joinToString(" · "))
        if (segments.size > shown.size) b.text(" (+${segments.size - shown.size})")
    }

    /**
     * 状态栏的一段：一个项目的当前对话与状态/速度。
     *
     * [rank] 决定排序（0 等确认 / 1 思考中 / 2 速度 / 3 当前项目兜底），[at] 用于同优先级排序。
     */
    private class ProjectSegment(
        val workspace: String,
        val project: String,
        val title: String?,
        val tail: String?,
        val rank: Int,
        val at: Long,
    )

    /** 当前项目排第一；快照里没有当前项目时补一段，保证状态栏不会只剩空白 */
    private fun renderSegments(): List<String> {
        val snap = statusSnapshot
            ?: return listOf(segmentText(project.name.ifBlank { "未命名项目" }, null, null, OWN_PROJECT_MAX_CHARS))
        val ownKey = ownWorkspace()
        val own = snap.segments.firstOrNull { it.workspace == ownKey }
            ?: ProjectSegment(
                workspace = ownKey,
                project = project.name.ifBlank { "未命名项目" },
                title = snap.titles[ownKey],
                tail = null,
                rank = OWN_SEGMENT_RANK,
                at = 0L,
            )
        val ordered = listOf(own) + snap.segments.filter { it.workspace != ownKey }
        // 当前项目优先给全名：主人看的是自己窗口的项目名，不能用别人的截断口径
        return ordered.mapIndexed { index, segment ->
            if (index == 0) segmentText(segment.project, segment.title, segment.tail, OWN_PROJECT_MAX_CHARS, OWN_TITLE_MAX_CHARS)
            else segmentText(segment.project, segment.title, segment.tail)
        }
    }

    /** 一段的正文：项目名-对话标题-状态/速度；缺标题时只留项目名与状态 */
    private fun segmentText(
        projectName: String,
        title: String?,
        tail: String?,
        projectMax: Int = PROJECT_MAX_CHARS,
        titleMax: Int = TITLE_MAX_CHARS,
    ): String {
        val sb = StringBuilder(truncate(projectName, projectMax))
        if (!title.isNullOrBlank()) sb.append('-').append(truncate(title, titleMax))
        tail?.let { sb.append('-').append(it) }
        return sb.toString()
    }

    /** 当前窗口项目的 workspace hash：日志文件与会话索引都按这个口径分组 */
    private fun ownWorkspace(): String = try {
        project.basePath?.takeIf { it.isNotBlank() }?.let { ProjectResolver.hashOf(it) }.orEmpty()
    } catch (_: Exception) {
        ""
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
     * 需要速度读数时确保两条通道都在跑：
     *  - 日志通道用「仅统计」模式拿到每步的真实 token 数（步末口径）；
     *  - 页面通道只做实时字符采样，给出亚秒级读数。
     * 桌宠已在跑时日志通道不会被降级成仅统计；页面通道同理只会「仅采样 → 全量」升级。
     */
    private fun ensureThroughputWatcher() {
        if (!AccountSettings.getInstance().showStatusBarThroughput) {
            try {
                CbWebObserver.stopStreamOnly()
            } catch (_: Exception) {
                // 释放失败只是多跑一个采样任务
            }
            return
        }
        try {
            CodeBuddyWatcher.getInstance().startMetricsOnly()
        } catch (_: Exception) {
            // 监控不可用时只是没有速度读数，不影响账号与额度展示
        }
        try {
            CbWebObserver.startStreamOnly()
        } catch (_: Exception) {
            // 页面通道不可用时退回步末口径读数
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

        /** 快路径轮询间隔（毫秒）：只读内存里的运行态与实时样本，不读索引 */
        private const val POLL_INTERVAL_MS = 1_000L

        /** 每几拍读一次会话索引（1s × 3 ≈ 3 秒）：标题读盘不能跟快路径一起跑 */
        private const val SLOW_EVERY = 3

        /** 吞吐样本有效期：超过 2 分钟没有新的一步，视为空闲，不再显示旧速度 */
        private const val THROUGHPUT_TTL_MS = 2 * 60_000L

        /** 状态栏空间有限：其他活动项目的项目名与对话标题各自截断的字数 */
        private const val PROJECT_MAX_CHARS = 12
        private const val TITLE_MAX_CHARS = 14

        /** 当前窗口项目给足宽度：项目名要能完整读出，标题只做兜底截断 */
        private const val OWN_PROJECT_MAX_CHARS = 44
        private const val OWN_TITLE_MAX_CHARS = 22

        /** 正文最多并排几段；超出的项目折叠成 (+N)，完整列表在 tooltip */
        private const val MAX_PROJECT_SEGMENTS = 3

        /** 当前项目兜底段（不在活动列表里时补的那段）的排序位 */
        private const val OWN_SEGMENT_RANK = 3

        /**
         * 进程级状态快照：多开 IDE 时全机共用一份，避免每个窗口各扫一遍会话索引。
         *
         * [titles] 是 workspace -> 当前对话标题，当前项目空闲时靠它补标题。
         */
        private class StatusSnapshot(
            val at: Long,
            val titles: Map<String, String>,
            val segments: List<ProjectSegment>,
        )

        @Volatile
        private var statusSnapshot: StatusSnapshot? = null

        /** 会话标题缓存：读索引是唯一的重活，只按慢路径刷新，快路径沿用这份 */
        @Volatile
        private var titleCache: Map<String, String> = emptyMap()

        /**
         * 重建快照：等确认 → 思考中 → 速度，同级按时间倒序。
         *
         * 项目以 workspace hash 为键，标题按 hash 直读该项目的会话索引。
         * 只能在后台线程调用。
         *
         * @param loadTitles 是否重读会话标题。1 秒的快路径不能每次都读盘，
         *   否则实时读数会把状态栏变成高频文件扫描；快路径沿用上次读到的标题。
         * @param ownKeys 各窗口项目的 workspace hash：即使项目此刻空闲也把标题刷新出来
         */
        private fun rebuildStatusSnapshot(loadTitles: Boolean, ownKeys: List<String>) {
            if (!AccountSettings.getInstance().showStatusBarThroughput) return
            val now = System.currentTimeMillis()
            if (loadTitles) {
                val keys = HashSet<String>()
                CbEventCenter.activeProjects(now).forEach { keys.add(it.workspace) }
                CbEventCenter.projectStreams(now).forEach { keys.add(it.workspace.ifBlank { it.project }) }
                CbEventCenter.projectUsages().forEach { keys.add(it.workspace.ifBlank { it.project }) }
                ownKeys.forEach { keys.add(it) }
                val refreshed = HashMap(titleCache)
                for (key in keys) {
                    if (key.isBlank()) continue
                    // titleOf 自带 3 秒缓存，只读这一个 workspace 的 index.json：
                    // 对话标题一改就能跟上，不会像全量扫描那样长时间停在旧标题
                    val title = try {
                        ActivityFeed.titleOf(key)
                    } catch (_: Exception) {
                        null
                    }
                    if (title != null) refreshed[key] = title
                }
                titleCache = refreshed
            }
            val titles = titleCache
            val merged = LinkedHashMap<String, ProjectSegment>()
            for (activity in CbEventCenter.activeProjects(now)) {
                val waiting = activity.phase == CbEventCenter.Phase.WAITING
                // 实时读数优先：页面通道亚秒级更新；等确认时不显示速度。
                // 面板识别降级（找不到 ToolWindow 归属）时全局样本没有 workspace，
                // 这时才退回全局最近一条，多开同跑时会串，但总比没有读数强
                val live = if (waiting) {
                    null
                } else {
                    CbEventCenter.streamOf(activity.workspace, now)
                        ?: CbEventCenter.recentStream(now)?.takeIf { it.workspace.isEmpty() }
                }
                merged[activity.workspace] = ProjectSegment(
                    workspace = activity.workspace,
                    project = activity.project,
                    title = titles[activity.workspace],
                    tail = when {
                        waiting -> "等确认"
                        live != null -> speedTextOf(live.tokensPerSecond)
                        else -> "思考中"
                    },
                    rank = if (waiting) 0 else 1,
                    at = activity.at,
                )
            }
            // 页面在生成但日志还没报运行态（日志通道没接上时就是这种情况）：也要给出速度段
            for (stream in CbEventCenter.projectStreams(now)) {
                val key = stream.workspace.ifBlank { stream.project }
                if (merged.containsKey(key)) continue
                merged[key] = ProjectSegment(
                    workspace = key,
                    project = stream.project,
                    title = titles[key],
                    tail = speedTextOf(stream.tokensPerSecond),
                    rank = 2,
                    at = stream.at,
                )
            }
            for (usage in CbEventCenter.projectUsages()) {
                if (now - usage.at > THROUGHPUT_TTL_MS) continue
                if (usage.tokensPerSecond <= 0.0) continue
                val key = usage.workspace.ifBlank { usage.project }
                if (merged.containsKey(key)) continue
                merged[key] = ProjectSegment(
                    workspace = key,
                    project = usage.project,
                    title = titles[key],
                    tail = speedTextOf(usage.tokensPerSecond),
                    rank = 2,
                    at = usage.at,
                )
            }
            val ordered = merged.values
                .sortedWith(compareBy<ProjectSegment> { it.rank }.thenByDescending { it.at })
            statusSnapshot = StatusSnapshot(now, titles, ordered)
        }

        /** 速度读数正文：只取整，后缀统一 tok/s */
        private fun speedTextOf(tokensPerSecond: Double): String =
            String.format(Locale.ROOT, "%.0f", tokensPerSecond) + " tok/s"

        private val LOCK = Any()

        /** 后台轮询线程（守护线程，随 IDE 退出；任务在无实例时取消，线程保留复用） */
        private val POLLER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "codebuddy-statusbar-poll").apply { isDaemon = true }
            }

        /** 所有已安装的状态栏实例：一个进程只跑一个轮询任务，逐个通知 */
        private val INSTANCES = java.util.concurrent.CopyOnWriteArraySet<CodeBuddyStatusBarWidget>()
        private val pollerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 轮询任务句柄：最后一个实例 dispose 后取消，避免状态栏不显示时仍每 3 秒重建快照 */
        @Volatile
        private var pollFuture: ScheduledFuture<*>? = null

        private fun ensurePoller() {
            if (!pollerStarted.compareAndSet(false, true)) return
            val rounds = java.util.concurrent.atomic.AtomicInteger()
            pollFuture = POLLER.scheduleWithFixedDelay(
                {
                    // 快照全进程一份：多开窗口时不会每个窗口各扫一遍会话索引
                    val round = rounds.incrementAndGet()
                    try {
                        // 各窗口的项目不同：把它们的 workspace 一起带进去，只重建一次快照
                        val ownKeys = INSTANCES.map { it.ownWorkspace() }.distinct()
                        rebuildStatusSnapshot(round % SLOW_EVERY == 0, ownKeys)
                    } catch (_: Exception) {
                        // 重建失败时沿用上一份
                    }
                    for (widget in INSTANCES) {
                        try {
                            // 设置页重新打开速度开关后无需重启 IDE：轮询顺手把监控拉起（幂等）
                            widget.ensureThroughputWatcher()
                            widget.requestRefresh()
                        } catch (_: Exception) { }
                    }
                },
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        /** 没有状态栏实例时停掉轮询任务；下次安装会重新排期 */
        private fun stopPollerIfIdle() {
            synchronized(LOCK) {
                if (INSTANCES.isNotEmpty()) return
                pollFuture?.cancel(false)
                pollFuture = null
                pollerStarted.set(false)
            }
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
