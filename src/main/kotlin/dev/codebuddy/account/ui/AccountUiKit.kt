package dev.codebuddy.account.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.GraphicsConfiguration
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.accessibility.AccessibleContext
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * 公共界面组件库：分区标题 / 指标块 / Hero 指标条 / 按钮 / 表格样式 / 状态文案。
 *
 * 目的是把此前散落在各页面里的重复实现收敛到一处，
 * 保证全插件的标题、指标、按钮、表格、状态五类元素只有一套画法。
 */
object AccountUiKit {

    /** 统一分区标题：标题（+ 副标题）+ 右侧延伸细线 + 操作区 */
    fun sectionHeader(title: String, subtitle: String? = null, actions: JComponent? = null): JComponent =
        SectionHeader(title, subtitle, actions)

    /**
     * 字段行：标签左对齐、值右对齐。
     *
     * 卡片里所有「标签 → 值」都走这里，左边缘与右边缘因此在整屏范围内对齐，
     * 不会因为标签字数不同而参差。
     */
    fun fieldLine(label: String, value: JComponent, labelWidth: Int? = null): JComponent =
        FieldLine(label, value, labelWidth)

    /** 右对齐的数值文本：等宽数字，刷新时不左右跳动 */
    fun valueText(
        text: String,
        tone: Color = AccountUiColors.textSecondary,
        mono: Boolean = true,
    ): JLabel = JLabel(text).apply {
        // 数值里可能混中文（「剩余 20%」），按文本选字体，避免等宽字渲染成方框
        font = if (mono) AccountUiType.forText(text, AccountUiType.label, Font.PLAIN)
        else baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = tone
        horizontalAlignment = SwingConstants.RIGHT
    }

    /** 状态标签：圆点 + 文案（颜色与文字双通道，不只靠颜色传达状态） */
    fun statusTag(text: String, tone: Color): JComponent = StatusTag(text, tone)

    /** 统一辅助说明文字 */
    fun hint(text: String): JLabel = UiHint(text)

    /** 统一指标块：主数值默认走黄铜读数 */
    fun kpi(label: String, value: String, hint: String? = null, tone: Color = AccountUiColors.brass): JComponent =
        KpiTile(label, value, hint, tone)

    /** 统一 Hero 指标条 */
    fun hero(
        title: String,
        value: String,
        unit: String? = null,
        metrics: List<Pair<String, String>> = emptyList(),
    ): JComponent = HeroPanel(title, value, unit, metrics)

    /** 统一按钮 */
    fun button(
        text: String,
        variant: JellyButtonVariant = JellyButtonVariant.SECONDARY,
        icon: Icon? = null,
        tip: String? = null,
        onClick: () -> Unit,
    ): JComponent = JellyButton(text, variant, icon, tip, onClick)

    /** 分区标题右侧的小动作（替代 ActionLink） */
    fun action(text: String, onClick: () -> Unit): JComponent = ActionPill(text, onClick)

    /**
     * 字段标签：语义色弱底 + 同色文字，圆角 4（不再一律胶囊）。
     *
     * 只给「计数 / 数值」这类需要一眼扫到的短字段用；
     * 行内状态优先用 [statusTag]（圆点 + 文案），视觉更轻。
     */
    fun fieldPill(text: String, tone: Color): JComponent =
        Chip(text, Jelly.mix(AccountUiColors.cardBg, tone, 0.10f), tone, AccountUiRadius.chip)

