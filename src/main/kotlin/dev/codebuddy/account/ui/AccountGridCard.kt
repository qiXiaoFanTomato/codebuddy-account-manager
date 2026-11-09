package dev.codebuddy.account.ui

import com.intellij.icons.AllIcons
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
enum class GridCardAction { SWITCH, DETAIL, RENAME, REFRESH, EXPORT, DELETE, GROWTH }

/**
 * 网格账号卡片（档案柜版）。
 *
 * 布局对应 design/plugin-ui.html 的 `.account-card`：
 *  - 左上角墨蓝 tab「当前激活」；
 *  - 标题行：42px 直角头像 + 账号名 + 个人/企业、域名、Token 标签 + 旋转朱印「当前」；
 *  - 档案号行：UID / 手机号 / 到期，等宽小字；
 *  - 黄铜读数：剩余额度 / 总量 + 刻度进度条 + 已用明细；
 *  - 底部虚线：snapshots 文件路径 + 动作条。
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

    private val check = CardCheckBox().apply {
        alignmentY = CENTER_ALIGNMENT
        toolTipText = "选择该账号"
        addActionListener { onCheckedChanged(this@AccountGridCard, isSelected) }
    }

    var checked: Boolean
        get() = check.isSelected
        set(value) {
            if (check.isSelected == value) return
            check.isSelected = value
            // 选中态用主色描边，与卡片果冻描边统一
            accentStroke = if (value) AccountUiColors.accent else null
            strokeWidth = if (value) 1.8f else 1f
            repaint()
        }

    init {
        // 顶部留出索引 tab 的高度，内容不会被墨蓝标签压住
        border = JBUI.Borders.empty(
            AccountUiSpacing.cardPadV + tabHeightDp + 6,
            AccountUiSpacing.cardPadH,
            AccountUiSpacing.cardPadV,
            AccountUiSpacing.cardPadH,
        )
        tabText = if (isActive) "当前激活" else null
        if (isActive) {
            accentStroke = AccountUiColors.ink
            strokeWidth = 1.6f
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHovering(true)

            override fun mouseExited(e: MouseEvent) = setHovering(false)
        })

        add(buildTitle())
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.blockGap)))
        add(buildMeta())
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.md)))
        add(buildQuotaBlock())
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.md)))
        add(buildPackages())
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.md)))
        add(buildFooter())
    }

    // ---------------------------------------------------------------
    // 标题 / 状态
    // ---------------------------------------------------------------

    private fun buildTitle(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(10), 0))

        // 头像区固定宽度：横向 BoxLayout 不换行，勾选框与 42px 头像始终同一行
        val head = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentY = Component.CENTER_ALIGNMENT
        }
        head.add(check)
        head.add(Box.createHorizontalStrut(JBUI.scale(9)))
        head.add(AvatarBadge(titleText()))

        // 名称在上、标签在下（对应 HTML 的 .acct-id）。
        // 名称列放 CENTER 吃剩余宽度：名字按宽度省略、标签按宽度换行，
        // 不会像 FlowLayout 那样把整列折到头像下一行。
        val textCol = FullWidthColumn()
        textCol.add(AccountNameLabel(titleText()).apply {
            foreground = AccountUiColors.textPrimary
        })
        textCol.add(Box.createVerticalStrut(JBUI.scale(4)))

        val tags = JPanel(FlowWrapLayout(JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        tags.add(tierChip())
        tags.add(
            Chip(
                snapshot.domainHost.ifBlank { "—" },
                AccountUiColors.tagDomainBg,
                AccountUiColors.tagDomainFg,
            )
        )
        val tokenOk = !snapshot.isRefreshTokenExpired
        tags.add(
            Chip(
                if (tokenOk) "Token 有效" else "Token 过期",
                if (tokenOk) AccountUiColors.tagSuccessBg else AccountUiColors.sealSoft,
                if (tokenOk) AccountUiColors.success else AccountUiColors.seal,
            )
        )
        textCol.add(tags)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        if (isActive) right.add(SealStamp())

        row.add(head, BorderLayout.WEST)
        row.add(textCol, BorderLayout.CENTER)
        row.add(right, BorderLayout.EAST)
        return row
    }

    /**
     * 档案号行：UID / 手机号 / 到期三行，标签左、值右。
     *
     * 对应 plugin-ui.html 的 `.acct-meta`（`dt`/`dd` 两列）；认证域已由上方的
     * 域名标签承担，这里只补 HTML 里有的到期时间，临期用朱印标出。
     */
    private fun buildMeta(): JComponent {
        val column = FullWidthColumn()
        val uid = snapshot.uid.ifBlank { "—" }
        column.add(
            AccountUiKit.fieldLine(
                "UID",
                AccountUiKit.valueText(uid, AccountUiColors.textPrimary).apply {
                    // 完整 UID 用一级小字，保证不截断且不与左侧标签相撞
                    font = AccountUiType.forText(uid, AccountUiType.micro, Font.PLAIN)
                    toolTipText = uid
                },
            )
        )
        val phone = snapshot.phoneNumber.ifBlank { "—" }
        column.add(
            AccountUiKit.fieldLine(
                "手机号",
                AccountUiKit.valueText(phone, AccountUiColors.textPrimary).apply { toolTipText = phone },
            )
        )
        column.add(AccountUiKit.fieldLine("到期", expiryText()))
        return column
    }

    /** 最近到期时间：取可用套餐里最早的一条，7 天内标「临期」 */
    private fun expiryText(): JComponent {
        val nearest = quota
            ?.takeIf { it.error == null }
            ?.usablePackages
            ?.asSequence()
            ?.filter { it.expireMillis != Long.MAX_VALUE && it.expireMillis > 0L }
            ?.minOfOrNull { it.expireMillis }

        if (nearest == null) return AccountUiKit.valueText("—", AccountUiColors.textFaint)

        val soon = nearest - System.currentTimeMillis() <= SOON_MS
        val text = DATE_FMT.format(Instant.ofEpochMilli(nearest)) + if (soon) " · 临期" else ""
        return AccountUiKit.valueText(
            text,
            if (soon) AccountUiColors.seal else AccountUiColors.textSecondary,
        )
    }

    private fun tierChip(): Chip {
        val enterprise = snapshot.type.equals("enterprise", true)
        return if (enterprise) {
            Chip("企业", AccountUiColors.tagEnterpriseBg, AccountUiColors.tagEnterpriseFg)
        } else {
            Chip("个人", AccountUiColors.tagPersonalBg, AccountUiColors.tagPersonalFg)
        }
    }

    /**
     * 额度主视觉：大号剩余百分比 + 全宽进度条 + 用量明细。
     *
     * 卡片以数字为主角，套餐分组退居次级信息。
     */
    private fun buildQuotaBlock(): JComponent {
        val column = FullWidthColumn()
        val summary = quota

        if (summary == null) {
            column.add(hint("尚未查询额度"))
            return column
        }
        if (summary.error != null) {
            column.add(hint("查询失败：${summary.error}"))
            return column
        }
        if (summary.packages.isEmpty()) {
            column.add(hint("接口未返回任何套餐"))
            return column
        }

        val percent = summary.usableRemainPercent
        val status = QuotaStatus.of(summary)

        // 黄铜读数：剩余额度 / 总量 credits（对应 .reading-num + .reading-total）
        val reading = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false }
        reading.add(JLabel(fmtNumber(summary.usableRemain)).apply {
            font = AccountUiType.mono(AccountUiType.display)
            foreground = if (percent <= 20.0) AccountUiColors.seal else AccountUiColors.brass
        })
        reading.add(
            JLabel("/ ${fmtNumber(summary.usableSize)} ${summary.unit.ifBlank { "credits" }}").apply {
                font = AccountUiType.mono(AccountUiType.label, Font.PLAIN)
                foreground = AccountUiColors.textMuted
            }
        )

        val numberRow = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        numberRow.add(reading, BorderLayout.WEST)
        numberRow.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(AccountUiKit.statusTag(status.text, status.color))
            },
            BorderLayout.EAST
        )
        column.add(numberRow)

        val fetchedAt = summary.fetchedAt.takeIf { it > 0 } ?: snapshot.importedAt
        column.add(
            JLabel("剩余额度 ${"%.0f".format(percent)}% · 刷新于 ${TIME_FMT.format(Instant.ofEpochMilli(fetchedAt))}").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = AccountUiColors.textSecondary
            }
        )
        column.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xs)))
        column.add(QuotaBar(percent))
        column.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xs)))
        // 已用 / 总量：标签左、数值右，多张卡片之间右边缘对齐
        column.add(
            AccountUiKit.fieldLine(
                "已用 / 总量",
                AccountUiKit.valueText(
                    "${fmtNumber(summary.usableUsed)} / ${fmtNumber(summary.usableSize)}" +
                        if (summary.unit.isBlank()) "" else " ${summary.unit}",
                    AccountUiColors.textSecondary,
                ),
            )
        )
        return column
    }

    /** 千分位数字 */
    private fun fmtNumber(v: Double): String = AccountUiKit.formatNumber(v)

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
        font = font.deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = AccountUiColors.textFaint
        toolTipText = text
        border = JBUI.Borders.empty(2, 2)
    }

    // ---------------------------------------------------------------
    // 底部：时间 + 操作
    // ---------------------------------------------------------------

    private fun buildFooter(): JComponent {
        val column = FullWidthColumn()
        // 虚线分隔 + 档案号 + 动作（对应 .card-foot / .acct-foot 的 dashed border）
        column.add(DashedRule())
        column.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))

        val file = "snapshots/${snapshot.uid.take(8)}@${snapshot.domainHost.ifBlank { "codebuddy.cn" }}.info"
        val fileNo = JLabel(file).apply {
            font = AccountUiType.mono(AccountUiType.micro, Font.PLAIN)
            foreground = AccountUiColors.textMuted
            toolTipText = file
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentY = CENTER_ALIGNMENT
        }
        actions.add(
            JellyButton("切换", JellyButtonVariant.GHOST, null, "切换到此账号") {
                onAction(this@AccountGridCard, GridCardAction.SWITCH)
            }.apply { emphasized = true }
        )
        // 与 HTML `.acct-foot` 一致：切换之后紧跟「额度明细」文字动作
        actions.add(
            JellyButton("额度明细", JellyButtonVariant.GHOST, null, "打开额度明细窗口") {
                onAction(this@AccountGridCard, GridCardAction.DETAIL)
            }
        )
        actions.add(IconTile(AllIcons.General.InspectionsOK, "一键完成该账号的成长任务") { onAction(this, GridCardAction.GROWTH) })
        actions.add(IconTile(AllIcons.Actions.Edit, "重命名（设置别名）") { onAction(this, GridCardAction.RENAME) })
        actions.add(IconTile(AllIcons.Actions.Refresh, "刷新该账号额度") { onAction(this, GridCardAction.REFRESH) })
        actions.add(IconTile(AllIcons.ToolbarDecorator.Export, "导出该账号凭据") { onAction(this, GridCardAction.EXPORT) })
        actions.add(IconTile(AllIcons.Actions.GC, "从账号库删除") { onAction(this, GridCardAction.DELETE) })

        // 档案号与动作分两行，卡片窄时不会互相挤压
        val actionRow = FullWidthRow(BorderLayout(0, 0))
        actionRow.add(actions, BorderLayout.EAST)
        column.add(fileNo)
        column.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xs)))
        column.add(actionRow)
        return column
    }

    // ---------------------------------------------------------------
    // 展示文本
    // ---------------------------------------------------------------

    /** 标题展示账号名原文，不再做脱敏 */
    private fun titleText(): String = snapshot.displayName

    private companion object {
        /** 卡片内最多展示的套餐分组数，超出折叠为提示文本 */
        const val MAX_GROUPS = 2

        /** 临期判定窗口：7 天（与看板「7 天内到期」指标同一口径） */
        const val SOON_MS = 7L * 24 * 60 * 60 * 1000

        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
}

