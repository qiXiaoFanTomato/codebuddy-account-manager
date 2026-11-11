package dev.codebuddy.account.pet

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import dev.codebuddy.account.ui.AccountUiType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JWindow
import javax.swing.Timer

/**
 * 碎碎念气泡：只在桌宠上方冒一句台词，没有任何按钮。
 *
 * 与 [PetBubble]（额度气泡）分开：
 * - 额度气泡要展示数字与按钮，由右键菜单触发
 * - 这个只负责说话，定时触发、自动消失、不抢焦点
 */
class PetSpeechBubble {

    private val window = JWindow()
    private val panel = BubblePanel()
    private val imageLabel = javax.swing.JLabel().apply {
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        isOpaque = false
    }
    private val chipLabel = JBLabel().apply {
        font = font.deriveFont(java.awt.Font.BOLD, AccountUiType.micro)
        foreground = AccountUiColors.seal
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        isVisible = false
    }
    private val titleLabel = JBLabel().apply {
        font = font.deriveFont(java.awt.Font.BOLD, AccountUiType.title)
        foreground = AccountUiColors.textPrimary
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        isVisible = false
    }
    private val label = JBLabel().apply {
        font = font.deriveFont(AccountUiType.body)
        foreground = AccountUiColors.textPrimary
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
    }

    private var hideTimer: Timer? = null
    private var visible = false

    init {
        window.isAlwaysOnTop = true
        window.setFocusableWindowState(false)
        window.background = if (PetPainter.supportsTranslucency()) Color(0, 0, 0, 0) else AccountUiColors.panelBg

        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(10, 16, 10, 14)
        panel.isOpaque = false
        panel.add(chipLabel)
        panel.add(titleLabel)
        panel.add(imageLabel)
        panel.add(label)
        window.contentPane = panel
    }

    val isShowing: Boolean get() = visible

    /** 在桌宠上方展示一句话（可带表情包配图），[keepMs] 后自动消失 */
    fun show(text: String, petBounds: Rectangle, memeId: String? = null, keepMs: Int = KEEP_MS) {
        if (text.isBlank()) return

        chipLabel.isVisible = false
        titleLabel.isVisible = false
        titleLabel.text = ""
        panel.accentBar = false
        panel.repaint()

        val image = memeId?.let { PetMeme.image(it) }
        if (image != null) {
            val targetW = JBUI.scale(IMAGE_WIDTH_DP)
            val targetH = (image.height.toDouble() * targetW / image.width).toInt().coerceAtLeast(1)
            imageLabel.icon = javax.swing.ImageIcon(
                image.getScaledInstance(targetW, targetH, java.awt.Image.SCALE_SMOOTH)
            )
            imageLabel.isVisible = true
        } else {
            imageLabel.icon = null
            imageLabel.isVisible = false
        }

        // HTML 定宽换行：长句会自动折行，窗口高度随内容变
        label.text = "<html><body style='width:${JBUI.scale(TEXT_WIDTH_DP)}px'>$text</body></html>"
        present(petBounds, keepMs)
    }

    /**
     * 系统提示：标题（按等级着色）+ 正文，无配图。
     *
     * 正文超过 [NOTICE_MAX_CHARS] 会截断，完整内容仍在 IDE 日志与对应面板里。
     */
    fun showNotice(level: PetNotifier.Level, title: String, content: String, petBounds: Rectangle, keepMs: Int) {
        imageLabel.icon = null
        imageLabel.isVisible = false

        val accent = accentOf(level)
        chipLabel.isVisible = true
        chipLabel.text = chipOf(level)
        chipLabel.foreground = accent
        panel.accent = accent
        panel.accentBar = true

        titleLabel.text = title
        titleLabel.isVisible = title.isNotBlank()
        titleLabel.foreground = if (level == PetNotifier.Level.ERROR) AccountUiColors.seal else AccountUiColors.textPrimary
        titleLabel.border = JBUI.Borders.emptyTop(2)

        val body = content.replace("\n", "<br/>")
            .let { if (it.length > NOTICE_MAX_CHARS) it.take(NOTICE_MAX_CHARS) + "…" else it }
        // 提示比碎碎念更宽、字号更大，保证一眼能看清
        label.text = "<html><body style='width:${JBUI.scale(NOTICE_WIDTH_DP)}px'>$body</body></html>"
        present(petBounds, keepMs)
    }

