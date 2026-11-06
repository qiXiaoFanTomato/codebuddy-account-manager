package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaGroup
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * 额度卡片组件集：账号卡片 / 套餐分组卡片 / 细项卡片 / 进度条 / 标签。
 *
 * 全部自绘圆角背景与描边，颜色取自 [AccountUiColors]，跟随 IDE 主题。
 */
object QuotaCardUi {

    /** 名称超过该长度截断（卡片宽度有限） */
    private const val NAME_LIMIT = 24

    fun shortName(name: String): String =
        if (name.length <= NAME_LIMIT) name else name.take(NAME_LIMIT - 1) + "…"

    fun fmt(v: Double): String = QuotaPackage.fmt(v)

    /** 剩余百分比对应的语义色：黄铜读数 / 朱印低额度 */
    fun percentColor(percent: Double): Color = when {
        percent <= 20 -> AccountUiColors.seal
        else -> AccountUiColors.brass
    }
}

/**
 * 卡片容器：纯色底 + 1px 描边 + 单层落影，子组件纵向排列。
 *
 * hover 只提亮底色与描边，不做缩放 / 位移变形——位移会让密集列表在
 * 鼠标扫过时抖动，且对表格类信息没有收益。
 *
 * @param elevated true 时绘制落影（仅外层卡片使用，内层块保持平面，
 * 避免阴影叠加显脏）
 * @param pill true 时圆角取高度一半，呈胶囊形
 * @param material 材质层级：外层卡片用 [JellyMaterial.FLAT]，
 * 内层嵌套块用 [JellyMaterial.SOFT]（多一条顶部亮线）
 */
open class RoundedCard(
    private val fill: Color,
    private val stroke: Color,
    arc: Int = AccountUiRadius.card,
    private val elevated: Boolean = false,
    private val pill: Boolean = false,
    private val material: JellyMaterial = JellyMaterial.GEL,
) : JPanel() {

    private val arcSize = JBUI.scale(arc)

    /** 整块卡片可点击时，hover 用于替换底色（null 表示不参与 hover 反馈） */
    var hoverFill: Color? = null

    /** 选中 / 激活态描边色（非 null 时覆盖 [stroke]） */
    var accentStroke: Color? = null

    /** 描边宽度 */
    var strokeWidth: Float = 1f

    /** 是否启用按压回弹（仅整块可点击的卡片开启，避免纯展示卡片被误触） */
    var pressFeedback: Boolean = false

    /**
     * 档案卡索引 tab：非空时在卡片左上角压一枚墨蓝小标签（当前激活 / 索引卡分类）。
     *
     * 对应 HTML 的 `.acct-tab` / `.index-tab`。使用方需要把 border 上边距
     * 额外留出一枚 tab 的高度，避免内容压到标签上。
     */
    var tabText: String? = null
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    /** tab 的绘制高度（dp），供使用方设置上边距 */
    val tabHeightDp: Int get() = 21

    private var hovering = false

    /** 抬起进度：0 贴地，1 完全抬起（果冻被拿起的观感） */
    private val lift = JellyValue(this, 0.0)

    /** 按压进度：1 表示鼠标按下，卡片轻微下沉 */
    private val press = JellyValue(this, 0.0)

    /** 由点击辅助方法调用，切换 hover 底色与抬起动画 */
    fun setHovering(value: Boolean) {
        if (hovering == value) return
        hovering = value
        lift.animateTo(
            if (value) 1.0 else 0.0,
            if (value) 200 else 280,
            { t -> Easing.outCubic(t) },
        )
        repaint()
    }

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT

        // 按压回弹：按下立刻下沉，松开用回弹曲线弹回
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!pressFeedback) return
                press.animateTo(1.0, 90, { t -> Easing.outCubic(t) })
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!pressFeedback) return
                press.animateTo(0.0, 260, { t -> Easing.outBack(t) })
            }

            override fun mouseExited(e: MouseEvent) {
                if (pressFeedback && press.value > 0.01) {
                    press.animateTo(0.0, 200, { t -> Easing.outBack(t) })
                }
            }
        })
    }

    /** 纵向 BoxLayout 里宽度要撑满，高度按内容 */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 2f || h <= 2f) return

            val liftValue = lift.value.toFloat()
            val pressValue = press.value.toFloat()
            val shape = if (pill) {
                Jelly.pill(0.5f, 0.5f, w - 1f, h - 1f)
            } else {
                Jelly.round(0.5f, 0.5f, w - 1f, h - 1f, arcSize.toFloat())
            }

            if (elevated) {
                // 抬起时影子更深，按下时收一点
                Jelly.paintShadow(g2, shape, 0.65f + 1.0f * liftValue - 0.2f * pressValue)
            }

            // 底色按抬起进度平滑过渡，避免 hover 时整块突变
            val base = hoverFill?.let { Jelly.mix(fill, it, liftValue) } ?: fill
            Jelly.paintBody(g2, shape, base, material)

            val edge = accentStroke
                ?: if (liftValue > 0.05f) Jelly.mix(stroke, AccountUiColors.ink, liftValue) else stroke
            Jelly.paintEdge(g2, shape, edge, strokeWidth)
            paintTab(g2)
        } finally {
            g2.dispose()
        }
    }

    /** 左上角的档案卡索引 tab：墨蓝底 + 浅色等宽标签文字 */
    private fun paintTab(g2: Graphics2D) {
        val text = tabText ?: return
        g2.font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.tab)
        val fm = g2.fontMetrics
        val padH = JBUI.scale(13)
        val w = (fm.stringWidth(text) + padH * 2).toFloat()
        val h = JBUI.scale(tabHeightDp).toFloat()
        val x = JBUI.scale(18).toFloat()
        val shape = Jelly.round(x, 0f, w, h, JBUI.scale(AccountUiRadius.card).toFloat())
        Jelly.paintBody(g2, shape, AccountUiColors.ink, JellyMaterial.FLAT)
        g2.color = AccountUiColors.onInk
        g2.drawString(text, x.toInt() + padH, (h.toInt() - fm.height) / 2 + fm.ascent)
    }
}

