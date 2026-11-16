package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 1px 横向分隔线：宽度撑满，颜色弱于边框 */
class HairLine(private val color: Color = AccountUiColors.divider) : JComponent() {

    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(1))
        minimumSize = preferredSize
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = color
            g2.fill(Rectangle2D.Float(0f, 0f, width.toFloat(), 1f))
        } finally {
            g2.dispose()
        }
    }
}

/** 字段行：标签左、值右，行高与内边距统一 */
class FieldLine(
    label: String,
    value: JComponent,
    labelWidth: Int? = null,
) : JPanel(BorderLayout(JBUI.scale(AccountUiSpacing.sm), 0)) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(AccountUiSpacing.xxs, 0)

        val key = JLabel(label).apply {
            // 字段名是中文，走界面字体；等宽字体没有中文字形
            font = AccountUiType.ui(AccountUiType.label)
            foreground = AccountUiColors.textMuted
            toolTipText = label
        }
        if (labelWidth != null) {
            key.preferredSize = Dimension(JBUI.scale(labelWidth), key.preferredSize.height)
        }
        value.alignmentY = Component.CENTER_ALIGNMENT
        add(key, BorderLayout.WEST)
        add(value, BorderLayout.EAST)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * 状态标签：6px 圆点 + 文案。
 *
 * 比实底胶囊轻，一屏十几个状态也不会变成一片色块；圆点保证色弱用户
 * 也能通过位置与文案区分「有状态」这件事。
 */
class StatusTag(text: String, private val tone: Color) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        alignmentY = Component.CENTER_ALIGNMENT
        add(JLabel("●").apply {
            font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.micro)
            foreground = tone
        })
        add(JLabel(text).apply {
            font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = tone
        })
    }

    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, preferredSize.height)
}

/** 指标单元：标签在上、数值在下，左对齐 */
class MetricCell(label: String, value: String, tone: Color) : JPanel() {

    private val labelComp = JLabel(label).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        toolTipText = label
    }

    private val valueComp = JLabel(value).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = AccountUiType.forText(value, AccountUiType.body, Font.BOLD)
        foreground = tone
    }

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        add(labelComp)
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xxs)))
        add(valueComp)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** 分区标题：标题（+ 副标题）+ 右侧延伸细线 + 可选操作 */
class SectionHeader(
    title: String,
    subtitle: String? = null,
    actions: JComponent? = null,
) : JPanel(BorderLayout(JBUI.scale(AccountUiSpacing.sm), 0)) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        alignmentY = Component.CENTER_ALIGNMENT

        val textCol = FullWidthColumn()
        textCol.add(
            JLabel(title).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD, AccountUiType.title)
                foreground = AccountUiColors.textPrimary
            }
        )
        if (!subtitle.isNullOrBlank()) {
            textCol.add(
                JLabel(subtitle).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = font.deriveFont(Font.PLAIN, AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                }
            )
        }

        // 标题右侧的 1px 细线：把标题与操作区分开，也顺势补齐视觉基线
        val rule = HairLine().apply {
            border = JBUI.Borders.emptyTop(JBUI.scale(7))
        }
        val middle = JPanel(BorderLayout(JBUI.scale(AccountUiSpacing.sm), 0)).apply { isOpaque = false }
        middle.add(rule, BorderLayout.CENTER)

        add(textCol, BorderLayout.WEST)
        add(middle, BorderLayout.CENTER)
        if (actions != null) {
            actions.alignmentY = Component.CENTER_ALIGNMENT
            add(actions, BorderLayout.EAST)
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** 辅助说明文字 */
class UiHint(text: String) : JLabel(text) {
    init {
        alignmentX = Component.LEFT_ALIGNMENT
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        foreground = AccountUiColors.textFaint
    }
}

