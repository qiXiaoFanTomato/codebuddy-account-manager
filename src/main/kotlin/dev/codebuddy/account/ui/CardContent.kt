package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 卡片内容排版工具箱。
 *
 * 内容区自上而下只有三层，每层职责唯一，不允许卡片各写一套：
 * 1. 主指标 [CardHeadline]：全卡唯一的大号数值 + 单位 + 右侧状态；
 * 2. 明细 [CardList]：行式列表（行由 [CardRow] 组成），按尺寸档位的容量裁剪，
 *    超出折成「另有 N 项」；
 * 3. 动作 [CardActionBar]：固定在卡片底边，左对齐、按宽度换行。
 *
 * 横向对齐规则：明细行右侧数值列宽固定，多行扫读时数字落在同一列；
 * 行内名称超宽由 JLabel 自动补省略号，全文进 tooltip。
 */
object CardMetrics {

    /** 明细行高（dp）：所有卡片一致，跨卡扫读时行距相同 */
    const val rowHeight = 20

    /** 明细行间距（dp） */
    const val rowGap = 4

    /** 区块间距（dp）：主指标 / 指标列 / 列表 / 动作之间 */
    const val blockGap = 8

    /** 明细行右侧数值列宽（dp） */
    const val valueWidth = 96

    /** 卡片外壳开销（dp）：标题栏 + 上下内边距 + 标题下的分隔线 */
    const val chromeDp = 76

    /** 动作条开销（dp）：一行按钮 + 与内容之间的间距 */
    const val actionBarDp = 36

    /** 主指标行开销（dp）：数值行 + 与列表之间的间距 */
    const val headlineDp = 32

    /**
     * 明细行容量：按统一档位高度倒推能放几行。
     *
     * 2×1 只留最关键的两行，2×4 才铺满整列；容量固定后，
     * 卡片高度不会被数据条数拉高拉低，整屏才能保持等高。
     */
    fun listRows(size: DashboardCardSize): Int {
        val budget = (size.heightDp - chromeDp - actionBarDp - headlineDp).coerceAtLeast(0)
        return (budget / (rowHeight + rowGap)).coerceIn(2, 16)
    }
}

/**
 * 主指标行：一个数值 + 单位 + 右侧状态。
 *
 * 数值走等宽字体（位数变化时不左右跳动），单位用 body 小字贴在同一基线上，
 * 右侧留给状态标签或更新时间。一张卡最多出现一处这种字号。
 */