/**
 * 可点击整行：hover 时铺一层浅色底（radius 4），用于看板排行与到期条目。
 *
 * 子组件需自行 add 到 CENTER / WEST / EAST。
 */
open class HoverRow(
    private val hoverFill: Color = AccountUiColors.cardHoverBg,
) : JPanel(BorderLayout()) {

    /** hover 进度：0 无，1 完全悬停（用补间而不是硬切，鼠标扫过时不会闪） */
    private val hoverAnim = JellyValue(this, 0.0)

    var hover: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            hoverAnim.animateTo(if (value) 1.0 else 0.0, if (value) 120 else 160)
        }

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(AccountUiSpacing.xs, AccountUiSpacing.sm)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                cursor = Cursor.getDefaultCursor()
            }
        })
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val value = hoverAnim.value.toFloat()
        if (value > 0.01f) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(AccountUiRadius.chip).toFloat()
                g2.color = Jelly.alpha(hoverFill, value)
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }
}

/**
 * 1px 虚线分隔：档案卡底部、切换日志用。
 *
 * 对应 HTML 的 `.card-foot { border-top: 1px dashed var(--rule) }`
 * 与 `.dash-row { border-bottom: 1px dashed var(--rule) }`。
 */
class DashedRule(private val color: Color = AccountUiColors.rule) : JComponent() {

    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(1))
        minimumSize = preferredSize
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.paintDashedLine(g2, 0f, 0.5f, width.toFloat(), color)
        } finally {
            g2.dispose()
        }
    }
}

/** 横向一行：宽度撑满、高度按内容（作为 BoxLayout 子项必需，否则右对齐失效） */
open class FullWidthRow(layout: LayoutManager = BorderLayout()) : JPanel(layout) {
    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** 纵向容器：宽度撑满、高度按内容（用于卡片内的子列表） */
class FullWidthColumn : JPanel() {
    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * 额度读数条：直角轨道 + 黄铜充盈 + 每 9px 一条的铅线刻度。
 *
 * 对应 HTML 的 `.meter`：充盈色是黄铜（低于 20% 转朱印），
 * 刻度覆盖整条轨道，读起来像量具而不是普通进度条。
 */
class QuotaBar(percent: Double) : JComponent() {

