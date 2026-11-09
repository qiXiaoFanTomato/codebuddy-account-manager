package dev.codebuddy.account.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.intellij.util.messages.MessageBusConnection
import dev.codebuddy.account.model.CredentialSnapshot
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** 状态栏工厂 */
class CodeBuddyStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = CodeBuddyStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "CodeBuddy 账号"

    override fun isAvailable(project: Project): Boolean =
        AccountSettings.getInstance().showStatusBarWidget

    override fun createWidget(project: Project): StatusBarWidget =
        CodeBuddyStatusBarWidget(project)
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

    /** 后台轮询任务：定期检查额度是否变化 */
    private var pollTask: ScheduledFuture<*>? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        connection?.disconnect()
        connection = project.messageBus.connect(this)
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
            }
        )

        // 先同步取一次，保证状态栏首帧就有内容
        rolling.setVisible(true)
        rolling.offer(computeDisplay(), animate = false)

        // 后台定期检查额度变化：只有刷新过配额后数值才会下降
        pollTask?.cancel(true)
        pollTask = POLLER.scheduleWithFixedDelay(
            { requestRefresh() },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    override fun dispose() {
        rolling.dispose()
        connection?.disconnect()
        connection = null
        pollTask?.cancel(true)
        pollTask = null
        statusBar = null
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        // 兜底：若动态移除 widget 失败（内部 API 变动），至少让内容为空
        if (!AccountSettings.getInstance().showStatusBarWidget) return ""
        if (renderedText.isEmpty()) {
            // 尚未完成首次加载时同步取一次，保证状态栏不会空白
            rolling.offer(computeDisplay(), animate = false)
        }
        return renderedText
    }

    override fun getTooltipText(): String {
        val snapshot = snapshot() ?: return "CodeBuddy 未登录，点击打开账号管理器"
        val sb = StringBuilder()
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
        val snapshot = snapshot() ?: return NumberDisplay("CodeBuddy: 未登录", emptyList())
        val quota = quotaOf(snapshot.accountKey)
        if (quota == null || quota.error != null) {
            return NumberDisplay("CodeBuddy: ${snapshot.displayName}", emptyList())
        }

        val settings = AccountSettings.getInstance()
        val b = NumberDisplayBuilder()
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
        return b.build()
    }

    /** 与 [dev.codebuddy.account.quota.QuotaPackage.fmt] 保持一致的显示格式 */
    private fun formatOf(value: Double): String =
        if (value == value.toLong().toDouble()) intText(value)
        else String.format(Locale.ROOT, "%.2f", value)

    private fun intText(value: Double): String = String.format(Locale.ROOT, "%.0f", value)

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

        private val LOCK = Any()

        /** 后台轮询线程（守护线程，随 IDE 退出） */
        private val POLLER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "codebuddy-statusbar-poll").apply { isDaemon = true }
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