class CardHeadline(
    value: String,
    unit: String? = null,
    trailing: JComponent? = null,
    tone: Color = AccountUiColors.brass,
    // 数值里可能是中文（「未开启」「查询失败」），按文本选字体
    valueFont: Font = AccountUiType.forText(value, AccountUiType.number, Font.BOLD),
) : JPanel(BorderLayout(JBUI.scale(CardMetrics.blockGap), 0)) {

    private val valueLabel = JLabel(value).apply {
        font = valueFont
        foreground = tone
    }

    private val unitLabel = JLabel(unit.orEmpty()).apply {
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = AccountUiColors.textMuted
        isVisible = !unit.isNullOrBlank()
        toolTipText = unit
    }

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        valueLabel.alignmentY = Component.CENTER_ALIGNMENT
        unitLabel.alignmentY = Component.BOTTOM_ALIGNMENT
        left.add(valueLabel)
        // 多余宽度全给胶水：单位紧跟数值，不会飘到行中间
        left.add(Box.createHorizontalStrut(JBUI.scale(AccountUiSpacing.xs)))
        left.add(unitLabel)
        left.add(Box.createHorizontalGlue())

        add(left, BorderLayout.CENTER)
        if (trailing != null) {
            trailing.alignmentY = Component.CENTER_ALIGNMENT
            add(trailing, BorderLayout.EAST)
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * 明细行：名称占满左侧剩余宽度，右侧依次是状态标签与固定列宽的等宽数值。
 *
 * 名称放不下时由 JLabel 自动补省略号（全文进 tooltip），
 * 右侧数值列宽固定，所以多行上下扫读时数字右边缘完全对齐。
 */
class CardRow(
    name: String,
    value: String = "",
    tag: JComponent? = null,
    valueTone: Color = AccountUiColors.textSecondary,
    tooltip: String? = null,
    valueWidthDp: Int = CardMetrics.valueWidth,
    clickable: Boolean = false,
) : JPanel(BorderLayout(JBUI.scale(CardMetrics.blockGap), 0)) {

    /** hover 进度：只有可点击的行才给反馈，避免纯展示行出现假可点暗示 */
    private val hover = JellyValue(this, 0.0)

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        preferredSize = Dimension(0, JBUI.scale(CardMetrics.rowHeight))
        toolTipText = tooltip ?: listOf(name, value).filter { it.isNotBlank() }.joinToString(" · ")

        // 名称直接放 CENTER：BorderLayout 会把剩余宽度全给它，
        // 放不下时 JLabel 自己补省略号，右侧状态与数值不会被挤出去
        val nameLabel = JLabel(name).apply {
            font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textPrimary
            minimumSize = Dimension(0, preferredSize.height)
        }

        // 右侧固定：状态标签 + 等宽数值，多行扫读时数值右边缘对齐
        val valueLabel = JLabel(value).apply {
            font = AccountUiType.forText(value, AccountUiType.label)
            foreground = valueTone
            horizontalAlignment = SwingConstants.RIGHT
            preferredSize = Dimension(JBUI.scale(valueWidthDp), preferredSize.height)
        }
        val right = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        if (tag != null) {
            tag.alignmentY = Component.CENTER_ALIGNMENT
            right.add(tag)
            right.add(Box.createHorizontalStrut(JBUI.scale(6)))
        }
        right.add(valueLabel)

        add(nameLabel, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        if (clickable) installHover(this)
    }

    /** hover 挂在行及其所有子组件上：鼠标停在文字上也算悬停 */
    private fun installHover(component: Component) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = hover.animateTo(1.0, 120)

            override fun mouseExited(e: MouseEvent) {
                val point = SwingUtilities.convertPoint(component, e.point, this@CardRow)
                if (!contains(point)) hover.animateTo(0.0, 160)
            }
        })
        if (component is Container) {
            for (child in component.components) installHover(child)
        }
    }

    override fun paintComponent(g: Graphics) {
        val value = hover.value.toFloat()
        if (value > 0.01f) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Jelly.alpha(AccountUiColors.cardHoverBg, value)
                g2.fill(
                    Jelly.round(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        JBUI.scale(AccountUiRadius.chip).toFloat(),
                    )
                )
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
        // 账本式行分隔：1px 虚线，对应 .dash-row 的 dashed border
        val line = g.create() as Graphics2D
        try {
            Jelly.paintDashedLine(line, 0f, height - 0.5f, width.toFloat(), AccountUiColors.rule)
        } finally {
            line.dispose()
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * 明细列表：按容量显示行，多出来的折成「另有 N 项」。
 *
 * 容量来自尺寸档位（[CardMetrics.listRows]），数据条数再多也不会把卡撑变形；
 * 列表为空时给一行空态文案，保持卡内排版不塌陷。
 */
class CardList(
    rows: List<JComponent?>,
    capacity: Int,
    emptyText: String = "暂无数据",
    overflowText: (Int) -> String = { "另有 $it 项" },
) : JPanel() {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val items = rows.filterNotNull()
        if (items.isEmpty()) {
            add(CardNotice(emptyText))
        } else {
            val shown = items.take(capacity.coerceAtLeast(0))
            for ((index, row) in shown.withIndex()) {
                if (index > 0) add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
                add(row)
            }
            val hidden = items.size - shown.size
            if (hidden > 0) {
                add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
                add(CardNotice(overflowText(hidden)))
            }
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * 底部动作条：固定在卡片底边，按钮按可用宽度换行。
 *
 * 一张卡最多一个 primary（主操作），其余用 secondary / ghost 递减视觉重量，
 * 避免一屏出现十几个同样抢眼的按钮。
 */
class CardActionBar : JPanel(FlowWrapLayout(JBUI.scale(6), JBUI.scale(CardMetrics.rowGap))) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        // 与内容区之间固定留一个区块间距：卡片被档位拉高时，动作始终贴底
        border = JBUI.Borders.emptyTop(CardMetrics.blockGap)
    }

    /** 追加一个动作：primary 为主操作，ghost 为低频动作 */
    fun addAction(
        text: String,
        tip: String? = null,
        primary: Boolean = false,
        ghost: Boolean = false,
        onClick: () -> Unit,
    ) {
        val variant = when {
            primary -> JellyButtonVariant.PRIMARY
            ghost -> JellyButtonVariant.GHOST
            else -> JellyButtonVariant.SECONDARY
        }
        add(JellyButton(text, variant, null, tip ?: text, onClick))
    }

    /** 是否已经加过动作（空动作条不占位） */
    fun isEmpty(): Boolean = componentCount == 0

    /** 没有动作时不占高度（连边框也不留） */
    override fun getPreferredSize(): Dimension =
        if (componentCount == 0) Dimension(0, 0) else super.getPreferredSize()

    override fun getMaximumSize(): Dimension =
        if (componentCount == 0) Dimension(0, 0) else Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** 弱化提示行：空态 / 加载中 / 溢出说明，统一 caption + faint */
class CardNotice(text: String, tone: Color = AccountUiColors.textFaint) : JLabel(text) {

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        foreground = tone
        toolTipText = text
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}