    /** 行内字段排布：水平排列并垂直居中（FlowLayout 会让胶囊顶到行首，文字视觉偏移） */
    fun fieldRow(vararg comps: JComponent): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEachIndexed { index, comp ->
            if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(6)))
            comp.alignmentY = Component.CENTER_ALIGNMENT
            add(comp)
        }
    }

    /** 统一表格样式 */
    fun styleTable(table: JBTable, widths: IntArray) = AccountTableStyle.apply(table, widths)

    /**
     * 统一下拉框样式：浅色档案纸底 + 墨蓝文字。
     *
     * 界面固定浅色，IDE 深色主题下 LAF 的下拉框会变成深底浅字，
     * 这里连渲染器一起换掉，弹出列表也保持浅色。
     */
    fun styleCombo(combo: JComboBox<*>, label: ((Any?) -> String)? = null): JComboBox<*> = combo.apply {
        background = AccountUiColors.cardBg
        foreground = AccountUiColors.textPrimary
        font = AccountUiType.ui(AccountUiType.label)
        isOpaque = true
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = label?.invoke(value) ?: value?.toString().orEmpty()
                background = if (isSelected) AccountUiColors.surfaceBg else AccountUiColors.cardBg
                foreground = AccountUiColors.textPrimary
                font = AccountUiType.ui(AccountUiType.label)
                border = JBUI.Borders.empty(2, 6)
                return this
            }
        }
    }

    /**
     * 滚动区浅色化。
     *
     * IDE 深色主题下 viewport 默认铺深灰，界面又固定用浅色档案纸，
     * 不显式改回来的话滚动区会变成「深底 + 深字」，内容整块读不出来。
     */
    fun styleScroll(scroll: JBScrollPane, bg: Color = AccountUiColors.panelBg): JBScrollPane = scroll.apply {
        border = JBUI.Borders.empty()
        background = bg
        isOpaque = true
        viewport.background = bg
        viewport.isOpaque = true
    }

    /** 主导航分段控件 */
    fun segmented(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): JComponent =
        SegmentedTabs(items, selectedIndex, onSelect)

    /** 额度状态文案与语义色（此前在两处各写一份） */
    fun quotaStatus(summary: QuotaSummary?): QuotaStatus.State = QuotaStatus.of(summary)

    /** 数字格式化：千分位 + 最多两位小数（此前在三处各写一份） */
    fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong()) else String.format("%,.2f", v)
}

/** 账号下拉项（此前三个弹窗各写一份） */
data class AccountChoice(
    val key: String?,
    val label: String,
) {
    override fun toString(): String = label
}

/** 额度状态：文案 + 语义色 */
object QuotaStatus {

    data class State(val text: String, val color: Color)

    fun of(summary: QuotaSummary?): State = when {
        summary == null -> State("未刷新", AccountUiColors.textFaint)
        summary.error != null -> State("查询失败", AccountUiColors.danger)
        summary.packages.isEmpty() -> State("无套餐", AccountUiColors.textMuted)
        summary.usableRemain <= 0 -> State("已用尽", AccountUiColors.danger)
        summary.usableRemainPercent <= 10 -> State("紧张", AccountUiColors.danger)
        summary.usableRemainPercent <= 30 -> State("偏低", AccountUiColors.warning)
        else -> State("正常", AccountUiColors.success)
    }
}

/** 账号关键字匹配（此前在列表与网格里各写一份） */
fun CredentialSnapshot.matchesKeyword(keyword: String): Boolean {
    if (keyword.isBlank()) return true
    return displayName.contains(keyword, ignoreCase = true) ||
        uid.contains(keyword, ignoreCase = true) ||
        uin.contains(keyword, ignoreCase = true) ||
        domain.contains(keyword, ignoreCase = true) ||
        domainHost.contains(keyword, ignoreCase = true) ||
        phoneNumber.contains(keyword, ignoreCase = true) ||
        alias.contains(keyword, ignoreCase = true)
}

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

/**
 * 数字滚动：把 "1,234 个" 拆成前缀 / 数字 / 后缀，动画期间只滚动数字部分。
 *
 * 解析失败返回 null，调用方退回静态文本。
 */
data class NumberRoll(
    val prefix: String,
    val number: Double,
    val suffix: String,
    val decimals: Int,
    val grouped: Boolean,
) {

    /** 按当前动画值拼回完整文本 */
    fun format(current: Double): String {
        val body = when {
            decimals > 0 -> String.format(Locale.ROOT, "%.${decimals}f", current)
            grouped -> String.format(Locale.ROOT, "%,d", current.toLong())
            else -> current.toLong().toString()
        }
        return prefix + body + suffix
    }

    companion object {

        private val PATTERN = Regex("^([^\\d]*)([\\d,]+(?:\\.\\d+)?)(.*)$")

        fun parse(text: String): NumberRoll? {
            val matched = PATTERN.find(text) ?: return null
            val prefix = matched.groupValues[1]
            val numberText = matched.groupValues[2]
            val suffix = matched.groupValues[3]
            val grouped = numberText.contains(',')
            val cleaned = numberText.replace(",", "")
            val value = cleaned.toDoubleOrNull() ?: return null
            val decimals = if (cleaned.contains('.')) cleaned.substringAfter('.').length else 0
            return NumberRoll(prefix, value, suffix, decimals, grouped)
        }
    }
}

/**
 * 指标块：标签（弱）+ 主数值（等宽大字）+ 说明。
 *
 * 排版固定为「标签 / 数值 / 说明」三行，内边距统一取 cardPad，
 * 多张卡并排时左边缘、数值基线、说明行三处都对得上。
 */
