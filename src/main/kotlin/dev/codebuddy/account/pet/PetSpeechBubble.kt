package dev.codebuddy.account.pet

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import dev.codebuddy.account.ui.AccountUiType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
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
 * - 底部进度条表示本句剩余展示时间，随倒计时收缩
 */
class PetSpeechBubble {

    private val window = JWindow()
    private val panel = BubblePanel()
    private val imageLabel = javax.swing.JLabel().apply {
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        isOpaque = false
    }
    private val chipLabel = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.micro, Font.BOLD)
        foreground = AccountUiColors.seal
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        isVisible = false
    }
    private val titleLabel = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.title, Font.BOLD)
        foreground = AccountUiColors.textPrimary
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
        isVisible = false
    }
    private val label = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.body)
        foreground = AccountUiColors.textPrimary
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
    }

    /** 进度条推进 + 到点收起共用一个 Timer，避免多个定时器各跑各的 */
    private var progressTimer: Timer? = null
    private var shownAt = 0L
    private var keepTotalMs = KEEP_MS.toLong()
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
        chipLabel.font = AccountUiType.ui(AccountUiType.micro, Font.BOLD)
        titleLabel.font = AccountUiType.ui(AccountUiType.title, Font.BOLD)
        label.font = AccountUiType.ui(AccountUiType.body)
        panel.accentBar = false
        panel.highlight = false
        panel.repaint()

        setImage(memeId)

        // HTML 定宽换行：长句会自动折行，窗口高度随内容变
        label.text = "<html><body style='width:${JBUI.scale(TEXT_WIDTH_DP)}px'>$text</body></html>"
        present(petBounds, keepMs)
    }

    /** 配图只在首次展示时解码；没有配图时把图片位让出来 */
    private fun setImage(memeId: String?) {
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
    }

    /**
     * 系统提示：标题（按等级着色）+ 正文。
     *
     * [highlight] 为 CodeBuddy 对话完成的强提醒：标题放大、加配图、
     * 朱印色粗边与顶部色带，比普通提示明显大一圈。
     * 正文超过 [NOTICE_MAX_CHARS] 会截断，完整内容仍在 IDE 日志与对应面板里。
     */
    fun showNotice(
        level: PetNotifier.Level,
        title: String,
        content: String,
        petBounds: Rectangle,
        keepMs: Int,
        highlight: Boolean = false,
    ) {
        val accent = accentOf(level)
        chipLabel.isVisible = true
        chipLabel.text = if (highlight) "CodeBuddy · 本轮完成" else chipOf(level)
        chipLabel.foreground = accent
        chipLabel.font = AccountUiType.ui(if (highlight) AccountUiType.label else AccountUiType.micro, Font.BOLD)
        panel.accent = accent
        panel.accentBar = true
        panel.highlight = highlight

        titleLabel.text = title
        titleLabel.isVisible = title.isNotBlank()
        titleLabel.foreground = if (level == PetNotifier.Level.ERROR) AccountUiColors.seal else AccountUiColors.textPrimary
        titleLabel.font = AccountUiType.ui(
            if (highlight) AccountUiType.scaled(19) else AccountUiType.title,
            Font.BOLD,
        )
        titleLabel.border = JBUI.Borders.emptyTop(if (highlight) 4 else 2)
        label.font = AccountUiType.ui(if (highlight) AccountUiType.scaled(15) else AccountUiType.body)
        setImage(if (highlight) ROUND_FINISH_MEME else null)

        val body = content.replace("\n", "<br/>")
            .let { if (it.length > NOTICE_MAX_CHARS) it.take(NOTICE_MAX_CHARS) + "…" else it }
        // 提示比碎碎念更宽、字号更大；强提醒再加宽一档
        val widthDp = if (highlight) HIGHLIGHT_WIDTH_DP else NOTICE_WIDTH_DP
        label.text = "<html><body style='width:${JBUI.scale(widthDp)}px'>$body</body></html>"
        present(petBounds, keepMs, highlight)
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

    private fun present(petBounds: Rectangle, keepMs: Int, highlight: Boolean = false) {
        keepTotalMs = keepMs.coerceAtLeast(1).toLong()
        shownAt = System.currentTimeMillis()
        panel.progress = 1.0
        window.pack()
        val size = window.size
        val screen = usableBounds(Point(petBounds.x + petBounds.width / 2, petBounds.y))
        val centerX = petBounds.x + petBounds.width / 2
        var x = centerX - size.width / 2
        var y = petBounds.y - size.height - JBUI.scale(6)
        if (y < screen.y) {
            y = if (highlight) {
                // 强提醒气泡带配图、个头大：宁可贴近屏幕上沿，也不要挪到角色下方盖住动画
                screen.y + JBUI.scale(8)
            } else {
                petBounds.y + petBounds.height + JBUI.scale(6)
            }
        }
        x = x.coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x))
        y = y.coerceIn(screen.y, (screen.y + screen.height - size.height).coerceAtLeast(screen.y))
        window.setLocation(x, y)
        window.isVisible = true
        visible = true

        progressTimer?.stop()
        progressTimer = Timer(PROGRESS_FRAME_MS) {
            val left = keepTotalMs - (System.currentTimeMillis() - shownAt)
            if (left <= 0) {
                hide()
            } else {
                panel.progress = left.toDouble() / keepTotalMs.toDouble()
            }
        }.apply { start() }
    }

    fun hide() {
        progressTimer?.stop()
        progressTimer = null
        if (!visible) return
        visible = false
        window.isVisible = false
    }

    fun dispose() {
        progressTimer?.stop()
        progressTimer = null
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

    /** 圆角纸面 + 投影 + 1px 铅线；提示左侧一条等级色竖条，底部一条倒计时进度条 */
    private class BubblePanel : javax.swing.JPanel() {

        /** 提示的等级色 */
        var accent: Color = AccountUiColors.seal

        /** 是否画左侧等级色竖条（提示模式） */
        var accentBar: Boolean = false

        /** 强提醒样式：粗边 + 顶部色带 + 更浓的投影 */
        var highlight: Boolean = false

        /** 剩余展示时间比例：1=刚出现，0=即将收起 */
        var progress: Double = 1.0
            set(value) {
                val next = value.coerceIn(0.0, 1.0)
                if (field != next) {
                    field = next
                    repaint()
                }
            }

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
            g2.color = Color(shadow.red, shadow.green, shadow.blue, if (highlight) 90 else 60)
            g2.fillRoundRect(1, 2, (w - 2).toInt(), (h - 3).toInt(), 8, 8)

            val card = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 2f, 8f, 8f)
            g2.color = AccountUiColors.cardBg
            g2.fill(card)
            if (highlight) {
                g2.color = accent
                g2.fillRoundRect(0, 0, (w - 1f).toInt(), JBUI.scale(6), 8, 8)
            }
            g2.color = if (highlight) accent else AccountUiColors.rule
            g2.stroke = BasicStroke(if (highlight) 2.5f else 1f)
            g2.draw(card)

            if (accentBar) {
                g2.color = accent
                g2.fillRoundRect(0, JBUI.scale(6), JBUI.scale(4), height - JBUI.scale(14), JBUI.scale(4), JBUI.scale(4))
            }

            // 进度条落在卡片下边距内：不再画左上角那截固定短条，避免被当成显示不全的进度条
            val barH = JBUI.scale(3)
            val left = JBUI.scale(16)
            val right = JBUI.scale(14)
            val trackW = (width - left - right).coerceAtLeast(0)
            val barY = (height - JBUI.scale(5) - barH).coerceAtLeast(0)
            g2.color = AccountUiColors.track
            g2.fillRoundRect(left, barY, trackW, barH, barH, barH)
            val fillW = (trackW * progress).toInt()
            if (fillW > 0) {
                g2.color = if (accentBar) accent else AccountUiColors.seal
                g2.fillRoundRect(left, barY, fillW.coerceAtLeast(barH), barH, barH, barH)
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

        /** 强提醒气泡正文宽度（dp） */
        const val HIGHLIGHT_WIDTH_DP = 320

        /** 对话完成气泡配图（原项目 memes） */
        const val ROUND_FINISH_MEME = "m26"

        /** 默认展示时长 */
        const val KEEP_MS = 7_000

        /** 进度条刷新间隔：只画收缩动画，不需要按帧率跑 */
        const val PROGRESS_FRAME_MS = 100
    }
}