    private fun accentOf(level: PetNotifier.Level): Color = when (level) {
        PetNotifier.Level.SUCCESS -> AccountUiColors.brassInk
        PetNotifier.Level.WARN -> AccountUiColors.brass
        PetNotifier.Level.ERROR -> AccountUiColors.seal
        PetNotifier.Level.INFO -> AccountUiColors.ink
    }

    private fun chipOf(level: PetNotifier.Level): String = when (level) {
        PetNotifier.Level.SUCCESS -> "操作成功"
        PetNotifier.Level.WARN -> "注意"
        PetNotifier.Level.ERROR -> "出错了"
        PetNotifier.Level.INFO -> "提示"
    }

    private fun present(petBounds: Rectangle, keepMs: Int) {
        window.pack()
        val size = window.size
        val screen = usableBounds(Point(petBounds.x + petBounds.width / 2, petBounds.y))
        val centerX = petBounds.x + petBounds.width / 2
        var x = centerX - size.width / 2
        var y = petBounds.y - size.height - JBUI.scale(6)
        if (y < screen.y) y = petBounds.y + petBounds.height + JBUI.scale(6)
        x = x.coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x))
        y = y.coerceIn(screen.y, (screen.y + screen.height - size.height).coerceAtLeast(screen.y))
        window.setLocation(x, y)
        window.isVisible = true
        visible = true

        hideTimer?.stop()
        hideTimer = Timer(keepMs) { hide() }.apply {
            isRepeats = false
            start()
        }
    }

    fun hide() {
        hideTimer?.stop()
        hideTimer = null
        if (!visible) return
        visible = false
        window.isVisible = false
    }

    fun dispose() {
        hideTimer?.stop()
        hideTimer = null
        visible = false
        window.isVisible = false
        window.dispose()
    }

    private fun usableBounds(around: Point): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val device = env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(around) }
            ?: env.defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    /** 圆角纸面 + 投影 + 1px 铅线；碎碎念顶部一道短朱印条，提示左侧一条等级色竖条 */
    private class BubblePanel : javax.swing.JPanel() {

        /** 提示的等级色 */
        var accent: Color = AccountUiColors.seal

        /** 是否画左侧等级色竖条（提示模式） */
        var accentBar: Boolean = false

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toFloat()
            val h = height.toFloat()

            // 投影：让气泡从桌面上浮起来，比 1px 描边更抓眼
            val shadow = AccountUiColors.cardShadow
            g2.color = Color(shadow.red, shadow.green, shadow.blue, 60)
            g2.fillRoundRect(1, 2, (w - 2).toInt(), (h - 3).toInt(), 8, 8)

            val card = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 2f, 8f, 8f)
            g2.color = AccountUiColors.cardBg
            g2.fill(card)
            g2.color = AccountUiColors.rule
            g2.stroke = BasicStroke(1f)
            g2.draw(card)

            if (accentBar) {
                g2.color = accent
                g2.fillRoundRect(0, JBUI.scale(6), JBUI.scale(4), height - JBUI.scale(14), JBUI.scale(4), JBUI.scale(4))
            } else {
                g2.color = AccountUiColors.seal
                g2.fillRoundRect(0, 0, JBUI.scale(22), JBUI.scale(3), 3, 3)
            }
            g2.dispose()
        }
    }

    private companion object {
        /** 气泡文字宽度（dp） */
        const val TEXT_WIDTH_DP = 220

        /** 配图展示宽度（dp） */
        const val IMAGE_WIDTH_DP = 150

        /** 提示正文在气泡里的最大字符数（超出截断） */
        const val NOTICE_MAX_CHARS = 200

        /** 提示气泡正文宽度（dp），比碎碎念更宽 */
        const val NOTICE_WIDTH_DP = 280

        /** 默认展示时长 */
        const val KEEP_MS = 7_000
    }
}