class KpiTile(
    label: String,
    value: String,
    hint: String? = null,
    private val tone: Color = AccountUiColors.brass,
) : RoundedCard(AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true) {

    private val valueLabel = JLabel()
    private val rollAnim = JellyValue(this, 0.0)

    init {
        border = JBUI.Borders.empty(AccountUiSpacing.cardPadV, AccountUiSpacing.cardPadH)

        add(
            JLabel(label).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = AccountUiType.ui(AccountUiType.label)
                foreground = AccountUiColors.textMuted
            }
        )
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xs)))

        valueLabel.alignmentX = Component.LEFT_ALIGNMENT
        // 主数值可能是「2 个」「—」，含中文时自动退回界面字体
        valueLabel.font = AccountUiType.forText(value, AccountUiType.display, Font.BOLD)
        valueLabel.foreground = tone
        add(valueLabel)

        // 首次落位直接给目标值：从 0 起跳会先闪一个「0」，看起来像多余字符
        val roll = NumberRoll.parse(value)
        valueLabel.text = if (roll == null) value else roll.format(roll.number)
        rollAnim.jumpTo(roll?.number ?: 0.0)

        if (!hint.isNullOrBlank()) {
            add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xxs)))
            // 说明文字按可用宽度折行：固定单行 JLabel 在窄卡片里会被直接裁掉
            add(KpiHint(hint))
        }
    }
}

/**
 * KPI 说明文字：按可用宽度折行并自绘，长文案不会被卡片裁掉。
 *
 * 中文按字、英文按词设断点，超长单词再按字符拆开；高度只取实际行数，
 * 不在卡片里预留空白行，同行对齐交给网格布局处理。
 */
private class KpiHint(private val raw: String) : JComponent() {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textFaint
        toolTipText = raw
    }

    /** 可用宽度：优先取已布局宽度，首次布局时退回父容器的内容宽度 */
    private fun lineWidth(): Int {
        if (width > 0) return width
        val host = parent ?: return 0
        return (host.width - host.insets.left - host.insets.right).coerceAtLeast(0)
    }

    /** 按空格与中文字符切分后逐段折行 */
    private fun lines(maxWidth: Int): List<String> {
        if (maxWidth <= 0) return listOf(raw)
        val fm = getFontMetrics(font)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        val word = StringBuilder()

        fun appendPart(part: String) {
            if (part.isEmpty()) return
            val partWidth = fm.stringWidth(part)
            if (current.isNotEmpty() && fm.stringWidth(current.toString()) + partWidth > maxWidth) {
                result += current.toString()
                current.setLength(0)
            }
            if (partWidth <= maxWidth) {
                current.append(part)
                return
            }
            for (ch in part) {
                if (current.isNotEmpty() && fm.stringWidth(current.toString()) + fm.charWidth(ch) > maxWidth) {
                    result += current.toString()
                    current.setLength(0)
                }
                current.append(ch)
            }
        }

        for (ch in raw) {
            when {
                ch == ' ' -> {
                    word.append(ch)
                    appendPart(word.toString())
                    word.setLength(0)
                }
                ch.code >= 0x2E80 -> {
                    appendPart(word.toString())
                    word.setLength(0)
                    appendPart(ch.toString())
                }
                else -> word.append(ch)
            }
        }
        appendPart(word.toString())
        if (current.isNotEmpty()) result += current.toString()
        return result.ifEmpty { listOf("") }
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val available = lineWidth()
        val lineCount = lines(available).size
        return Dimension(
            if (available > 0) available else fm.stringWidth(raw),
            lineCount * fm.height,
        )
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val fm = getFontMetrics(font)
        g.color = foreground
        // 折行宽度与首选高度计算保持一致，避免绘制行数与组件高度不对齐
        lines(lineWidth()).forEachIndexed { index, line ->
            g.drawString(line, 0, fm.ascent + index * fm.height)
        }
    }
}

/**
 * 摘要卡：白色表面 + 1px 描边 + 单层落影。
 *
 * 与其它卡片同材质，靠内部的字号阶梯（display 数值）承担「第一眼看什么」，
 * 不再用整块深色渐变抢视觉——工具窗里高饱和面只留给状态与可点元素。
 */
