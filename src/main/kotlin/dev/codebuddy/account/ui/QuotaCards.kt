package dev.codebuddy.account.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaGroup
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * 额度卡片组件集：账号卡片 / 套餐分组卡片 / 细项卡片 / 进度条 / 标签。
 *
 * 全部自绘圆角背景与描边，颜色取自 [AccountUiColors]，跟随 IDE 主题。
 */
object QuotaCardUi {

    /** 细项条目底色 */
    val itemBg: Color = JBColor(Color(0xF1F3F5), Color(0x35383D))

    /** 名称超过该长度截断（卡片宽度有限） */
    private const val NAME_LIMIT = 24

    fun shortName(name: String): String =
        if (name.length <= NAME_LIMIT) name else name.take(NAME_LIMIT - 1) + "…"

    fun fmt(v: Double): String = QuotaPackage.fmt(v)

    /** 剩余百分比对应的语义色 */
    fun percentColor(percent: Double): Color = when {
        percent <= 10 -> AccountUiColors.danger
        percent <= 30 -> AccountUiColors.warning
        else -> AccountUiColors.success
    }
}

/**
 * 圆角卡片容器：整块绘制背景 + 描边，子组件纵向排列。
 *
 * @param elevated true 时在底部补一条 1px 落影，让卡片从画布里浮起（仅外层卡片使用，
 * 内层嵌套卡片保持平面，避免阴影叠加显脏）
 */
open class RoundedCard(
    private val fill: Color,
    private val stroke: Color,
    arc: Int = AccountUiRadius.card,
    private val elevated: Boolean = false,
) : JPanel() {

    private val arcSize = JBUI.scale(arc)

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** 纵向 BoxLayout 里宽度要撑满，高度按内容 */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = (width - 1).toFloat()
            val h = (height - 1).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, w, h, arcSize.toFloat(), arcSize.toFloat())

            if (elevated) {
                // 底部落影：整体下移 1.5px，只有底边露出，形成轻微浮起感
                g2.color = AccountUiColors.cardShadow
                g2.fill(
                    RoundRectangle2D.Float(
                        1.5f, 2f, (w - 2f).coerceAtLeast(1f), (h - 1.5f).coerceAtLeast(1f),
                        arcSize.toFloat(), arcSize.toFloat()
                    )
                )
            }

            g2.color = fill
            g2.fill(shape)
            g2.color = stroke
            g2.stroke = BasicStroke(1f)
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 可点击整行：hover 时绘制淡底色圆角块，用于看板排行与到期条目。
 *
 * 子组件需自行 add 到 CENTER / WEST / EAST。
 */
open class HoverRow(
    private val radius: Int = AccountUiRadius.inner,
    private val hoverFill: Color = AccountUiColors.cardHoverBg,
) : JPanel(BorderLayout()) {

    var hover: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 6)
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
        if (hover) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = hoverFill
                val a = JBUI.scale(radius).toFloat()
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), a, a))
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
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

/** 轻量进度条（0~100） */
class QuotaBar(percent: Double) : JComponent() {

    var percent: Double = percent.coerceIn(0.0, 100.0)
        set(value) {
            field = value.coerceIn(0.0, 100.0)
            repaint()
        }

