package dev.codebuddy.account.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaGroup
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/** 卡片底部操作 */
enum class GridCardAction { SWITCH, RENAME, REFRESH, EXPORT, DELETE }

/**
 * 网格账号卡片。
 *
 * 布局对应设计稿：
 *  - 标题行：复选框 + 账号（手机号脱敏）+ 等级徽标
 *  - 用量状态：左侧说明、右侧语义色状态
 *  - 配额查询：按套餐分组的条目（名称 + 条数 + 已用/总量 + 进度条 + 折叠箭头）
 *  - 底部：最近刷新时间 + 五个图标操作
 */
class AccountGridCard(
    val snapshot: CredentialSnapshot,
    val isActive: Boolean,
    val quota: QuotaSummary?,
    private val onCheckedChanged: (AccountGridCard, Boolean) -> Unit,
    private val onAction: (AccountGridCard, GridCardAction) -> Unit,
) : RoundedCard(
    fill = if (isActive) AccountUiColors.cardActiveBg else AccountUiColors.cardBg,
    stroke = if (isActive) AccountUiColors.borderActive else AccountUiColors.border,
    arc = AccountUiRadius.card,
    elevated = true,
) {

    private val check = JCheckBox().apply {
        isOpaque = false
        isFocusPainted = false
        alignmentY = CENTER_ALIGNMENT
        toolTipText = "选择该账号"
        addActionListener { onCheckedChanged(this@AccountGridCard, isSelected) }
    }

    private var hover = false

    var checked: Boolean
        get() = check.isSelected
        set(value) {
            if (check.isSelected == value) return
            check.isSelected = value
            repaint()
        }

    init {
        border = JBUI.Borders.empty(12, 14)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                repaint()
            }
        })

        add(buildTitle())
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(buildStatus())
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(sectionLabel("配额查询"))
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(buildPackages())
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(buildFooter())
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!checked && !hover) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(AccountUiRadius.card).toFloat()
            g2.color = if (checked) AccountUiColors.accent else AccountUiColors.borderActive
            g2.stroke = BasicStroke(if (checked) 1.6f else 1f)
            g2.draw(
                RoundRectangle2D.Float(
                    0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc
                )
            )
        } finally {
            g2.dispose()
        }
    }

    // ---------------------------------------------------------------
    // 标题 / 状态
    // ---------------------------------------------------------------

    private fun buildTitle(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply { isOpaque = false }
        left.add(check)
        left.add(JLabel(ellipsize(titleText(), 16)).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            foreground = AccountUiColors.textPrimary
            toolTipText = snapshot.displayName
        })
        if (isActive) {
            left.add(Chip("当前", AccountUiColors.accent, Color.WHITE))
        }

        row.add(left, BorderLayout.WEST)
        row.add(tierChip(), BorderLayout.EAST)
        return row
    }

    private fun tierChip(): Chip {
        val enterprise = snapshot.type.equals("enterprise", true)
        return if (enterprise) {
            Chip("企业", AccountUiColors.tagEnterpriseBg, AccountUiColors.tagEnterpriseFg, arc = 11)
        } else {
            Chip("FREE", AccountUiColors.surfaceBg, AccountUiColors.textMuted, arc = 11)
        }
    }

    private fun buildStatus(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))

        row.add(JLabel("用量状态").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textMuted
        }, BorderLayout.WEST)

        val (statusText, statusColor) = statusOf(quota)
        row.add(JLabel(statusText).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = statusColor
        }, BorderLayout.EAST)
        return row
    }

    /** 用量状态语义：与额度明细保持同一套口径 */
    private fun statusOf(summary: QuotaSummary?): Pair<String, Color> = when {
        summary == null -> "未刷新" to AccountUiColors.textFaint
        summary.error != null -> "查询失败" to AccountUiColors.danger
        summary.packages.isEmpty() -> "无套餐" to AccountUiColors.textMuted
        summary.usableRemain <= 0 -> "已用尽" to AccountUiColors.danger
        summary.usableRemainPercent <= 10 -> "紧张" to AccountUiColors.danger
        summary.usableRemainPercent <= 30 -> "偏低" to AccountUiColors.warning
        else -> "正常" to AccountUiColors.success
    }

    // ---------------------------------------------------------------
    // 配额列表
    // ---------------------------------------------------------------

    private fun buildPackages(): JComponent {
        val column = FullWidthColumn()
        val summary = quota
        when {
            summary == null -> {
                column.add(hint("尚未查询额度"))
                return column
            }

            summary.error != null -> {
                column.add(hint("查询失败：${summary.error}"))
                return column
            }

            summary.packages.isEmpty() -> {
                column.add(hint("接口未返回任何套餐"))
                return column
            }
        }

        val groups = summary.groups()
        for (group in groups.take(MAX_GROUPS)) {
            val strip = QuotaGroupStrip(group)
            strip.alignmentX = Component.LEFT_ALIGNMENT
            column.add(strip)
            column.add(Box.createVerticalStrut(JBUI.scale(6)))
        }
        if (groups.size > MAX_GROUPS) {
            column.add(hint("另有 ${groups.size - MAX_GROUPS} 个套餐分组未展示"))
        }
        return column
    }

    private fun hint(text: String): JLabel = JLabel(QuotaCardUi.shortName(text)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textFaint
        toolTipText = text
        border = JBUI.Borders.empty(2, 2)
    }

    private fun sectionLabel(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textMuted
    }

    // ---------------------------------------------------------------
    // 底部：时间 + 操作
    // ---------------------------------------------------------------

    private fun buildFooter(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        row.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.divider, 1, 0, 0, 0),
            JBUI.Borders.emptyTop(8)
        )

        val timestamp = quota?.fetchedAt?.takeIf { it > 0 } ?: snapshot.importedAt
        val instant = Instant.ofEpochMilli(timestamp)
        val time = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel(DATE_FMT.format(instant)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                foreground = AccountUiColors.textFaint
            })
            add(JLabel(TIME_FMT.format(instant)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                foreground = AccountUiColors.textFaint
            })
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        actions.add(IconTile(AllIcons.Actions.Execute, "切换到此账号") { onAction(this, GridCardAction.SWITCH) })
        actions.add(IconTile(AllIcons.Actions.Edit, "重命名（设置别名）") { onAction(this, GridCardAction.RENAME) })
        actions.add(IconTile(AllIcons.Actions.Refresh, "刷新该账号额度") { onAction(this, GridCardAction.REFRESH) })
        actions.add(IconTile(AllIcons.ToolbarDecorator.Export, "导出该账号凭据") { onAction(this, GridCardAction.EXPORT) })
        actions.add(IconTile(AllIcons.Actions.GC, "从账号库删除") { onAction(this, GridCardAction.DELETE) })

        row.add(time, BorderLayout.WEST)
        row.add(actions, BorderLayout.EAST)
        return row
    }

    // ---------------------------------------------------------------
    // 展示文本
    // ---------------------------------------------------------------

    /** 标题优先昵称/别名；纯数字时按手机号脱敏展示 */
    private fun titleText(): String {
        val name = snapshot.displayName
        if (name.isNotBlank() && name.any { !it.isDigit() }) return name
        val phone = snapshot.phoneNumber.ifBlank { name }
        return maskPhone(phone)
    }

    private fun maskPhone(value: String): String {
        val digits = value.filter { it.isDigit() }
        if (digits.length != 11) return value
        return digits.substring(0, 3) + "****" + digits.substring(7)
    }

    private companion object {
        /** 卡片内最多展示的套餐分组数，超出折叠为提示文本 */
        const val MAX_GROUPS = 2

        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
}