open class HeroCard(
    private val arc: Int = AccountUiRadius.card,
) : JPanel(BorderLayout()) {

    /** hover 进度：只影响落影深浅，不做位移与扫光 */
    private val hover = JellyValue(this, 0.0)

    /**
     * 是否自绘卡片底。
     *
     * 看板上的 Hero 已经套了 DashboardCard 外壳，再画一层就会出现「卡中卡」，
     * 由外层置 false，只保留内部排版。
     */
    var paintSurface: Boolean = true

    init {
        isOpaque = false
    }

    /** 鼠标进入 / 离开时由外层统一调用（子组件不冒泡，需外层转达） */
    fun setHovering(value: Boolean) {
        hover.animateTo(if (value) 1.0 else 0.0, 200)
    }

    override fun paintComponent(g: Graphics) {
        if (!paintSurface) {
            super.paintComponent(g)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val a = JBUI.scale(arc).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, a, a)
            Jelly.paintShadow(g2, shape, 0.8f + 0.2f * hover.value.toFloat())
            Jelly.paintBody(g2, shape, AccountUiColors.cardBg, JellyMaterial.FLAT)
            Jelly.paintEdge(
                g2, shape,
                if (hover.value > 0.05) AccountUiColors.borderActive else AccountUiColors.border,
                1f,
            )
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 可滚动的积分数字：数值变化时从旧值平滑过渡到新值，并闪一次强调色。
 *
 * 自己绘制而不是用 JLabel 的原因：
 * - 补间过程中要逐帧改文本，JLabel 每次 setText 都会引起布局抖动；
 * - 变化时的高亮与轻微放大放在 paint 层，不会挤到相邻元素。
 *
 * 数值变动只登记待播过渡，等组件进入滚动视口才播放；
 * 一次过渡只播放一次，滚出视口不复位也不重播。
 */
class AnimatedCreditValue : JComponent() {

    /** 补间值：从旧值滚到新值 */
    private val anim = JellyValue(this, 0.0)

    /** 变化脉冲：0 无 → 1 最强，驱动金色高亮与轻微放大 */
    private val pulse = JellyValue(this, 0.0)

    /** 目标值 */
    private var target: Double = 0.0

    /** 当前显示值的小数位数：待播期间保持消耗前的格式，播放后切到目标格式 */
    private var decimals: Int = 0

    /** 目标值的小数位数：整数 0 位，有小数 2 位 */
    private var targetDecimals: Int = 0

    /** 滚动起点的原样文本：位数变化时预留宽度 */
    private var fromText: String? = null

    /** 已登记的目标值：null 表示还没展示过数值（首次加载不播动画） */
    private var shown: Double? = null

    /** 待播放的滚动目标：进入视口时消费 */
    private var pending: Double? = null

    /** 当前是否处在滚动视口内 */
    private var inViewport = false

    /** 视口可见性：进入时播放待播过渡，离开时只更新标记 */
    private val viewportTrigger = ViewportTrigger(this) { visible ->
        inViewport = visible
        if (visible) playPending()
    }

    /** 静态文本模式（"—" / "…" 这类非数值） */
    private var staticText: String? = null

    /** 脉冲回落延时器 */
    private var pulseTimer: Timer? = null

    /** 数值变化回调：参数为增量（增加为正，减少为负） */
    var onDelta: ((Double) -> Unit)? = null

    init {
        isOpaque = false
        // 看板 Hero 的 38px 黄铜读数（对应 .dash-hero-num）
        font = AccountUiType.mono(AccountUiType.heroNum)
        alignmentY = Component.CENTER_ALIGNMENT
    }

    /** 数字字体的下伸部高度（用来把旁边小字的基线对齐到数字基线） */
    val fontDescent: Int get() = getFontMetrics(font).descent

    /**
     * 设置显示文本。
     *
     * 文本是数字时按数字滚动过渡（"100" → "120"），
     * 不是数字时（"—" / "…"）直接静态显示。
     *
     * @param animate 是否播放滚动动画
     */
    fun setText(text: String, animate: Boolean = true) {
        val number = parseNumber(text)
        if (number == null) {
            staticText = text
            shown = null
            pending = null
            fromText = null
            target = 0.0
            anim.jumpTo(0.0)
            revalidate()
            repaint()
            return
        }
        staticText = null
        setNumber(number, animate)
    }

    /** 设置数值：变化只登记过渡，播放时机交给 [viewportTrigger] */
    private fun setNumber(value: Double, animate: Boolean) {
        if (!value.isFinite()) return
        val prev = shown
        val changed = prev == null || Math.abs(prev - value) > 1e-6
        shown = value
        target = value
        targetDecimals = if (value == value.toLong().toDouble()) 0 else 2
        if (!changed) return
        if (!animate || prev == null) {
            // 首次加载 / 明确不动画：直接就位，不登记过渡
            pending = null
            fromText = null
            decimals = targetDecimals
            anim.jumpTo(value)
            revalidate()
            repaint()
            return
        }
        // 待播期间仍显示消耗前数值，格式也保持原样
        fromText = format(anim.value)
        pending = value
        viewportTrigger.check()
        // 已在视口内：立即播放本次过渡（每次变化只播一次）
        if (inViewport) playPending()
        revalidate()
        repaint()
    }

    /** 消费待播过渡；播放后即使再次进入视口也不会重播 */
    private fun playPending() {
        val next = pending ?: return
        pending = null
        val from = anim.value
        decimals = targetDecimals
        if (Math.abs(from - next) < 1e-6) {
            anim.jumpTo(next)
            repaint()
            return
        }
        anim.animateTo(next, 720, { t -> Easing.outCubic(t) })
        playPulse()
        onDelta?.invoke(next - from)
        repaint()
    }

    /** 变化脉冲：先快速亮起，再缓慢褪回 */
    private fun playPulse() {
        pulseTimer?.stop()
        pulse.jumpTo(0.0)
        pulse.animateTo(1.0, 180)
        pulseTimer = JellyClock.delay(180) { pulse.animateTo(0.0, 760) }
    }

    override fun addNotify() {
        super.addNotify()
        viewportTrigger.check()
    }

    override fun removeNotify() {
        pulseTimer?.stop()
        pulse.stop()
        // 不停止 anim：滚动中途被摘下时继续补到终值，重新挂上不重置、不重播
        viewportTrigger.unbind()
        super.removeNotify()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        // 按滚动过程中最宽的形态预留宽度，位数变化也不会挤动布局
        val numeric = fm.stringWidth(widestText()) * 1.08f
        val static = staticText?.let { fm.stringWidth(it).toFloat() } ?: 0f
        val w = maxOf(numeric, static) + JBUI.scale(4)
        return Dimension(w.toInt().coerceAtLeast(JBUI.scale(32)), fm.height + JBUI.scale(2))
    }

    /** 宽度只按内容算：横向 BoxLayout 里不参与拉伸，否则数字与单位之间会被撑出一大段空白 */
    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, Short.MAX_VALUE.toInt())

    /** 当前可能出现的最宽文本：兼顾新旧值的位数差异 */
    private fun widestText(): String {
        val candidates = buildList {
            add(format(target))
            fromText?.let { add(it) }
            if (targetDecimals > 0) add(String.format(Locale.ROOT, "%.2f", target))
        }
        // 等宽字体下字符数即宽度
        return candidates.maxByOrNull { it.length } ?: "0"
    }

    /** 按当前小数位数格式化：整数不带小数，小数保留两位并去掉多余的 0 */
    private fun format(v: Double): String {
        if (!v.isFinite()) return "0"
        if (decimals <= 0) return v.toLong().toString()
        return String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val fm = g2.fontMetrics
            val p = pulse.value.toFloat()
            val text = staticText ?: format(anim.value)
            g2.font = font
            g2.color = if (staticText != null) {
                AccountUiColors.textPrimary
            } else {
                // 数值变化时闪一次朱印，静止后回到黄铜读数
                Jelly.mix(AccountUiColors.brass, AccountUiColors.seal, p)
            }
            // 脉冲只做整数像素上移：分数倍 scale 会让 40px 大字的字形每帧重新栅格化，
            // 刷新期间持续发虚，改用颜色 + 整数位移表达同样的呼吸感
            val rise = (p * JBUI.scale(2)).toInt()
            g2.translate(0, height - fm.descent - JBUI.scale(1) - rise)
            g2.drawString(text, 0, 0)
        } finally {
            g2.dispose()
        }
    }

    private companion object {

        /** 只认纯数字文本（可带千分位 / 小数），避免把 "12 个" 之类的文案当成数值 */
        val NUMBER_TEXT = Regex("^[+-]?[\\d,]+(?:\\.\\d+)?$")

        /** 解析纯数字文本 */
        fun parseNumber(text: String): Double? {
            val trimmed = text.trim()
            if (!NUMBER_TEXT.matches(trimmed)) return null
            return trimmed.replace(",", "").toDoubleOrNull()
        }
    }
}