/**
 * 套餐分组条目：左侧语义竖条、名称、条数徽标、已用/总量、进度条，点击折叠细项。
 */
class QuotaGroupStrip(private val group: QuotaGroup) :
    RoundedCard(
        AccountUiColors.cardBg, AccountUiColors.divider, AccountUiRadius.inner,
        material = JellyMaterial.SOFT,
    ) {

    private val itemsPanel = FullWidthColumn().apply { isVisible = false }
    private val arrow = JLabel("▸").apply {
        font = font.deriveFont(Font.PLAIN, AccountUiType.caption)
        foreground = AccountUiColors.textMuted
    }
    private var expanded = false

    init {
        border = JBUI.Borders.empty(AccountUiSpacing.blockGap, AccountUiSpacing.md, AccountUiSpacing.blockGap, AccountUiSpacing.lg)
        val header = buildHeader()
        val bar = QuotaBar(group.remainPercent)
        add(header)
        add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))
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
            font = font.deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textPrimary
            toolTipText = group.name
        })
        left.add(Chip("(${group.packages.size})", AccountUiColors.itemBg, AccountUiColors.textMuted))

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(3), 0)).apply { isOpaque = false }
        right.add(JLabel(formatUsage()).apply {
            font = font.deriveFont(Font.PLAIN, AccountUiType.label)
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
                Triple("赠", AccountUiColors.glyphGiftBg, AccountUiColors.glyphGiftFg)

            name.contains("体验") || name.contains("基础") || name.contains("试用") ->
                Triple("基", AccountUiColors.glyphBaseBg, AccountUiColors.glyphBaseFg)

            else -> Triple(
                name.take(1).ifBlank { "包" },
                AccountUiColors.glyphPlainBg,
                AccountUiColors.glyphPlainFg,
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
        arrow.text = if (expanded) "▾" else "▸"
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
        font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.caption)
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
            Jelly.textHints(g2)
            g2.color = fill
            g2.fill(
                RoundRectangle2D.Float(
                    0f, 0f, box.toFloat(), box.toFloat(), arcSize.toFloat(), arcSize.toFloat()
                )
            )
            // 果冻高光：字标同样带一点顶部反光
            g2.color = Color(255, 255, 255, 40)
            g2.fill(
                RoundRectangle2D.Float(
                    JBUI.scale(2).toFloat(), JBUI.scale(2).toFloat(),
                    (box - JBUI.scale(4)).toFloat(), (box * 0.36f).toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
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

/**
 * 图标按钮：24×24 圆角 6，hover 只换底色，不缩放、不下沉。
 *
 * 是卡片底部操作与工具栏图标键的统一实现；键盘 Space/Enter 可触发。
 */
class IconTile(
    private val icon: Icon,
    private val tip: String,
    private val onClick: () -> Unit,
) : JComponent() {

    private val side: Int = JBUI.scale(24)

    /** hover 弹起动画进度 */
    private val lift = JellyValue(this, 0.0)

    /** 按下进度：1 表示按住，胶囊下沉 1px */
    private val press = JellyValue(this, 0.0)

    init {
        isOpaque = false
        toolTipText = tip
        preferredSize = Dimension(side, side)
        minimumSize = preferredSize
        maximumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = true

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                lift.animateTo(1.0, 170, { t -> Easing.outBack(t) })
            }

            override fun mouseExited(e: MouseEvent) {
                lift.animateTo(0.0, 220, { t -> Easing.outCubic(t) })
                press.animateTo(0.0, 200, { t -> Easing.outBack(t) })
            }

            override fun mousePressed(e: MouseEvent) {
                if (isEnabled) press.animateTo(1.0, 80)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!isEnabled) return
                press.animateTo(0.0, 260, { t -> Easing.outBack(t) })
                if (contains(e.point)) fire()
            }
        })
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
        })
        inputMap.put(KeyStroke.getKeyStroke("SPACE"), "activate")
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "activate")
        actionMap.put(
            "activate",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    if (isEnabled) fire()
                }
            }
        )
    }

    private fun fire() = onClick()

    /** 禁用态重绘：图标键在批量操作期间会被临时置灰 */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val liftValue = lift.value.toFloat()
            val alpha = if (isEnabled) 1f else 0.4f
            val arc = JBUI.scale(AccountUiRadius.chip).toFloat()
            val shape = Jelly.round(0.5f, 0.5f, width - 1f, height - 1f, arc)
            if (liftValue > 0.02f) {
                g2.color = Jelly.alpha(AccountUiColors.surfaceHi, liftValue * alpha)
                g2.fill(shape)
                g2.color = Jelly.alpha(AccountUiColors.border, liftValue * alpha)
                g2.stroke = java.awt.BasicStroke(1f)
                g2.draw(shape)
            }
            icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
            Jelly.paintFocusRing(g2, shape, isFocusOwner)
        } finally {
            g2.dispose()
        }
    }
}