/**
 * 套餐分组条目：左侧语义竖条、名称、条数徽标、已用/总量、进度条，点击折叠细项。
 */
class QuotaGroupStrip(private val group: QuotaGroup) :
    RoundedCard(AccountUiColors.cardBg, AccountUiColors.divider, AccountUiRadius.inner) {

    private val itemsPanel = FullWidthColumn().apply { isVisible = false }
    private val arrow = JLabel("▾").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        foreground = AccountUiColors.textMuted
    }
    private var expanded = false

    init {
        border = JBUI.Borders.empty(6, 8, 6, 12)
        val header = buildHeader()
        val bar = QuotaBar(group.remainPercent)
        add(header)
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(bar)
        add(itemsPanel)

        for (pkg in group.packages.sortedBy { it.expireMillis }) {
            val item = QuotaItemCard(pkg)
            item.alignmentX = Component.LEFT_ALIGNMENT
            itemsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            itemsPanel.add(item)
        }

        installToggle(header)
        installToggle(bar)
    }

    private fun buildHeader(): JComponent {
        val header = FullWidthRow(BorderLayout(JBUI.scale(6), 0))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
        left.add(glyphBadge())
        left.add(JLabel(ellipsize(group.name, 12)).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textPrimary
            toolTipText = group.name
        })
        left.add(Chip("(${group.packages.size})", QuotaCardUi.itemBg, AccountUiColors.textMuted, arc = 8))

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(3), 0)).apply { isOpaque = false }
        right.add(JLabel(formatUsage()).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textSecondary
        })
        right.add(arrow)

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        return header
    }

    /** 分组图标：按套餐名关键字取字与配色，避免依赖系统 emoji 字体 */
    private fun glyphBadge(): GlyphBadge {
        val name = group.name
        val (glyph, bg, fg) = when {
            name.contains("赠") || name.contains("活动") || name.contains("礼") ->
                Triple("赠", JBColor(Color(0xFFF3E2), Color(0x453A1B)), JBColor(Color(0xD97706), Color(0xFBBF24)))

            name.contains("体验") || name.contains("基础") || name.contains("试用") ->
                Triple("基", JBColor(Color(0xE8F1FF), Color(0x1D2E45)), JBColor(Color(0x3B82F6), Color(0x7AB0FF)))

            else -> Triple(
                name.take(1).ifBlank { "包" },
                JBColor(Color(0xF1F3F5), Color(0x35383D)),
                AccountUiColors.textMuted,
            )
        }
        return GlyphBadge(glyph, bg, fg)
    }

    private fun formatUsage(): String = "${fmtNumber(group.used)} / ${fmtNumber(group.size)}"

    private fun fmtNumber(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong())
        else String.format("%,.2f", v)

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
        arrow.text = if (expanded) "▴" else "▾"
        revalidate()
        var p: Container? = parent
        while (p != null) {
            p.revalidate()
            p = p.parent
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val barW = JBUI.scale(3)
            val pad = JBUI.scale(8)
            val barH = (height - pad * 2).coerceAtLeast(JBUI.scale(10))
            g2.color = if (group.usableCount > 0) AccountUiColors.success else AccountUiColors.textFaint
            g2.fill(
                RoundRectangle2D.Float(
                    JBUI.scale(1).toFloat(), pad.toFloat(), barW.toFloat(), barH.toFloat(),
                    barW.toFloat(), barW.toFloat()
                )
            )
        } finally {
            g2.dispose()
        }
    }
}

