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
import javax.swing.Timer

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
 * 额度（积分）数值下降时做平滑递减动画：
 * 刷新配额后若剩余变少，状态栏上的数字从旧值逐渐递减到新值，
 * 而不是直接跳变；数值上升则直接显示新值。
 */
class CodeBuddyStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var connection: MessageBusConnection? = null

    /** 当前展示文本（EDT 读写） */
    private var renderedText: String = ""

    /** 上一次稳定的目标展示内容 */
    private var stable: Display? = null

    /** 递减动画状态 */
    private var animFrom: List<Double> = emptyList()
    private var animTo: List<Double> = emptyList()
    private var animFromFormats: List<NumFormat> = emptyList()
    private var animTarget: Display? = null
    private var animProgress: Double = 1.0
    private var animTimer: Timer? = null

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
            }
        )

        // 先同步取一次，保证状态栏首帧就有内容
        val display = computeDisplay()
        stable = display
        renderedText = display.text

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
        animTimer?.stop()
        animTimer = null
        animTarget = null
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
            val display = computeDisplay()
            stable = display
            renderedText = display.text
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
    // 刷新与动画
    // ---------------------------------------------------------------

    /** 后台线程读取数据，再切回 EDT 应用到状态栏 */
    private fun requestRefresh() {
        try {
            val display = computeDisplay()
            SwingUtilities.invokeLater {
                if (statusBar == null) return@invokeLater
                applyDisplay(display, animate = true)
            }
        } catch (_: Exception) {
            // 读取失败保持上一次的展示
        }
    }

    /** 应用新的展示内容；数值下降时走递减动画 */
    private fun applyDisplay(raw: Display, animate: Boolean) {
        val prev = stable
        if (animate && prev != null && sameShape(prev, raw)) {
            val from = currentValues(prev)
            val to = raw.slots.map { it.value }
            if (from.size == to.size && from.indices.any { to[it] < from[it] - EPS }) {
                startAnimation(prev, raw, from, to)
                return
            }
        }
        stopAnimation()
        if (prev?.text == raw.text) return
        stable = raw
        renderedText = raw.text
        updateWidget()
    }

    /**
     * 两次展示的数值位置一致时才可逐个数值做动画。
     *
     * 数字位数会变（100 → 96.7），因此不能比绝对位置，
     * 只比较把数值挖空后的文本骨架。
     */
    private fun sameShape(a: Display, b: Display): Boolean {
        if (a.slots.isEmpty() || a.slots.size != b.slots.size) return false
        return skeleton(a) == skeleton(b)
    }

    /** 把数值挖空后的文本骨架 */
    private fun skeleton(display: Display): String {
        val sb = StringBuilder()
        var cursor = 0
        for (slot in display.slots) {
            sb.append(display.text, cursor, slot.start).append('\u0000')
            cursor = slot.end
        }
        sb.append(display.text, cursor, display.text.length)
        return sb.toString()
    }

    /** 当前正在展示的数值（动画进行中取插值，否则取上一次稳定值） */
    private fun currentValues(prev: Display): List<Double> {
        if (animTarget != null && animProgress < 1.0) {
            val e = ease(animProgress)
            return animFrom.indices.map { i ->
                val f = animFrom[i]
                val t = animTo.getOrElse(i) { f }
                f + (t - f) * e
            }
        }
        return prev.slots.map { it.value }
    }

    private fun startAnimation(prev: Display, raw: Display, from: List<Double>, to: List<Double>) {
        animFrom = from
        animTo = to
        animFromFormats = prev.slots.map { it.format }
        animTarget = raw
        animProgress = 0.0
        val timer = animTimer ?: Timer(FRAME_INTERVAL_MS) { tick() }.also { animTimer = it }
        timer.restart()
    }

    private fun stopAnimation() {
        animTimer?.stop()
        animTarget = null
        animProgress = 1.0
    }

    /** 动画帧：推进进度并刷新状态栏 */
    private fun tick() {
        val target = animTarget ?: return
        animProgress += FRAME_INTERVAL_MS.toDouble() / ANIM_DURATION_MS
        if (animProgress >= 1.0) {
            animProgress = 1.0
            stopAnimation()
            stable = target
            renderedText = target.text
        } else {
            renderedText = renderInterpolated(target)
        }
        updateWidget()
    }

    /** 用插值后的数值重建文本 */
    private fun renderInterpolated(target: Display): String {
        val e = ease(animProgress)
        val sb = StringBuilder()
        var cursor = 0
        target.slots.forEachIndexed { i, slot ->
            sb.append(target.text, cursor, slot.start)
            val f = animFrom.getOrElse(i) { slot.value }
            val t = animTo.getOrElse(i) { slot.value }
            val value = f + (t - f) * e
            val source = animFromFormats.getOrElse(i) { slot.format }
            val format = if (source == NumFormat.DEC2 || slot.format == NumFormat.DEC2) NumFormat.DEC2 else slot.format
            sb.append(format.render(value))
            cursor = slot.end
        }
        sb.append(target.text, cursor, target.text.length)
        return sb.toString()
    }

    /** 缓出曲线：先快后慢，收尾更自然 */
    private fun ease(p: Double): Double {
        val x = p.coerceIn(0.0, 1.0)
        return 1.0 - Math.pow(1.0 - x, 3.0)
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
    private fun computeDisplay(): Display {
        val snapshot = snapshot() ?: return Display("CodeBuddy: 未登录", emptyList())
        val quota = quotaOf(snapshot.accountKey)
        if (quota == null || quota.error != null) {
            return Display("CodeBuddy: ${snapshot.displayName}", emptyList())
        }

        val settings = AccountSettings.getInstance()
        val b = DisplayBuilder()
        b.text("CodeBuddy: ${snapshot.displayName} · ")
        when (settings.statusBarMode) {
            QuotaDisplayMode.TOTAL_PERCENT -> {
                b.num(quota.usableRemainPercent, NumFormat.INT)
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
                    b.num(tightest.remainPercent, NumFormat.INT)
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
    private fun formatOf(value: Double): NumFormat =
        if (value == value.toLong().toDouble()) NumFormat.INT else NumFormat.DEC2

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

        /** 动画帧间隔（毫秒） */
        private const val FRAME_INTERVAL_MS = 16

        /** 递减动画时长（毫秒） */
        private const val ANIM_DURATION_MS = 600.0

        /** 数值比较容差 */
        private const val EPS = 1e-6

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

/** 数值渲染格式 */
private enum class NumFormat(val template: String) {
    INT("%.0f"),
    DEC2("%.2f"),
    ;

    fun render(value: Double): String = String.format(Locale.ROOT, template, value)
}

/** 状态栏文本中的一个数值槽位 */
private data class NumSlot(val start: Int, val end: Int, val value: Double, val format: NumFormat)

/** 状态栏展示内容：文本 + 数值槽位 */
private data class Display(val text: String, val slots: List<NumSlot>)

/** 按顺序拼装文本，并记录每个数值的槽位 */
private class DisplayBuilder {
    private val sb = StringBuilder()
    private val slots = ArrayList<NumSlot>()

    fun text(value: String) {
        sb.append(value)
    }

    fun num(value: Double, format: NumFormat) {
        val start = sb.length
        sb.append(format.render(value))
        slots.add(NumSlot(start, sb.length, value, format))
    }

    fun build(): Display = Display(sb.toString(), slots)
}