/** 42px 直角头像：取账号首字，配色池对应设计稿的 .av-1 ~ .av-4 */
private class AvatarBadge(name: String) : JComponent() {

    private val side = JBUI.scale(42)
    private val glyph = name.take(1).ifBlank { "?" }
    private val fill = AccountUiColors.avatarPalette[
        Math.floorMod(name.hashCode(), AccountUiColors.avatarPalette.size)
    ]

    init {
        isOpaque = false
        preferredSize = Dimension(side, side)
        minimumSize = preferredSize
        maximumSize = preferredSize
        alignmentY = CENTER_ALIGNMENT
        font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.number)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            g2.color = fill
            g2.fill(java.awt.geom.Rectangle2D.Float(0f, 0f, side.toFloat(), side.toFloat()))
            g2.font = font
            g2.color = Color.WHITE
            val fm = g2.fontMetrics
            g2.drawString(
                glyph,
                Jelly.textX(side, fm.stringWidth(glyph)),
                Jelly.textBaseline(side, fm),
            )
        } finally {
            g2.dispose()
        }
    }
}

/** 朱印「当前」：2px 朱红描边 + -7° 旋转，对应 HTML 的 `.seal` */
private class SealStamp : JComponent() {

    private val w = JBUI.scale(48)
    private val h = JBUI.scale(30)

