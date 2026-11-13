package dev.codebuddy.account.pet

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import dev.codebuddy.account.ui.AccountUiType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 桌宠确认气泡：CodeBuddy 通过 hook 把「要不要执行」抛过来时，在角色上方弹一张卡片，
 * 主人点「允许 / 拒绝」即完成裁决；超时自动收起，把决定权交回 CodeBuddy 原生确认框。
 *
 * 与 [PetSpeechBubble] 的区别：这里必须能接收点击，所以窗口可获焦、置顶、不自动消失
 * （只在倒计时结束时收起），并且**同一时刻只处理一个请求**。
 */
class PetApprovalBubble {

    private val window = JWindow()
    private val panel = CardPanel()

    private val chipLabel = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.micro, Font.BOLD)
        foreground = AccountUiColors.seal
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }
    private val titleLabel = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.title, Font.BOLD)
        foreground = AccountUiColors.textPrimary
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }
    private val detailLabel = JBLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        foreground = AccountUiColors.ink
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }
    private val hintLabel = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.micro)
        foreground = AccountUiColors.textMuted
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }
    private val allowButton = JButton("同意").apply {
        font = AccountUiType.ui(AccountUiType.body, Font.BOLD)
        margin = JBUI.insets(5, 18)
        isFocusPainted = false
    }
    private val denyButton = JButton("取消").apply {
        font = AccountUiType.ui(AccountUiType.body)
        margin = JBUI.insets(5, 18)
        isFocusPainted = false
    }

    private var countdownTimer: Timer? = null
    private var remainingMs = 0L

    /** 当前这张气泡是不是危险操作确认 */
    private var riskyMode = false

    /** 只允许一张气泡存在：串行化所有裁决请求 */
    private val lock = Any()

    init {
        window.isAlwaysOnTop = true
        window.setFocusableWindowState(true)
        window.background = if (PetPainter.supportsTranslucency()) Color(0, 0, 0, 0) else AccountUiColors.cardBg

        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(12, 16, 12, 14)
        panel.isOpaque = false
        panel.add(chipLabel)
        panel.add(titleLabel)
        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(detailLabel)
        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
        panel.add(hintLabel)
        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(denyButton)
            add(allowButton)
        }
        panel.add(buttons)
        window.contentPane = panel
    }

    /** 当前是否有请求正在等主人裁决 */
    val isShowing: Boolean get() = window.isVisible

    /**
     * 阻塞等待裁决。调用方是 hook 服务线程，不能是 EDT。
     *
     * @param risky 危险 / 敏感调用：样式加重，且**永远不会自动放行**，只能由主人点按钮
     * @return "allow" / "deny"；超时或异常返回 null（调用方应转为 passthrough）
     */
    fun ask(
        request: HookApprovalRequest,
        anchor: () -> Rectangle?,
        timeoutMs: Int,
        risky: Boolean = false,
    ): String? =
        synchronized(lock) {
            val waitMs = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)
            val latch = CountDownLatch(1)
            val settled = AtomicBoolean(false)
            val answer = AtomicReference<String?>(null)
            val finish: (String?) -> Unit = { value ->
                if (settled.compareAndSet(false, true)) {
                    answer.set(value)
                    latch.countDown()
                }
            }
            try {
                SwingUtilities.invokeAndWait { present(request, anchor(), waitMs, risky, finish) }
            } catch (_: Exception) {
                return null
            }
            latch.await(waitMs + 1_000L, TimeUnit.MILLISECONDS)
            // 兜底：无论正常点击还是超时，都让气泡收起
            SwingUtilities.invokeLater { hide() }
            answer.get()
        }

    private fun present(
        request: HookApprovalRequest,
        anchor: Rectangle?,
        timeoutMs: Int,
        risky: Boolean,
        finish: (String?) -> Unit,
    ) {
        countdownTimer?.stop()
        riskyMode = risky
        panel.warning = risky

        chipLabel.text = if (risky) "CodeBuddy · 危险操作" else "CodeBuddy · 请求确认"
        titleLabel.text = escape(request.toolName.ifBlank { "未知工具" })
        titleLabel.foreground = if (risky) AccountUiColors.seal else AccountUiColors.textPrimary
        val full = request.detail.trim()
        val cut = full.length > DISPLAY_MAX_CHARS || request.truncated
        val detail = if (full.length > DISPLAY_MAX_CHARS) full.take(DISPLAY_MAX_CHARS) + "…" else full
        val note = if (cut) {
            "<br/><br/><i>（命令过长，仅显示前 $DISPLAY_MAX_CHARS 字，请确认后再决定）</i>"
        } else {
            ""
        }
        detailLabel.text = if (detail.isEmpty()) {
            "<html><body style='width:${JBUI.scale(WIDTH_DP)}px'><i>（无命令详情）</i></body></html>"
        } else {
            "<html><body style='width:${JBUI.scale(WIDTH_DP)}px'>" +
                escape(detail).replace("\n", "<br/>") + note + "</body></html>"
        }

        allowButton.text = if (risky) "同意执行" else "同意"
        denyButton.text = "取消"
        allowButton.isEnabled = true
        denyButton.isEnabled = true

        allowButton.actionListeners.forEach { allowButton.removeActionListener(it) }
        denyButton.actionListeners.forEach { denyButton.removeActionListener(it) }
        allowButton.addActionListener { finish("allow") }
        denyButton.addActionListener { finish("deny") }

        remainingMs = timeoutMs.toLong()
        updateHint()

        window.pack()
        val size = window.size
        val pet = anchor
        val screen = usableBounds(
            Point(
                pet?.let { it.x + it.width / 2 } ?: (size.width / 2),
                pet?.y ?: 0,
            )
        )
        val centerX = pet?.let { it.x + it.width / 2 } ?: (screen.x + screen.width / 2)
        var x = centerX - size.width / 2
        var y = (pet?.y ?: (screen.y + screen.height / 2)) - size.height - JBUI.scale(10)
        if (y < screen.y) y = screen.y + JBUI.scale(8)
        x = x.coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x))
        y = y.coerceIn(screen.y, (screen.y + screen.height - size.height).coerceAtLeast(screen.y))
        window.setLocation(x, y)
        window.isVisible = true
        window.toFront()

        countdownTimer = Timer(COUNTDOWN_TICK_MS) {
            remainingMs -= COUNTDOWN_TICK_MS
            if (remainingMs <= 0) {
                finish(null)
            } else {
                updateHint()
            }
        }.apply { start() }
    }

    private fun updateHint() {
        val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
        hintLabel.text = if (riskyMode) {
            "${seconds} 秒后交回 CodeBuddy 原生确认（不会自动执行）"
        } else {
            "${seconds} 秒后自动收起，交回 CodeBuddy 原生确认"
        }
    }

    private fun hide() {
        countdownTimer?.stop()
        countdownTimer = null
        if (!window.isVisible) return
        window.isVisible = false
    }

    fun dispose() {
        countdownTimer?.stop()
        countdownTimer = null
        window.isVisible = false
        window.dispose()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun usableBounds(around: Point): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val device = env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(around) }
            ?: env.defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    /** 圆角纸面 + 铅线 + 顶部朱印色带，与桌宠其它气泡保持同一套材质 */
    private class CardPanel : JPanel() {

        /** 危险操作：朱印色粗边 + 更矮更重的顶带，与普通确认一眼分开 */
        var warning: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    repaint()
                }
            }

        init {
            isOpaque = false
        }

        /** 正文按 [WIDTH_DP] 定宽折行，卡片再留左右内边距 */
        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            return Dimension(maxOf(base.width, JBUI.scale(WIDTH_DP + 34)), base.height)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toFloat()
            val h = height.toFloat()

            val shadow = AccountUiColors.cardShadow
            g2.color = Color(shadow.red, shadow.green, shadow.blue, 70)
            g2.fillRoundRect(1, 2, (w - 2).toInt(), (h - 3).toInt(), 8, 8)

            val card = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 2f, 8f, 8f)
            g2.color = AccountUiColors.cardBg
            g2.fill(card)
            g2.color = AccountUiColors.seal
            g2.fillRoundRect(0, 0, (w - 1f).toInt(), JBUI.scale(if (warning) 7 else 5), 8, 8)
            g2.color = if (warning) AccountUiColors.seal else AccountUiColors.rule
            g2.stroke = BasicStroke(if (warning) 2f else 1f)
            g2.draw(card)
            g2.dispose()
        }
    }

    private companion object {
        /** 正文宽度（dp）：命令摘要要能一眼看全，但不能占满屏幕 */
        const val WIDTH_DP = 380

        /** 气泡里最多展示的摘要字符数：再长就只截断显示，完整判定在桌宠侧按截断标志处理 */
        const val DISPLAY_MAX_CHARS = 240

        const val MIN_TIMEOUT_MS = 5_000

        const val COUNTDOWN_TICK_MS = 500
    }
}