    /** 充盈动画当前值（与 [percent] 目标值分离，避免刷新时瞬间跳变） */
    private val anim = JellyValue(this, percent.coerceIn(0.0, 100.0))

    var percent: Double = percent.coerceIn(0.0, 100.0)
        set(value) {
            val target = value.coerceIn(0.0, 100.0)
            field = target
            toolTipText = "剩余 ${"%.0f".format(target)}%"
            anim.animateTo(target, 320, { t -> Easing.outQuint(t) })
        }

    init {
        isOpaque = false
        toolTipText = "剩余 ${"%.0f".format(percent.coerceIn(0.0, 100.0))}%"
        preferredSize = Dimension(0, JBUI.scale(BAR_HEIGHT))
        minimumSize = Dimension(0, JBUI.scale(BAR_HEIGHT))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(BAR_HEIGHT))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height.toFloat()
            val w = width.toFloat()
            if (h <= 0f || w <= 0f) return

            g2.color = AccountUiColors.barTrack
            g2.fill(Rectangle2D.Float(0f, 0f, w, h))

            val pct = (anim.value / 100.0).coerceIn(0.0, 1.0)
            if (pct > 0.0) {
                val fw = (w * pct).toFloat().coerceAtMost(w)
                g2.color = if (anim.value <= 20.0) AccountUiColors.barFillLow else AccountUiColors.barFill
                g2.fill(Rectangle2D.Float(0f, 0f, fw, h))
            }
            // 刻度压在最上层：充盈段与空白段都有栅格，像档案柜上的量尺
            Jelly.paintTicks(g2, 0f, 0f, w, h)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /** 设计稿 .meter 是 9px 高 */
        const val BAR_HEIGHT = 9
    }
}

/** 小标签：2px 纸角的等宽小徽标（对应 HTML 的 .tag） */
class Chip(
    text: String,
    private val fill: Color,
    private val fg: Color,
    /** 圆角；[AccountUiRadius.pill] 表示胶囊 */
    private val arc: Int = AccountUiRadius.chip,
) : JComponent() {

    private val arcSize = JBUI.scale(arc)

    /** 文本可变，供计数类标签随数据刷新 */
    var text: String = text
        set(value) {
            if (field == value) return
            field = value
            font = AccountUiType.forText(value, AccountUiType.micro)
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        // 中文标签（个人 / 企业 / 12 条）走界面字体，纯 ASCII 域名才用等宽
        font = AccountUiType.forText(text, AccountUiType.micro)
        alignmentY = CENTER_ALIGNMENT
    }

    /** JComponent 默认无字体，兜底后再交给绘制与度量 */
    override fun getFont(): Font = super.getFont() ?: baseLabelFont()

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(12), fm.height + JBUI.scale(6))
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val w = (width - 1).toFloat()
            val h = (height - 1).toFloat()
            val radius = if (arc == AccountUiRadius.pill) h else arcSize.toFloat()
            val shape = Jelly.round(0.5f, 0.5f, w, h, radius)
            Jelly.paintBody(g2, shape, fill, JellyMaterial.FLAT)
            // 细描边：徽标在深浅两种主题下都要有边界，否则浅底会糊进卡片
            Jelly.paintEdge(g2, shape, Jelly.alpha(fg, 0.35f), 1f)
            g2.font = font
            g2.color = fg
            val fm = g2.fontMetrics
            g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 单条配额细项：名称 + 已用/总量，第二行到期时间。
 */
class QuotaItemCard(private val pkg: QuotaPackage) :
    RoundedCard(
        AccountUiColors.itemBg, AccountUiColors.divider, AccountUiRadius.inner,
        material = JellyMaterial.SOFT,
    ) {

    init {
        border = JBUI.Borders.empty(AccountUiSpacing.sm, AccountUiSpacing.md)
        val top = FullWidthRow(BorderLayout(JBUI.scale(6), 0))

        val name = JLabel(QuotaCardUi.shortName(pkg.subProductName.ifBlank { pkg.name })).apply {
            font = font.deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textSecondary
            toolTipText = pkg.subProductName.ifBlank { pkg.name }
        }
        // 数值列右对齐：多条细项上下排列时数字右边缘对齐，便于纵向比较
        val usage = AccountUiKit.valueText(
            pkg.usageText(),
            when {
                pkg.expired || pkg.remain <= 0 -> AccountUiColors.textFaint
                else -> AccountUiColors.textSecondary
            },
        )
        top.add(name, BorderLayout.CENTER)
        top.add(usage, BorderLayout.EAST)

        val expireText = when {
            pkg.expired -> "已过期"
            pkg.expiredTime.isNotBlank() -> "到期 ${pkg.expiredTime.replace('-', '/')}"
            pkg.cycleEnd.isNotBlank() -> "到期 ${pkg.cycleEnd.replace('-', '/')}"
            else -> "长期有效"
        }
        val expire = JLabel(expireText).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, AccountUiType.caption)
            foreground = if (pkg.expired) AccountUiColors.danger else AccountUiColors.textFaint
        }