    init {
        isOpaque = false
        preferredSize = Dimension(w, h)
        minimumSize = preferredSize
        maximumSize = preferredSize
        alignmentY = CENTER_ALIGNMENT
        font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.label)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            g2.translate(width / 2.0, height / 2.0)
            g2.rotate(Math.toRadians(-7.0))
            g2.translate(-width / 2.0, -height / 2.0)
            val stamp = java.awt.geom.Rectangle2D.Float(2f, 2f, (width - 4).toFloat(), (height - 4).toFloat())
            // 淡朱底衬，保证朱印文字在档案纸面上始终清晰
            g2.color = AccountUiColors.sealSoft
            g2.fill(stamp)
            g2.color = AccountUiColors.seal
            g2.stroke = BasicStroke(2f)
            g2.draw(stamp)
            g2.font = font
            val fm = g2.fontMetrics
            g2.drawString(
                "当前",
                Jelly.textX(width, fm.stringWidth("当前")),
                Jelly.textBaseline(height, fm),
            )
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 卡片勾选框：固定浅色自绘。
 *
 * 原生 JCheckBox 在 IDE 深色主题下使用深底图标，与固定浅色的档案纸卡片冲突，
 * 因此这里只画未选中描边与选中勾选，保留 JCheckBox 的键盘与选择语义。
 */
private class CardCheckBox : JCheckBox() {

