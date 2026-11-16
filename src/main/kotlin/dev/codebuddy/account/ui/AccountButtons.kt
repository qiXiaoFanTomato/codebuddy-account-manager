package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * 按钮：圆角矩形，悬停只提亮底色，不做位移与回弹。
 *
 * 三种语义：
 * - [JellyButtonVariant.PRIMARY] 主动作（主色实心 + onAccent 文字），一屏最多一个。
 * - [JellyButtonVariant.SECONDARY] 常规动作（卡片底 + 描边）。
 * - [JellyButtonVariant.GHOST] 低频动作（无底，hover 才出现浅色底）。
 */
enum class JellyButtonVariant { PRIMARY, SECONDARY, GHOST }

class JellyButton(
    text: String,
    private val variant: JellyButtonVariant = JellyButtonVariant.SECONDARY,
    private val icon: Icon? = null,
    tooltip: String? = null,
    private val onClick: () -> Unit,
) : JComponent() {

    /**
     * 放在深墨状态栏 / 标题栏上时用反色文字与半透明底。
     *
     * 做成属性而不是构造参数： trailing lambda 会绑定到最后一个参数，
     * 若在 `onClick` 之后追加参数，所有 `JellyButton(...) { ... }` 调用都会失配。
     */
    var onDark: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /**
     * GHOST 按钮的强调态：墨蓝文字 + 2px 粗下划线（对应 HTML `.acct-actions .primary`）。
     *
     * 只对 [JellyButtonVariant.GHOST] 生效，其它变体忽略；悬停仍转朱印。
     */
    var emphasized: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /** 危险动作（删除等）：悬停时文字与描边转朱印，对应 `.tw-btn--danger` */
    var danger: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    /** 按钮文案：改动后重排尺寸（例如查询中 → 刷新） */
    var text: String = text
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    private val hoverAnim = JellyValue(this, 0.0)
    private val pressAnim = JellyValue(this, 0.0)
    private var hovering = false
    private val padH = JBUI.scale(if (variant == JellyButtonVariant.GHOST) 8 else 12)
    private val heightDp = JBUI.scale(28)

    init {
        isOpaque = false
        font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.label)
        toolTipText = tooltip
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovering = true
                hoverAnim.animateTo(1.0, 160)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            override fun mouseExited(e: MouseEvent) {
                hovering = false
                hoverAnim.animateTo(0.0, 200)
                pressAnim.animateTo(0.0, 200, { t -> Easing.outBack(t) })
            }

            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                pressAnim.animateTo(1.0, 80)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!isEnabled) return
                pressAnim.animateTo(0.0, 300, { t -> Easing.outBack(t) })
                if (hovering && SwingUtilities2.contains(this@JellyButton, e.point)) fire()
            }
        })

        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })

        // 键盘：Space / Enter 都能触发，Esc 不吞（交给 IDE）
        inputMap.put(KeyStroke.getKeyStroke("SPACE"), "activate")
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "activate")
        actionMap.put(
            "activate",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (isEnabled) fire()
                }
            }
        )
    }

    /** 禁用态需要重绘：JComponent 自身不会因为 enabled 变化刷新外观 */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }

    private fun fire() {
        // 按下反馈：先压缩再触发，避免动作里快速重建组件导致动画卡在中途
        pressAnim.animateTo(0.0, 220, { t -> Easing.outBack(t) })
        onClick()
    }

    private fun contentWidth(): Int {
        val fm = getFontMetrics(font)
        return fm.stringWidth(text) + (if (icon != null) icon.iconWidth + JBUI.scale(5) else 0)
    }

    override fun getPreferredSize(): Dimension =
        Dimension(contentWidth() + padH * 2 + JBUI.scale(2), heightDp)

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val hover = hoverAnim.value.toFloat()
            val press = pressAnim.value.toFloat()
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 2f || h <= 2f) return

            val alpha = if (isEnabled) 1f else 0.45f
            val shape = Jelly.round(0.5f, 0.5f, w - 1f, h - 1f, JBUI.scale(AccountUiRadius.inner).toFloat())

            when (variant) {
                JellyButtonVariant.PRIMARY -> {
                    val base = when {
                        onDark -> AccountUiColors.onInk
                        press > 0.35f -> AccountUiColors.accentDeep
                        else -> Jelly.lighten(AccountUiColors.accentFill, hover * 0.05f)
                    }
                    Jelly.paintShadow(g2, shape, (0.3f + hover * 0.2f) * alpha)
                    Jelly.paintBody(g2, shape, base, JellyMaterial.FLAT)
                    Jelly.paintEdge(
                        g2, shape,
                        if (onDark) Jelly.alpha(AccountUiColors.ink, 0.3f * alpha)
                        else Jelly.alpha(Jelly.darken(AccountUiColors.accentFill, 0.18f), 0.6f * alpha),
                        1f,
                    )
                }

                JellyButtonVariant.SECONDARY -> {
                    val base = if (onDark) {
                        Jelly.alpha(AccountUiColors.onInk, (0.08f + hover * 0.08f) * alpha)
                    } else if (hover > 0.02f) {
                        AccountUiColors.cardHoverBg
                    } else {
                        AccountUiColors.cardBg
                    }
                    Jelly.paintBody(g2, shape, base, JellyMaterial.FLAT)
                    Jelly.paintEdge(
                        g2, shape,
                        Jelly.alpha(
                            when {
                                onDark -> AccountUiColors.heroDivider
                                danger && hover > 0.3f -> AccountUiColors.seal
                                hover > 0.3f -> AccountUiColors.borderActive
                                else -> AccountUiColors.border
                            },
                            alpha,
                        ),
                        1f,
                    )
                }

                JellyButtonVariant.GHOST -> {
                    // 档案柜的 quiet 按钮：无底、无框，只有一条 1px 下划线
                }
            }

            val fg = when (variant) {
                JellyButtonVariant.PRIMARY ->
                    if (onDark) AccountUiColors.ink else AccountUiColors.onAccentFill
                JellyButtonVariant.GHOST ->
                    if (hover > 0.4f) AccountUiColors.seal
                    else if (onDark) if (emphasized) AccountUiColors.onInk else AccountUiColors.onInkDim
                    else if (emphasized) AccountUiColors.textPrimary
                    else AccountUiColors.textSecondary
                JellyButtonVariant.SECONDARY ->
                    if (onDark) AccountUiColors.onInk
                    else if (danger && hover > 0.3f) AccountUiColors.seal
                    else AccountUiColors.textPrimary
            }
            g2.font = font
            // 禁用态不再压低文字 alpha：半透明前景会让字形边缘变淡发虚，
            // 改成与按钮表面做不透明混色，保持对比度与锐度
            val disabledSurface = when (variant) {
                JellyButtonVariant.PRIMARY -> AccountUiColors.accentFill
                else -> if (onDark) AccountUiColors.ink else AccountUiColors.cardBg
            }
            g2.color = if (isEnabled) fg else Jelly.mix(disabledSurface, fg, 0.55f)

            val fm = g2.fontMetrics
            val iconW = if (icon != null) icon.iconWidth + JBUI.scale(5) else 0
            val textW = fm.stringWidth(text)
            val totalW = iconW + textW
            var x = (width - totalW) / 2
            if (icon != null) {
                icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
                x += iconW
            }
            g2.drawString(text, x, Jelly.textBaseline(height, fm))

            if (variant == JellyButtonVariant.GHOST) {
                // 下划线的 text-underline-offset 约 4px；强调态加粗到 2px 并改用墨蓝
                g2.color = Jelly.alpha(
                    when {
                        hover > 0.3f -> AccountUiColors.seal
                        onDark -> AccountUiColors.onInkDim
                        emphasized -> AccountUiColors.textPrimary
                        else -> AccountUiColors.textMuted
                    },
                    alpha,
                )
                val thickness = if (emphasized) JBUI.scale(2).toFloat() else 1f
                g2.fill(Rectangle2D.Float(x.toFloat(), h - JBUI.scale(4).toFloat(), textW.toFloat(), thickness))
            }

            Jelly.paintFocusRing(g2, shape, isFocusOwner)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 文字按钮：低饱和文字 + hover 时的浅色底（radius 6）。
 *
 * 保留整块点击区（不只文字本身），窄工具窗里也点得准；
 * 视觉重量低于 [JellyButton]，用于卡片标题行的低频动作。
 */
