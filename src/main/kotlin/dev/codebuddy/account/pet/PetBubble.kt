package dev.codebuddy.account.pet

import com.intellij.util.ui.JBUI
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.ui.AccountUiColors
import dev.codebuddy.account.ui.AccountUiSpacing
import dev.codebuddy.account.ui.AccountUiType
import dev.codebuddy.account.ui.JellyButton
import dev.codebuddy.account.ui.JellyButtonVariant
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.Timer

/**
 * 桌宠气泡：展示当前账号余额（含实时刷新入口）与两个跳转入口。
 *
 * 交互约定：
 * - 点击桌宠打开，再次点击或鼠标移出 700ms 后自动收起（不抢 IDE 焦点）
 * - 数据完全来自 [PetService]，气泡自身不发请求
 */
class PetBubble(
    private val onRefresh: () -> Unit,
    private val onQuotaDetail: () -> Unit,
    private val onHistory: () -> Unit,
) {

    private val window = JWindow()
    private val panel = BubblePanel()

    private val nameLabel = createLabel(AccountUiType.title.toInt(), AccountUiColors.textPrimary, bold = true)
    private val domainLabel = createLabel(AccountUiType.caption.toInt(), AccountUiColors.textMuted)
    private val percentLabel = createLabel(AccountUiType.display.toInt(), AccountUiColors.brassInk, bold = true, mono = true)
    private val summaryLabel = createLabel(AccountUiType.body.toInt(), AccountUiColors.textPrimary)
    private val groupLabel1 = createLabel(AccountUiType.caption.toInt(), AccountUiColors.textSecondary)
    private val groupLabel2 = createLabel(AccountUiType.caption.toInt(), AccountUiColors.textSecondary)
    private val updatedLabel = createLabel(AccountUiType.caption.toInt(), AccountUiColors.textMuted)
    private val bar = QuotaBar()

    private var hideTimer: Timer? = null
    private var visible = false

    init {
        window.isAlwaysOnTop = true
        // 不抢 IDE 焦点：只有设置了 focusableWindowState 才能做到「点一下不切窗口」
        window.setFocusableWindowState(false)
        if (PetPainter.supportsTranslucency()) {
            window.background = Color(0, 0, 0, 0)
        } else {
            window.background = AccountUiColors.panelBg
        }

        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(14, 16, 12, 16)
        panel.isOpaque = false

        panel.add(nameLabel)
        panel.add(vspace(2))
        panel.add(domainLabel)
        panel.add(vspace(10))
        panel.add(percentLabel)
        panel.add(vspace(4))
        panel.add(summaryLabel)
        panel.add(vspace(8))
        panel.add(bar)
        panel.add(vspace(AccountUiSpacing.sm))
        panel.add(groupLabel1)
        panel.add(groupLabel2)
        panel.add(vspace(AccountUiSpacing.sm))
        panel.add(updatedLabel)
        panel.add(vspace(10))

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JellyButton("刷新", JellyButtonVariant.SECONDARY, null, "重新查询当前账号额度") { onRefresh() })
            add(JellyButton("额度明细", JellyButtonVariant.SECONDARY, null, "打开额度明细窗口") { onQuotaDetail() })
            add(JellyButton("历史对话", JellyButtonVariant.GHOST, null, "浏览已有历史会话") { onHistory() })
        }
        panel.add(actions)

        window.contentPane = panel
        installKeepAlive(panel)
    }

    val isShowing: Boolean get() = visible

    /** 以桌宠窗口位置为锚点展示气泡，并填入最新数据 */
    fun show(petBounds: Rectangle, data: PetData) {
        update(data)
        window.pack()
        val size = window.size
        val screen = usableBounds(petBounds.location)
        val centerX = petBounds.x + petBounds.width / 2
        var x = centerX - size.width / 2
        var y = petBounds.y - size.height - JBUI.scale(8)
        if (y < screen.y) y = petBounds.y + petBounds.height + JBUI.scale(8)
        x = x.coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x))
        y = y.coerceIn(screen.y, (screen.y + screen.height - size.height).coerceAtLeast(screen.y))
        window.setLocation(x, y)
        window.isVisible = true
        visible = true
        scheduleHide()
    }

    fun hide() {
        cancelHide()
        if (!visible) return
        visible = false
        window.isVisible = false
    }

    fun dispose() {
        cancelHide()
        visible = false
        window.isVisible = false
        window.dispose()
    }

    /** 用最新数据刷新气泡内容（气泡未展开时也更新，展开即最新） */
    fun update(data: PetData) {
        nameLabel.text = data.accountName.ifBlank { "CodeBuddy 未登录" }
        domainLabel.text = buildString {
            append(data.domain.ifBlank { "—" })
            if (data.tokenExpired) append(" · token 已过期")
        }

        val quota = data.quota
        if (data.error != null) {
            percentLabel.text = "—"
            percentLabel.foreground = AccountUiColors.seal
            summaryLabel.text = "额度查询失败：${data.error}"
            summaryLabel.foreground = AccountUiColors.seal
            bar.percent = 0.0
            groupLabel1.isVisible = false
            groupLabel2.isVisible = false
        } else if (quota == null) {
            percentLabel.text = "—"
            percentLabel.foreground = AccountUiColors.textMuted
            summaryLabel.text = "尚未获取额度，点击「刷新」查询"
            summaryLabel.foreground = AccountUiColors.textMuted
            bar.percent = 0.0
            groupLabel1.isVisible = false
            groupLabel2.isVisible = false
        } else {
            percentLabel.text = String.format(Locale.ROOT, "%.0f%%", quota.usableRemainPercent)
            percentLabel.foreground = if (quota.usableRemainPercent < PetPainter.LOW_PERCENT) {
                AccountUiColors.seal
            } else {
                AccountUiColors.brassInk
            }
            summaryLabel.text = summaryOf(quota)
            summaryLabel.foreground = AccountUiColors.textPrimary
            bar.percent = quota.usableRemainPercent
            val groups = quota.groups().take(2)
            groupLabel1.isVisible = groups.isNotEmpty()
            groupLabel2.isVisible = groups.size > 1
            groupLabel1.text = groups.getOrNull(0)?.let { "· ${it.name.take(12)} ${it.usageText()}（剩 ${fmt(it.remain)}）" }.orEmpty()
            groupLabel2.text = groups.getOrNull(1)?.let { "· ${it.name.take(12)} ${it.usageText()}（剩 ${fmt(it.remain)}）" }.orEmpty()
        }

        updatedLabel.text = when {
            quota == null -> "未查询"
            quota.fetchedAt <= 0 -> "未查询"
            else -> "更新于 ${TIME_FMT.format(Date(quota.fetchedAt))}"
        }
        bar.repaint()
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    private fun summaryOf(quota: QuotaSummary): String {
        if (quota.packages.isEmpty()) return "无可用套餐"
        val unit = quota.unit.ifBlank { "credits" }
        return "剩余 ${fmt(quota.usableRemain)} / ${fmt(quota.usableSize)} $unit"
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v)

    private fun scheduleHide() {
        cancelHide()
        hideTimer = Timer(HIDE_DELAY_MS) {
            window.isVisible = false
            visible = false
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelHide() {
        hideTimer?.stop()
        hideTimer = null
    }

    /** 鼠标进入气泡任意子组件都取消自动收起，离开后重新计时 */
    private fun installKeepAlive(component: Component) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = cancelHide()
            override fun mouseExited(e: MouseEvent) = scheduleHide()
        })
        if (component is java.awt.Container) {
            component.components.forEach { installKeepAlive(it) }
        }
    }

    /** [sizePx] 已是缩放后的像素值（来自 [AccountUiType]），此处不再二次缩放 */
    private fun createLabel(
        sizePx: Int,
        color: Color,
        bold: Boolean = false,
        mono: Boolean = false,
    ): JLabel = JLabel().apply {
        font = if (mono) {
            Font(Font.MONOSPACED, if (bold) Font.BOLD else Font.PLAIN, sizePx)
        } else {
            Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, sizePx)
        }
        foreground = color
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        maximumSize = Dimension(JBUI.scale(BUBBLE_MAX_CONTENT_W), Int.MAX_VALUE)
    }

    private fun vspace(dp: Int): Component = JPanel().apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Integer.MAX_VALUE, JBUI.scale(dp))
        preferredSize = Dimension(1, JBUI.scale(dp))
    }

    private fun usableBounds(around: Point): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val device = env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(around) }
            ?: env.defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    /** 气泡卡片：圆角纸面 + 左上角档案卡 tab + 1px 铅线 */
    private class BubblePanel : JPanel() {

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toFloat()
            val h = height.toFloat()
            val card = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 8f, 8f)
            g2.color = AccountUiColors.cardBg
            g2.fill(card)
            g2.color = AccountUiColors.rule
            g2.stroke = BasicStroke(1f)
            g2.draw(card)

            // 左上角档案卡 tab
            g2.color = AccountUiColors.ink
            g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, JBUI.scale(44).toFloat(), JBUI.scale(5).toFloat(), 3f, 3f))
            g2.dispose()
        }
    }

    /** 额度进度条：轨道 + 黄铜充盈 + 9px 刻度 */
    private class QuotaBar : JComponent() {

        var percent: Double = 0.0
            set(value) {
                field = value.coerceIn(0.0, 100.0)
                repaint()
            }

        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(BUBBLE_MAX_CONTENT_W), JBUI.scale(8))
            maximumSize = Dimension(JBUI.scale(BUBBLE_MAX_CONTENT_W), JBUI.scale(8))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = JBUI.scale(6)
            val y = (height - h) / 2
            g2.color = AccountUiColors.track
            g2.fillRoundRect(0, y, width, h, h, h)
            val fillW = (width * percent / 100.0).toInt()
            if (fillW > 0) {
                g2.color = AccountUiColors.brass
                g2.fillRoundRect(0, y, fillW.coerceAtLeast(h), h, h, h)
            }
            g2.color = AccountUiColors.tick
            g2.stroke = BasicStroke(1f)
            var x = TICK_STEP
            while (x < width) {
                g2.drawLine(x, y - JBUI.scale(1), x, y + h + JBUI.scale(1))
                x += TICK_STEP
            }
            g2.dispose()
        }

        private val TICK_STEP: Int get() = JBUI.scale(9)
    }

    companion object {
        /** 气泡内容最大宽度（dp） */
        private const val BUBBLE_MAX_CONTENT_W = 268

        /** 鼠标移出后自动收起延迟 */
        private const val HIDE_DELAY_MS = 700

        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    }
}