    init {
        isOpaque = false
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
            val r = h.toInt()

            g2.color = AccountUiColors.barTrack
            g2.fillRoundRect(0, 0, w.toInt(), h.toInt(), r, r)

            val pct = percent / 100.0
            if (pct <= 0.0) return
            val fw = (w * pct).toInt().coerceIn(1, w.toInt())
            g2.color = QuotaCardUi.percentColor(percent)
            g2.fillRoundRect(0, 0, fw, h.toInt(), r, r)

            // 上半部分叠一层白色微光，避免纯色块显得扁平
            if (fw > 2 && h >= 4f) {
                g2.color = Color(255, 255, 255, 46)
                g2.fillRoundRect(0, 0, fw, (h / 2).toInt(), r, r)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val BAR_HEIGHT = 8
    }
}

/** 圆角小标签 */
class Chip(
    private val text: String,
    private val fill: Color,
    private val fg: Color,
    arc: Int = 8,
) : JComponent() {

    private val arcSize = JBUI.scale(arc)

    init {
        isOpaque = false
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        alignmentY = CENTER_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(12), fm.height + JBUI.scale(6))
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = (width - 1).toFloat()
            val h = (height - 1).toFloat()
            g2.color = fill
            g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, w, h, arcSize.toFloat(), arcSize.toFloat()))
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
    RoundedCard(QuotaCardUi.itemBg, AccountUiColors.divider, AccountUiRadius.inner) {

    init {
        border = JBUI.Borders.empty(6, 8)
        val top = FullWidthRow(BorderLayout(JBUI.scale(6), 0))

        val name = JLabel(QuotaCardUi.shortName(pkg.subProductName.ifBlank { pkg.name })).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textSecondary
            toolTipText = pkg.subProductName.ifBlank { pkg.name }
        }
        val usage = JLabel(pkg.usageText()).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = when {
                pkg.expired -> AccountUiColors.textFaint
                pkg.remain <= 0 -> AccountUiColors.textFaint
                else -> AccountUiColors.success
            }
        }
        top.add(name, BorderLayout.CENTER)
        top.add(usage, BorderLayout.EAST)

        val expireText = when {
            pkg.expired -> "已过期"
            pkg.expiredTime.isNotBlank() -> "到期时间：${pkg.expiredTime.replace('-', '/')}"
            pkg.cycleEnd.isNotBlank() -> "到期时间：${pkg.cycleEnd.replace('-', '/')}"
            else -> "长期有效"
        }
        val expire = JLabel(expireText).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = if (pkg.expired) AccountUiColors.danger else AccountUiColors.textFaint
        }

        add(top)
        add(Box.createVerticalStrut(JBUI.scale(2)))
        add(expire)
    }
}

/**
 * 一个套餐分组卡片：组头（名称 + 条数 + 已用/总量）、进度条、可折叠的细项列表。
 */
class QuotaGroupCard(private val group: QuotaGroup) :
    RoundedCard(AccountUiColors.surfaceBg, AccountUiColors.border, 8) {

    private val itemsPanel = FullWidthColumn().apply { isVisible = false }
    private val arrow = JLabel("▸").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        foreground = AccountUiColors.textMuted
    }
    private val bar = QuotaBar(group.remainPercent)
    private var expanded = false

    init {
        border = JBUI.Borders.empty(8, 10)
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
            font = font.deriveFont(Font.PLAIN, JBUI.scale(9).toFloat())
            foreground = if (group.usableCount > 0) AccountUiColors.success else AccountUiColors.textFaint
        })
        left.add(JLabel(group.name).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
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
        right.add(JLabel("${group.usageText()} ${group.unit}".trim()).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textSecondary
        })
        right.add(JLabel("${"%.0f".format(group.remainPercent)}%").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
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
    stroke = if (active) AccountUiColors.borderActive else AccountUiColors.border,
    arc = 12,
) {

    init {
        border = JBUI.Borders.empty(10, 12)
        add(buildTitle())
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(buildStatus())
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(sectionLabel("配额查询"))
        add(Box.createVerticalStrut(JBUI.scale(6)))

        val brief = summary
        when {
            brief == null -> add(hint("尚未查询额度，点击上方「刷新额度」获取"))
            brief.error != null -> add(hint("查询失败：${brief.error}"))
            brief.packages.isEmpty() -> add(hint("接口未返回任何套餐"))
            else -> {
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
            font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            foreground = AccountUiColors.textPrimary
        })
        if (active) {
            left.add(Chip("当前", AccountUiColors.accent, Color.WHITE))
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

        val percent = summary?.takeIf { it.error == null && it.packages.isNotEmpty() }?.usableRemainPercent
        val right = JLabel(
            if (percent == null) "—" else "剩余 ${"%.0f".format(percent)}%"
        ).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = if (percent == null) AccountUiColors.textFaint else QuotaCardUi.percentColor(percent)
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        return row
    }

    private fun buildStatus(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))

        val label = JLabel("用量状态").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textMuted
        }

        val (statusText, statusColor) = when {
            summary == null -> "未刷新" to AccountUiColors.textFaint
            summary.error != null -> "查询失败" to AccountUiColors.danger
            summary.usableRemain <= 0 -> "已用尽" to AccountUiColors.danger
            summary.usableRemainPercent <= 10 -> "紧张" to AccountUiColors.danger
            summary.usableRemainPercent <= 30 -> "偏低" to AccountUiColors.warning
            else -> "正常" to AccountUiColors.success
        }

        val value = JLabel(statusText).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = statusColor
        }
        row.add(label, BorderLayout.WEST)
        row.add(value, BorderLayout.EAST)
        return row
    }

    private fun sectionLabel(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textMuted
    }

    private fun hint(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(6, 2)
    }
}