class ActionPill(
    private val text: String,
    private val onClick: () -> Unit,
) : JComponent() {

    private val hoverAnim = JellyValue(this, 0.0)
    private var hovering = false

    init {
        isOpaque = false
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovering = true
                hoverAnim.animateTo(1.0, 160)
            }

            override fun mouseExited(e: MouseEvent) {
                hovering = false
                hoverAnim.animateTo(0.0, 200)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (hovering) onClick()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        inputMap.put(KeyStroke.getKeyStroke("SPACE"), "activate")
        actionMap.put(
            "activate",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = onClick()
            }
        )
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(16), fm.height + JBUI.scale(8))
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val hover = hoverAnim.value.toFloat()
            val w = width.toFloat()
            val h = height.toFloat()
            val shape = Jelly.round(0.5f, 0.5f, w - 1f, h - 1f, JBUI.scale(AccountUiRadius.inner).toFloat())

            g2.font = font
            val fm = g2.fontMetrics
            val tx = Jelly.textX(width, fm.stringWidth(text))
            // 档案柜的文字动作：下划线，hover 转朱印，不铺色块
            g2.color = if (hover > 0.3f) AccountUiColors.seal else AccountUiColors.textSecondary
            g2.drawString(text, tx, Jelly.textBaseline(height, fm))
            g2.fill(Rectangle2D.Float(tx.toFloat(), h - JBUI.scale(4).toFloat(), fm.stringWidth(text).toFloat(), 1f))

            Jelly.paintFocusRing(g2, shape, isFocusOwner)
        } finally {
            g2.dispose()
        }
    }
}

