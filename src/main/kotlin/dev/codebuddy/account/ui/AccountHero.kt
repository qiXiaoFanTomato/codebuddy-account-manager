package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

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