        add(top)
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xxs)))
        add(expire)
    }
}

/**
 * 一个套餐分组卡片：组头（名称 + 条数 + 已用/总量）、进度条、可折叠的细项列表。
 */
class QuotaGroupCard(
    private val group: QuotaGroup,
    initiallyExpanded: Boolean = false,
) :
    RoundedCard(
        AccountUiColors.surfaceBg, AccountUiColors.border, AccountUiRadius.card,
        material = JellyMaterial.SOFT,
    ) {

    private val itemsPanel = FullWidthColumn().apply { isVisible = initiallyExpanded }
    private val arrow = JLabel(if (initiallyExpanded) "▾" else "▸").apply {
        font = font.deriveFont(Font.PLAIN, AccountUiType.caption)
        foreground = AccountUiColors.textMuted
    }
    private val bar = QuotaBar(group.remainPercent)
    private var expanded = initiallyExpanded

    init {
        border = JBUI.Borders.empty(AccountUiSpacing.md, AccountUiSpacing.md + 2)
        val header = buildHeader()
        add(header)
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(bar)
        add(itemsPanel)

        for (p in group.packages.sortedBy { it.expireMillis }) {
            val item = QuotaItemCard(p)
            item.alignmentX = Component.LEFT_ALIGNMENT
            itemsPanel.add(item)
            itemsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        // 头部整行（含子标签）与进度条都可点击折叠
        installToggle(header)
        installToggle(bar)
    }

    private fun buildHeader(): JComponent {
        val header = FullWidthRow()

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
        left.add(JLabel("●").apply {
            font = font.deriveFont(Font.PLAIN, AccountUiType.micro)
            foreground = if (group.usableCount > 0) AccountUiColors.success else AccountUiColors.textFaint
        })
        left.add(JLabel(group.name).apply {
            font = font.deriveFont(Font.BOLD, AccountUiType.body)
            foreground = AccountUiColors.textPrimary
            toolTipText = group.name
        })
        left.add(
            Chip(
                "${group.packages.size} 条",
                AccountUiColors.cardBg,
                AccountUiColors.textMuted,
            )
        )

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        val usageLabel = "${group.usageText()} ${group.unit}".trim()
        right.add(JLabel(usageLabel).apply {
            font = AccountUiType.forText(usageLabel, AccountUiType.label)
            foreground = AccountUiColors.textSecondary
        })
        right.add(JLabel("${"%.0f".format(group.remainPercent)}%").apply {
            font = AccountUiType.mono(AccountUiType.label)
            foreground = QuotaCardUi.percentColor(group.remainPercent)
        })
        right.add(arrow)

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        return header
    }

    private fun installToggle(target: Component) {
        target.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
            override fun mouseEntered(e: MouseEvent) {
                target.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            override fun mouseExited(e: MouseEvent) {
                target.cursor = Cursor.getDefaultCursor()
            }
        })
        if (target is Container) {
            for (child in target.components) installToggle(child)
        }
    }

    private fun toggle() {
        expanded = !expanded
        itemsPanel.isVisible = expanded
        arrow.text = if (expanded) "▾" else "▸"
        revalidate()
        // 卡片高度变了，需要让外层滚动容器重新布局
        var p: Container? = parent
        while (p != null) {
            p.revalidate()
            p = p.parent
        }
        repaint()
    }
}