/** 弹窗顶部汇总条：标题 + 副标题 + 右侧操作 */
class DialogTopBar(
    title: String,
    subtitle: String? = null,
    actions: JComponent? = null,
) : JPanel(BorderLayout()) {

    private val titleLabel = JLabel(title)
    private val subtitleLabel = JLabel(subtitle ?: "")

    init {
        // 账本式顶栏：抽屉面纸 + 2px 墨蓝底线（不铺深墨，避免外部传入的控件文字读不清）
        background = AccountUiColors.surfaceBg
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.ink, 0, 0, 2, 0),
            JBUI.Borders.empty(AccountUiSpacing.md, AccountUiSpacing.cardPadH)
        )

        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        titleLabel.font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.title)
        titleLabel.foreground = AccountUiColors.ink

        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        subtitleLabel.font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        subtitleLabel.foreground = AccountUiColors.textSecondary
        subtitleLabel.border = JBUI.Borders.emptyTop(AccountUiSpacing.xxs)
        subtitleLabel.isVisible = !subtitle.isNullOrBlank()

        val textCol = FullWidthColumn()
        textCol.add(titleLabel)
        textCol.add(subtitleLabel)

        add(textCol, BorderLayout.CENTER)
        if (actions != null) {
            actions.alignmentY = Component.CENTER_ALIGNMENT
            add(actions, BorderLayout.EAST)
        }
    }

    /** 数据刷新后更新标题与副标题 */
    fun update(title: String, subtitle: String? = null) {
        titleLabel.text = title
        subtitleLabel.text = subtitle ?: ""
        subtitleLabel.isVisible = !subtitle.isNullOrBlank()
    }
}


/**
 * 主导航标签：底部 3px 朱印指示线（underline tabs）。
 *
 * [onDark] 为 true 时用于深墨标题栏（浅色文字 + 半透明底线），
 * 对应 plugin-ui.html 的 `.titlebar .tab`；false 用于普通抽屉工具栏。
 * 方向键可切换，获得焦点时套黄铜焦点环。
 */