/** 圆角方形字标（替代 emoji 图标） */
class GlyphBadge(
    private val glyph: String,
    private val fill: Color,
    private val fg: Color,
    size: Int = 20,
) : JComponent() {

    private val box: Int = JBUI.scale(size)
    private val arcSize: Int = JBUI.scale(6)

    init {
        isOpaque = false
        font = baseLabelFont().deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        preferredSize = Dimension(box, box)
        minimumSize = preferredSize
        maximumSize = preferredSize
        alignmentY = CENTER_ALIGNMENT
    }

    /** JComponent 默认无字体，兜底后再交给绘制 */
    override fun getFont(): Font = super.getFont() ?: baseLabelFont()

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = fill
            g2.fill(
                RoundRectangle2D.Float(
                    0f, 0f, box.toFloat(), box.toFloat(), arcSize.toFloat(), arcSize.toFloat()
                )
            )
            g2.font = font
            g2.color = fg
            val fm = g2.fontMetrics
            g2.drawString(glyph, (box - fm.stringWidth(glyph)) / 2, (box - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** 方形图标按钮：浅底圆角，hover 加深，用于卡片底部操作 */
class IconTile(
    private val icon: Icon,
    private val tip: String,
    private val onClick: () -> Unit,
) : JComponent() {

    private val side: Int = JBUI.scale(26)
    private val arcSize: Int = JBUI.scale(8)
    private var hover = false

    init {
        isOpaque = false
        toolTipText = tip
        preferredSize = Dimension(side, side)
        minimumSize = preferredSize
        maximumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (isEnabled) onClick()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (hover) AccountUiColors.cardHoverBg else AccountUiColors.surfaceBg
            g2.fill(
                RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(), arcSize.toFloat(), arcSize.toFloat()
                )
            )
            icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}

/** 卡片宽度有限，超长文本按字符截断（完整内容走 tooltip） */
private fun ellipsize(text: String, limit: Int): String =
    if (text.length <= limit) text else text.take(limit - 1) + "…"