/**
 * Hero 右上角的增量徽标：数值上涨 / 下跌时闪一次（箭头 + 增量），随后自动淡出。
 */
class HeroDeltaBadge : JComponent() {

    /** 当前文案 */
    private var text: String = ""

    /** 显隐进度 */
    private val alpha = JellyValue(this, 0.0)

    /** 淡出延时器 */
    private var fadeTimer: Timer? = null

    init {
        isOpaque = false
        font = AccountUiType.mono(AccountUiType.caption, Font.BOLD)
    }

    /** 展示一次增量；[delta] 为 0 时收起 */
    fun show(delta: Double) {
        if (!delta.isFinite() || Math.abs(delta) < 0.005) {
            clear()
            return
        }
        text = "${if (delta > 0) "↑" else "↓"} ${formatAbs(delta)}"
        fadeTimer?.stop()
        alpha.jumpTo(0.0)
        alpha.animateTo(1.0, 200)
        fadeTimer = JellyClock.delay(2400) { alpha.animateTo(0.0, 620) }
        revalidate()
        repaint()
    }

    /** 立即收起 */
    fun clear() {
        fadeTimer?.stop()
        fadeTimer = null
        alpha.animateTo(0.0, 200)
        revalidate()
        repaint()
    }

    override fun removeNotify() {
        fadeTimer?.stop()
        alpha.stop()
        super.removeNotify()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = if (text.isEmpty()) 0 else fm.stringWidth(text) + JBUI.scale(14)
        return Dimension(w, fm.height + JBUI.scale(7))
    }