class SegmentedTabs(
    private val items: List<String>,
    selectedIndex: Int = 0,
    private val onSelect: (Int) -> Unit,
) : JComponent() {

    /**
     * 深墨标题栏模式：浅色文字 + 半透明底线。
     *
     * 做成属性而不是构造参数：trailing lambda 会绑定到最后一个参数，
     * 在 `onSelect` 之后追加参数会让 `SegmentedTabs(...) { ... }` 调用失配。
     */
    var onDark: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    private var selected = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    private val padH = JBUI.scale(10)
    private val itemGap = JBUI.scale(2)

    /** 当前悬停项，-1 表示无 */
    private var hoverIndex = -1

    init {
        isOpaque = false
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.body)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = indexAt(e.x)
                if (index >= 0 && index != selected) {
                    setSelectedIndex(index)
                    onSelect(index)
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = indexAt(e.x)
                if (index != hoverIndex) {
                    hoverIndex = index
                    repaint()
                }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hoverIndex = -1
                repaint()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })

        // 方向键切换：键盘用户不必先 Tab 到目标项
        inputMap.put(KeyStroke.getKeyStroke("LEFT"), "prev")
        inputMap.put(KeyStroke.getKeyStroke("RIGHT"), "next")
        inputMap.put(KeyStroke.getKeyStroke("HOME"), "first")
        inputMap.put(KeyStroke.getKeyStroke("END"), "last")
        actionMap.put(
            "prev",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = selectBy(-1)
            }
        )
        actionMap.put(
            "next",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = selectBy(1)
            }
        )
        actionMap.put(
            "first",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = selectBy(-items.size)
            }
        )
        actionMap.put(
            "last",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = selectBy(items.size)
            }
        )
    }

    private fun selectBy(delta: Int) {
        val target = (selected + delta).coerceIn(0, items.size - 1)
        if (target == selected) return
        setSelectedIndex(target)
        onSelect(target)
    }

    fun setSelectedIndex(index: Int) {
        if (index == selected || index !in items.indices) return
        selected = index
        repaint()
    }

    /** 当前选中项下标 */
    fun selectedIndex(): Int = selected

    private fun widths(): IntArray {
        val fm = getFontMetrics(font)
        return items.map { fm.stringWidth(it) + padH * 2 }.toIntArray()
    }

    override fun getPreferredSize(): Dimension {
        val w = widths().sum() + itemGap * (items.size - 1).coerceAtLeast(0)
        val h = getFontMetrics(font).height + JBUI.scale(if (onDark) 18 else 12)
        return Dimension(w, h)
    }

    private fun indexAt(x: Int): Int {
        var cursorX = 0
        widths().forEachIndexed { index, w ->
            if (x in cursorX until cursorX + w) return index
            cursorX += w + itemGap
        }
        return -1
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)

            val h = height.toFloat()
            if (h <= 0f) return
            val widths = widths()

            // 底部基线：整条 1px 分隔线，朱印指示线压在它上面
            g2.color = if (onDark) AccountUiColors.heroDivider else AccountUiColors.divider
            g2.fill(Rectangle2D.Float(0f, h - 1f, width.toFloat(), 1f))

            val plain = getFontMetrics(font)
            val bold = getFontMetrics(font.deriveFont(Font.BOLD))
            var x = 0
            widths.forEachIndexed { index, w ->
                val active = index == selected
                val fm = if (active) bold else plain
                g2.font = fm.font
                g2.color = when {
                    active -> if (onDark) AccountUiColors.onInk else AccountUiColors.textPrimary
                    index == hoverIndex -> if (onDark) AccountUiColors.onInk else AccountUiColors.textSecondary
                    else -> if (onDark) AccountUiColors.onInkDim else AccountUiColors.textMuted
                }
                val tx = x + (w - fm.stringWidth(items[index])) / 2
                val ty = (height - JBUI.scale(3) - fm.height) / 2 + fm.ascent
                g2.drawString(items[index], tx, ty)

                if (active) {
                    // 3px 直角朱印指示线：与 HTML 的 .tab[aria-selected] 一致
                    val lineW = (fm.stringWidth(items[index]) + JBUI.scale(10)).toFloat()
                    val lineX = x + (w - lineW) / 2f
                    g2.color = AccountUiColors.seal
                    g2.fill(
                        Rectangle2D.Float(
                            lineX, h - JBUI.scale(3).toFloat(),
                            lineW, JBUI.scale(3).toFloat(),
                        )
                    )
                }
                x += w + itemGap
            }

            Jelly.paintFocusRing(
                g2,
                Jelly.round(0.5f, 0.5f, width - 1f, height - 1f, JBUI.scale(AccountUiRadius.inner).toFloat()),
                isFocusOwner,
            )
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 色调切换图标：三个重叠色点，像调色盘上的三格色卡。
 *
 * 不依赖 IDE 内置图标（各版本可用性不一致），自绘一个稳定的调色盘符号。
 *
 * @param accent 主色；null 表示跟随当前色调
 * @param sheen 装饰色；null 表示跟随当前色调
 */
class ThemeSwatchIcon(
    private val accent: Color? = null,
    private val sheen: Color? = null,
) : Icon {

    private val size: Int = JBUI.scale(16)

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
        val g2 = (g as? Graphics2D)?.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val main = accent ?: AccountUiColors.accent
            // 第二个色点用填充色：亮色调下 heroSheen 太浅，色卡会看不见
            val deco = sheen ?: AccountUiColors.accentFill
            val s = size.toFloat()
            val r = s * 0.42f
            // 主色圆
            g2.color = main
            g2.fill(java.awt.geom.Ellipse2D.Float(0f, 0f, r * 2, r * 2))
            // 装饰色圆（右上）
            g2.color = deco
            g2.fill(java.awt.geom.Ellipse2D.Float(s - r * 1.7f, 0f, r * 1.7f, r * 1.7f))
            // 描边圆（右下）：把三个色块分层
            g2.color = AccountUiColors.textPrimary
            g2.stroke = BasicStroke(1.2f)
            g2.draw(java.awt.geom.Ellipse2D.Float(s - r * 1.5f, s - r * 1.5f, r * 1.5f, r * 1.5f))
        } finally {
            g2.dispose()
        }
    }
}