    private val side = JBUI.scale(16)

    init {
        isOpaque = false
        isFocusPainted = false
        border = null
        preferredSize = Dimension(side, side)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val shape = Jelly.round(
                0.5f, 0.5f, (side - 1).toFloat(), (side - 1).toFloat(),
                JBUI.scale(4).toFloat(),
            )
            if (isSelected) {
                g2.color = AccountUiColors.accent
                g2.fill(shape)
                g2.color = AccountUiColors.onInk
                g2.stroke = BasicStroke(2f)
                g2.drawPolyline(
                    intArrayOf(JBUI.scale(4), JBUI.scale(7), JBUI.scale(12)),
                    intArrayOf(JBUI.scale(8), JBUI.scale(11), JBUI.scale(5)),
                    3,
                )
            } else {
                g2.color = AccountUiColors.textMuted
                g2.stroke = BasicStroke(1.2f)
                g2.draw(shape)
            }
            Jelly.paintFocusRing(g2, shape, isFocusOwner)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 卡片标题里的账号名：按父容器给定宽度做尾部省略，完整内容走 tooltip。
 *
 * 名字是 22px 粗体，长手机号会把整列撑出标题行；这里不参与宽度争抢，
 * 最小宽度取 0，由 BorderLayout.CENTER 决定实际宽度后自绘。
 */
private class AccountNameLabel(private val raw: String) : JComponent() {

    init {
        isOpaque = false
        font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.number)
        alignmentX = Component.LEFT_ALIGNMENT
        toolTipText = raw
    }

    private fun visibleText(available: Int): String {
        val fm = getFontMetrics(font)
        if (available <= 0 || fm.stringWidth(raw) <= available) return raw
        var end = raw.length
        while (end > 0 && fm.stringWidth(raw.take(end) + "…") > available) end--
        return if (end > 0) raw.take(end) + "…" else ""
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(raw), fm.height)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            g2.font = font
            g2.color = foreground
            val fm = g2.fontMetrics
            g2.drawString(visibleText(width), 0, fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** 卡片宽度有限，超长文本按字符截断（完整内容走 tooltip） */
private fun ellipsize(text: String, limit: Int): String =
    if (text.length <= limit) text else text.take(limit - 1) + "…"