    override fun paintComponent(g: Graphics) {
        val a = alpha.value.toFloat()
        if (a <= 0.01f || text.isEmpty()) return
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val shape = Jelly.pill(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat())
            Jelly.paintBody(g2, shape, Jelly.alpha(AccountUiColors.brassSoft, 0.9f * a), JellyMaterial.FLAT)
            Jelly.paintEdge(g2, shape, Jelly.alpha(AccountUiColors.brassInk, 0.45f * a), 1f)
            g2.font = font
            // 淡入淡出用不透明混色表达，避免文字前景带 alpha 后边缘再被摊薄一层
            g2.color = Jelly.mix(AccountUiColors.cardBg, AccountUiColors.brassInk, a)
            val fm = g2.fontMetrics
            g2.drawString(text, Jelly.textX(width, fm.stringWidth(text)), Jelly.textBaseline(height, fm))
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        fun formatAbs(v: Double): String {
            val abs = Math.abs(v)
            return if (abs == abs.toLong().toDouble()) abs.toLong().toString()
            else String.format(Locale.ROOT, "%.2f", abs).trimEnd('0').trimEnd('.')
        }
    }
}

/** Hero 内的细分隔线 */
private class HeroRule : JComponent() {

    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(1))
        minimumSize = Dimension(0, JBUI.scale(1))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = AccountUiColors.divider
            g2.fill(Rectangle2D.Float(0f, 0f, width.toFloat(), 1f))
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 只按内容取宽度的标签。
 *
 * 横向 BoxLayout 会按 maximumSize 分配多余空间，而 JComponent 默认最大宽度是
 * Short.MAX_VALUE，直接放进去就会被拉宽，于是「数字」与「单位」之间出现一大段空白。
 */
private class FixedWidthLabel(text: String = "") : JLabel(text) {
    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, Short.MAX_VALUE.toInt())
}

/**
 * 摘要卡：今日消耗积分为主视觉。
 *
 * 排版（自上而下，全部左对齐同一条轴线）：
 *  1. 标签行：小标题 + 右侧增量徽标
 *  2. 主数值行：display 等宽数字 + 单位 + 行尾更新时间
 *  3. 分隔线
 *  4. 次级指标：等宽三列（标签在上、数值在下），列宽一致即对齐
 *
 * 数值动画要跨刷新延续（100 → 120 的滚动过渡），
 * 因此看板侧复用同一个实例，通过 [setValue] / [setMetrics] 原地更新，
 * 而不是每次刷新重建组件（重建会让动画从头再滚一遍）。
 */