/**
 * 单个账号的额度卡片：账号信息、用量状态、套餐分组列表。
 *
 * @param summary 为 null 表示还没有额度缓存；error != null 表示上次查询失败
 */
class QuotaAccountCard(
    private val snapshot: CredentialSnapshot,
    private val summary: QuotaSummary?,
    private val active: Boolean,
) : RoundedCard(
    fill = if (active) AccountUiColors.cardActiveBg else AccountUiColors.cardBg,
    stroke = if (active) AccountUiColors.ink else AccountUiColors.border,
    arc = AccountUiRadius.card,
) {

    init {
        border = JBUI.Borders.empty(AccountUiSpacing.cardGap, AccountUiSpacing.cardPadH)
        add(buildTitle())
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.md)))

        val brief = summary
        when {
            brief == null -> add(hint("尚未查询额度，点击上方「刷新额度」获取"))
            brief.error != null -> add(hint("查询失败：${brief.error}"))
            brief.packages.isEmpty() -> add(hint("接口未返回任何套餐"))
            else -> {
                // 默认全部折叠：卡片先只给「套餐分组 + 剩余」的概览，点开才看细项
                for (group in brief.groups()) {
                    val card = QuotaGroupCard(group)
                    card.alignmentX = Component.LEFT_ALIGNMENT
                    add(card)
                    add(Box.createVerticalStrut(JBUI.scale(6)))
                }
            }
        }
    }

    private fun buildTitle(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
        left.add(JLabel(snapshot.displayName).apply {
            font = font.deriveFont(Font.BOLD, AccountUiType.title)
            foreground = AccountUiColors.textPrimary
        })
        if (active) {
            left.add(Chip("当前", AccountUiColors.accentSoft, AccountUiColors.accent))
        }
        left.add(
            Chip(
                if (snapshot.type.equals("enterprise", true)) "企业" else "个人",
                if (snapshot.type.equals("enterprise", true)) AccountUiColors.tagEnterpriseBg
                else AccountUiColors.tagPersonalBg,
                if (snapshot.type.equals("enterprise", true)) AccountUiColors.tagEnterpriseFg
                else AccountUiColors.tagPersonalFg,
            )
        )
        left.add(
            Chip(
                snapshot.domainHost.ifBlank { "—" },
                AccountUiColors.tagDomainBg,
                AccountUiColors.tagDomainFg,
            )
        )

        val valid = summary?.takeIf { it.error == null && it.packages.isNotEmpty() }
        val percent = valid?.usableRemainPercent
        val unit = valid?.unit?.ifBlank { "credits" } ?: "credits"
        // 与 HTML `.quota-sum` 一致：总剩余 x / y · z%
        val sumText =
            if (valid == null) "—"
            else "总剩余 ${fmt(valid.usableRemain)} / ${fmt(valid.usableSize)} $unit · ${"%.0f".format(percent ?: 0.0)}%"
        val right = JLabel(sumText).apply {
            font = AccountUiType.forText(sumText, AccountUiType.label)
            foreground = if (percent == null) AccountUiColors.textFaint else QuotaCardUi.percentColor(percent)
            horizontalAlignment = SwingConstants.RIGHT
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        return row
    }

    private fun fmt(v: Double): String = AccountUiKit.formatNumber(v)

    private fun hint(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(6, 2)
    }
}