class HeroPanel(
    title: String,
    value: String,
    unit: String? = null,
    metrics: List<Pair<String, String>> = emptyList(),
) : HeroCard() {

    /** 主数值：自绘，数值变化时从旧值滚到新值 */
    private val creditValue = AnimatedCreditValue()

    /** 单位文本：等宽小字，与黄铜读数同一基线 */
    private val unitLabel = FixedWidthLabel(unit.orEmpty()).apply {
        font = AccountUiType.mono(AccountUiType.label, Font.PLAIN)
        foreground = AccountUiColors.textMuted
    }

    /** 最近更新时间：小字（含中文，走界面字体） */
    private val updatedLabel = FixedWidthLabel().apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textFaint
    }

    /** 增量徽标（标签行右侧） */
    private val deltaBadge = HeroDeltaBadge()

    /** 分隔线 */
    private val rule = HeroRule()

    /** 次级指标：等宽列容器（每次刷新重建列，列宽自动均分） */
    private val metricsRow = JPanel(GridLayout(1, 1, JBUI.scale(AccountUiSpacing.md), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    init {
        border = JBUI.Borders.empty(AccountUiSpacing.heroPadV, AccountUiSpacing.heroPadH)

        // 数值变化时把增量交给徽标展示
        creditValue.onDelta = { delta -> deltaBadge.show(delta) }

        // 标签行：小标题 + 增量徽标
        val titleRow = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        titleRow.add(
            JLabel(title).apply {
                font = font.deriveFont(Font.BOLD, AccountUiType.label)
                foreground = AccountUiColors.textMuted
            },
            BorderLayout.WEST,
        )
        titleRow.add(deltaBadge, BorderLayout.EAST)

        // 主数值行：大号数字 + 单位 + 最近更新时间
        val valueRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        creditValue.alignmentY = Component.CENTER_ALIGNMENT
        unitLabel.alignmentY = Component.BOTTOM_ALIGNMENT
        updatedLabel.alignmentY = Component.BOTTOM_ALIGNMENT
        valueRow.add(creditValue)
        valueRow.add(Box.createHorizontalStrut(JBUI.scale(8)))
        valueRow.add(unitLabel)
        // 多余宽度全给胶水：单位紧跟数字，更新时间贴行尾，中间不会隔着大段空白
        valueRow.add(Box.createHorizontalGlue())
        valueRow.add(updatedLabel)

        val north = FullWidthColumn()
        north.add(titleRow)
        north.add(Box.createVerticalStrut(JBUI.scale(4)))
        north.add(valueRow)
        add(north, BorderLayout.NORTH)

        // 分隔线 + 次级指标胶囊
        val center = JPanel(BorderLayout()).apply { isOpaque = false }
        center.add(rule, BorderLayout.NORTH)
        center.add(metricsRow, BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)

        alignBaseline()
        setValue(value)
        setMetrics(metrics)
    }

    /** 更新主数值：其中的数字会滚动过渡到新值 */
    fun setValue(text: String, animate: Boolean = true) {
        creditValue.setText(text, animate)
    }

    /** 更新次级指标：重建等宽列，保证每列宽度一致 */
    fun setMetrics(metrics: List<Pair<String, String>>) {
        metricsRow.removeAll()
        metricsRow.layout = GridLayout(1, metrics.size.coerceAtLeast(1), JBUI.scale(AccountUiSpacing.md), 0)
        for ((k, v) in metrics) {
            metricsRow.add(MetricCell(k, v, AccountUiColors.textPrimary))
        }
        rule.isVisible = metrics.isNotEmpty()
        metricsRow.revalidate()
        metricsRow.repaint()
    }

    /** 更新「最近更新时间」 */
    fun setUpdatedAt(text: String?) {
        updatedLabel.text = text.orEmpty()
        updatedLabel.isVisible = !text.isNullOrBlank()
    }

    /** 在纵向 BoxLayout 里撑满宽度，高度按当前内容（宽度变化时胶囊换行也能跟上） */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    /** 小字基线对齐大号数字：数字下伸部更大，补一点下边距即可对齐 */
    private fun alignBaseline() {
        val unitDescent = unitLabel.getFontMetrics(unitLabel.font).descent
        val pad = (creditValue.fontDescent - unitDescent).coerceAtLeast(0)
        unitLabel.border = JBUI.Borders.empty(0, 0, pad, 0)
        updatedLabel.border = JBUI.Borders.empty(0, 0, pad, 0)
    }
}

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
 * 表格统一样式：表头、斑马纹、行高、网格线，外加鼠标所在行的轻高亮。
 *
 * 行 hover 是表格里最划算的一处交互：一屏几十行时，
 * 光标停在哪一行不用再靠水平漂移去对齐。
 */
object AccountTableStyle {

    fun apply(table: JBTable, widths: IntArray) {
        table.rowHeight = JBUI.scale(28)
        table.showHorizontalLines = true
        table.showVerticalLines = false
        table.gridColor = AccountUiColors.tableGrid
        table.intercellSpacing = Dimension(0, 0)
        table.border = JBUI.Borders.empty()
        table.background = AccountUiColors.tableBodyBg
        table.foreground = AccountUiColors.textPrimary
        table.selectionBackground = AccountUiColors.brassSoft
        table.selectionForeground = AccountUiColors.textPrimary
        table.fillsViewportHeight = true

        val header = table.tableHeader
        // 账本式表头：等宽小字 + 1.5px 墨蓝底线
        header.background = AccountUiColors.tableHeaderBg
        header.foreground = AccountUiColors.textSecondary
        // 表头是中文列名，等宽字体没有中文字形
        header.font = AccountUiType.ui(AccountUiType.caption)
        header.border = JBUI.Borders.customLine(AccountUiColors.ink, 0, 0, 2, 0)
        header.reorderingAllowed = false
        // JBTableHeader 的默认渲染器按 LAF 取色：深色主题下表头会变成深底浅字，
        // 与固定浅色的纸面冲突。这里换成显式着色的渲染器，保证任何主题下表头都是抽屉面。
        header.defaultRenderer = accountHeaderRenderer()

        widths.forEachIndexed { index, width ->
            if (index < table.columnModel.columnCount) {
                table.columnModel.getColumn(index).preferredWidth = JBUI.scale(width)
            }
        }

        installRowHover(table)
        installRowRenderer(table)
    }

    /** 表头渲染器：抽屉面底 + 次级文字，不跟随 IDE 深浅主题 */
    private fun accountHeaderRenderer(): TableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            tbl: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column).also {
            isOpaque = true
            background = AccountUiColors.tableHeaderBg
            foreground = AccountUiColors.textSecondary
            font = AccountUiType.ui(AccountUiType.caption)
            horizontalAlignment = SwingConstants.LEFT
            border = headerCellBorder
        }
    }

    /** 表头单元格内边距 */
    private val headerCellBorder = JBUI.Borders.empty(0, 8)

    /** 鼠标所在行高亮：行号记在客户端属性里，由渲染器读取 */
    private fun installRowHover(table: JTable) {
        table.putClientProperty(HOVER_ROW_KEY, -1)
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                if (row != (table.getClientProperty(HOVER_ROW_KEY) as? Int)) {
                    table.putClientProperty(HOVER_ROW_KEY, row)
                    table.repaint()
                }
            }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if ((table.getClientProperty(HOVER_ROW_KEY) as? Int ?: -1) != -1) {
                    table.putClientProperty(HOVER_ROW_KEY, -1)
                    table.repaint()
                }
            }
        })
    }

    private const val HOVER_ROW_KEY = "codebuddy.account.hoverRow"

    /** 单元格内边距：渲染时复用同一个实例，避免每个格子新建 Border */
    private val cellBorder = JBUI.Borders.empty(0, 8)

    private fun installRowRenderer(table: JTable) {
        val delegate = table.getDefaultRenderer(Any::class.java)
        table.setDefaultRenderer(
            Any::class.java,
            object : TableCellRenderer {
                override fun getTableCellRendererComponent(
                    tbl: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int,
                ): Component {
                    val comp = delegate.getTableCellRendererComponent(
                        tbl, value, isSelected, hasFocus, row, column
                    )
                    if (comp is JComponent) {
                        val hoverRow = tbl?.getClientProperty(HOVER_ROW_KEY) as? Int ?: -1
                        // 不用斑马纹：行 hover 已经足够定位，横向满色块反而干扰读数字
                        comp.background = when {
                            isSelected -> AccountUiColors.brassSoft
                            row == hoverRow -> AccountUiColors.cardHoverBg
                            else -> AccountUiColors.tableBodyBg
                        }
                        comp.foreground = AccountUiColors.textPrimary
                        (comp as? JLabel)?.border = cellBorder
                    }
                    return comp
                }
            }
        )
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

/** 让 JTable 也用统一配色（供未走 [AccountTableStyle] 的表格调用） */
fun JTable.applyAccountTableColors() {
    background = AccountUiColors.tableBodyBg
    foreground = AccountUiColors.textPrimary
    gridColor = AccountUiColors.tableGrid
    rowHeight = JBUI.scale(28)
    showVerticalLines = false
    intercellSpacing = Dimension(0, 0)
    tableHeader.background = AccountUiColors.tableHeaderBg
    tableHeader.foreground = AccountUiColors.textSecondary
}

/** 小组件：判断点是否落在组件内（按下后松手才算点击） */
private object SwingUtilities2 {
    fun contains(component: JComponent, point: Point): Boolean =
        component.isShowing && component.contains(point)
}
