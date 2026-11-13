package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.usage.DailyUsage
import dev.codebuddy.account.usage.UsageRecord
import dev.codebuddy.account.usage.UsageStats
import dev.codebuddy.account.usage.formatCreditThousands
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** 复刻页面的绿色主色（折线 / 选中态） */
private val STATS_GREEN: Color = Color(0x5FAF73)
private val STATS_CARD_BORDER: Color = Color(0xE5E8EA)
private val STATS_PLOT_BG: Color = Color(0xF7F8F8)
private val STATS_CHIP_BG: Color = Color(0xEEF0F1)

/**
 * 用量统计窗口：1:1 复刻 WorkDaddy「用量统计」页。
 *
 * 结构：
 *  - 顶部「Token / 积分」口径 tab（Token 目前接口未提供，页内如实提示）；
 *  - 时间范围：今天 / 近 7 天 / 近 30 天 / 近 90 天；
 *  - 两张指标卡：积分消耗、调用次数；
 *  - 每日趋势：总览 / 按账号，折线图按天聚合（无记录的天补 0）；
 *  - 账号用量：按积分降序，右侧给数值与调用次数。
 *
 * 与「消耗明细」窗口的分工：那个窗口是**请求级明细**，这里是**聚合统计**，两者粒度不同。
 * 数据全部来自 `AccountLibrary.fetchUsageAll`（按账号翻页拉全）。
 */
class UsageStatsDialog(private val project: Project) : DialogWrapper(project) {

    private val library = AccountLibrary.getInstance()

    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AccountUiColors.panelBg
        border = JBUI.Borders.empty(16, 20)
    }

    private val statusLabel = JLabel("正在读取…").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** 账号 key → 展示名 */
    private var accountNames = LinkedHashMap<String, String>()

    /** 账号 key → 该账号在时间范围内的记录 */
    private var recordsByAccount = LinkedHashMap<String, List<UsageRecord>>()

    private var failedAccounts: List<String> = emptyList()
    private var truncatedAccounts = 0
    private var loading = false
    private var loadSeq = 0

    private var metric: Metric = Metric.CREDIT
    private var range: RangeChoice = RANGES[1]
    private var trendMode: TrendMode = TrendMode.TOTAL
    private var selectedAccountKey: String? = null

    init {
        title = "用量统计"
        setCancelButtonText("关闭")
        setSize(900, 700)
        init()
        load()
    }

    override fun createCenterPanel(): JComponent {
        val scroll = AccountUiKit.styleScroll(JBScrollPane(content), AccountUiColors.panelBg).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            preferredSize = Dimension(JBUI.scale(860), JBUI.scale(620))
        }
        return scroll
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    // ---------------------------------------------------------------
    // 数据
    // ---------------------------------------------------------------

    private fun load() {
        val seq = ++loadSeq
        loading = true
        render()
        status("正在读取 ${range.label} 的用量…")

        val saved = library.listSaved()
        if (saved.isEmpty()) {
            loading = false
            render()
            status("账号库为空，请先在面板中导入账号")
            return
        }
        val currentRange = range
        ApplicationManager.getApplication().executeOnPooledThread {
            val names = LinkedHashMap<String, String>()
            val byAccount = LinkedHashMap<String, List<UsageRecord>>()
            val errors = ArrayList<String>()
            var truncated = 0

            for ((index, snapshot) in saved.withIndex()) {
                if (seq != loadSeq) return@executeOnPooledThread
                names[snapshot.accountKey] = snapshot.displayName
                onEdt { status("正在读取 ${index + 1}/${saved.size} 个账号…") }
                val page = library.fetchUsageAll(
                    snapshot.accountKey,
                    currentRange.startTime(),
                    currentRange.endTime(),
                )
                if (page.error != null) {
                    errors.add("${snapshot.displayName}：${page.error}")
                    continue
                }
                byAccount[snapshot.accountKey] = page.records
                if (page.truncated) truncated++
            }

            onEdt {
                if (seq != loadSeq) return@onEdt
                loading = false
                accountNames = names
                recordsByAccount = byAccount
                failedAccounts = errors
                truncatedAccounts = truncated
                selectedAccountKey = selectedAccountKey?.takeIf { byAccount.containsKey(it) }
                    ?: byAccount.keys.firstOrNull()
                render()
                status(
                    "已统计 ${byAccount.size} 个账号 · ${allRecords().size} 次调用 · " +
                        "${formatCreditThousands(totalCredit())} 积分"
                )
            }
        }
    }

    /** 全部账号合并后的记录 */
    private fun allRecords(): List<UsageRecord> = recordsByAccount.values.flatten()

    private fun totalCredit(): Double = allRecords().sumOf { it.credit }

    private fun totalCount(): Int = allRecords().size

    private fun dailyPoints(): List<DailyUsage> {
        val records = if (trendMode == TrendMode.ACCOUNT) {
            selectedAccountKey?.let { recordsByAccount[it] } ?: emptyList()
        } else {
            allRecords()
        }
        return UsageStats.byDay(records, range.from(), range.to())
    }

    // ---------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------

    private fun render() {
        content.removeAll()

        content.add(header())
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(metricTabs())
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
        content.add(timeRow())
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
        content.add(metricCards())
        content.add(Box.createVerticalStrut(JBUI.scale(14)))
        content.add(trendCard())
        content.add(Box.createVerticalStrut(JBUI.scale(14)))
        content.add(accountUsageCard())
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
        content.add(statusLabel)
        content.add(Box.createVerticalGlue())

        content.revalidate()
        content.repaint()
    }

    /** 页头：标题 + 「Token / 积分」口径 tab（对应截图顶部两段式） */
    private fun header(): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
        }
        row.add(
            JLabel("用量统计").apply {
                font = AccountUiType.ui(AccountUiType.scaled(20), Font.BOLD)
                foreground = AccountUiColors.textPrimary
            },
            BorderLayout.WEST,
        )
        return row
    }

    private fun metricTabs(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(18), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
        }
        for (m in Metric.entries) {
            row.add(
                UnderlineTab(m.label, metric == m) {
                    metric = m
                    render()
                }
            )
        }
        return row
    }

    private fun timeRow(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
        }
        row.add(
            JLabel("时间").apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = AccountUiColors.textSecondary
                border = JBUI.Borders.emptyRight(4)
            }
        )
        for (r in RANGES) {
            row.add(
                TimeChip(r.label, r == range) {
                    if (range == r) return@TimeChip
                    range = r
                    load()
                }
            )
        }
        return row
    }

    private fun metricCards(): JComponent {
        val row = JPanel(GridLayout(1, 2, JBUI.scale(10), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(86))
        }
        val creditText = when {
            metric == Metric.TOKEN -> "—"
            loading -> "…"
            else -> formatCreditThousands(totalCredit())
        }
        val countText = when {
            metric == Metric.TOKEN -> "—"
            loading -> "…"
            else -> totalCount().toString()
        }
        row.add(MetricCard(if (metric == Metric.TOKEN) "Token 消耗" else "积分消耗", creditText))
        row.add(MetricCard("调用", countText))
        return row
    }

    private fun trendCard(): JComponent {
        val card = StatsCard()
        val head = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }
        head.add(
            JLabel("每日趋势").apply {
                font = AccountUiType.ui(AccountUiType.title, Font.BOLD)
                foreground = AccountUiColors.textPrimary
            },
            BorderLayout.WEST,
        )
        val modes = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        for (m in TrendMode.entries) {
            modes.add(
                SmallToggle(m.label, trendMode == m) {
                    trendMode = m
                    render()
                }
            )
        }
        head.add(modes, BorderLayout.EAST)
        card.add(head)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        if (trendMode == TrendMode.ACCOUNT) {
            val keys = recordsByAccount.keys.toList()
            val labels = keys.map { accountNames[it] ?: it }
            val combo = JComboBox(labels.toTypedArray()).apply {
                selectedIndex = keys.indexOf(selectedAccountKey).coerceAtLeast(0)
                preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            }
            combo.addActionListener {
                val i = combo.selectedIndex
                if (i in keys.indices) {
                    selectedAccountKey = keys[i]
                    render()
                }
            }
            val comboRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            comboRow.add(AccountUiKit.styleCombo(combo) { it?.toString().orEmpty() })
            card.add(comboRow)
            card.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        if (metric == Metric.TOKEN) {
            card.add(
                JLabel("当前接口未提供 token 统计，只能查看积分口径。").apply {
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
            return card
        }

        val points = dailyPoints()
        val chart = TrendChart()
        chart.setPoints(points)
        chart.alignmentX = Component.LEFT_ALIGNMENT
        card.add(chart)
        return card
    }

    private fun accountUsageCard(): JComponent {
        val card = StatsCard()
        val head = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }
        head.add(
            JLabel("账号用量").apply {
                font = AccountUiType.ui(AccountUiType.title, Font.BOLD)
                foreground = AccountUiColors.textPrimary
            },
            BorderLayout.WEST,
        )
        card.add(head)
        card.add(Box.createVerticalStrut(JBUI.scale(6)))

        if (recordsByAccount.isEmpty()) {
            card.add(hint(if (loading) "正在读取…" else "该时间范围内没有消耗记录"))
            return card
        }

        val rows = recordsByAccount.entries
            .map { (key, records) ->
                Triple(accountNames[key] ?: key, records.sumOf { it.credit }, records.size)
            }
            .sortedByDescending { it.second }

        var first = true
        for ((name, credit, count) in rows) {
            if (!first) card.add(HairLine())
            first = false
            card.add(AccountUsageRow(name, if (metric == Metric.TOKEN) "—" else formatCreditThousands(credit), count))
        }
        return card
    }

    private fun hint(text: String): JComponent = JLabel(text).apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun status(text: String) {
        statusLabel.text = text
        if (!loading && failedAccounts.isNotEmpty()) {
            statusLabel.text = text + "　|　${failedAccounts.size} 个账号读取失败（${failedAccounts.first()}）"
        } else if (!loading && truncatedAccounts > 0) {
            statusLabel.text = text + "　|　$truncatedAccounts 个账号超过单次拉取上限，统计偏低"
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any())
    }

    // ---------------------------------------------------------------
    // 局部组件
    // ---------------------------------------------------------------

    private enum class Metric(val label: String) {
        TOKEN("Token"),
        CREDIT("积分"),
    }

    private enum class TrendMode(val label: String) {
        TOTAL("总览"),
        ACCOUNT("按账号"),
    }

    /** 时间范围：days = 1 表示今天（含今天共 days 天） */
    private class RangeChoice(val label: String, private val days: Int) {
        fun from(): LocalDate = LocalDate.now().minusDays((days - 1).toLong())
        fun to(): LocalDate = LocalDate.now()
        fun startTime(): String = from().toString() + " 00:00:00"
        fun endTime(): String = to().toString() + " 23:59:59"
    }

    /** 白色圆角卡片 */
    private class StatsCard : JPanel() {
        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(14, 16)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val shape = Jelly.round(
                    0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(),
                    JBUI.scale(8).toFloat(),
                )
                g2.color = AccountUiColors.cardBg
                g2.fill(shape)
                g2.color = STATS_CARD_BORDER
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** 下划线口径 tab（Token / 积分） */
    private class UnderlineTab(text: String, selected: Boolean, onClick: () -> Unit) : JComponent() {
        private val label = text
        private val isSelected = selected
        private var hovering = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = AccountUiType.ui(AccountUiType.label, if (selected) Font.BOLD else Font.PLAIN)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovering = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovering = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onClick()
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            return Dimension(fm.stringWidth(label) + JBUI.scale(4), fm.height + JBUI.scale(12))
        }

        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                Jelly.textHints(g2)
                g2.font = font
                g2.color = when {
                    isSelected -> AccountUiColors.textPrimary
                    hovering -> AccountUiColors.textSecondary
                    else -> AccountUiColors.textMuted
                }
                val fm = g2.fontMetrics
                val tx = (width - fm.stringWidth(label)) / 2
                val ty = JBUI.scale(2) + fm.ascent
                g2.drawString(label, tx, ty)
                if (isSelected) {
                    g2.color = STATS_GREEN
                    g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                    g2.drawLine(tx, height - JBUI.scale(3), tx + fm.stringWidth(label), height - JBUI.scale(3))
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** 时间范围胶囊 */
    private class TimeChip(text: String, selected: Boolean, onClick: () -> Unit) : JComponent() {
        private val label = text
        private val isSelected = selected
        private var hovering = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = AccountUiType.ui(AccountUiType.caption, if (selected) Font.BOLD else Font.PLAIN)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovering = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovering = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onClick()
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            return Dimension(fm.stringWidth(label) + JBUI.scale(20), fm.height + JBUI.scale(8))
        }

        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val shape = Jelly.round(0f, 0f, width.toFloat(), height.toFloat(), height / 2f)
                g2.color = when {
                    isSelected -> STATS_CHIP_BG
                    hovering -> STATS_CHIP_BG
                    else -> AccountUiColors.cardBg
                }
                g2.fill(shape)
                if (isSelected) {
                    g2.color = STATS_CARD_BORDER
                    g2.draw(shape)
                }
                g2.font = font
                g2.color = if (isSelected) AccountUiColors.textPrimary else AccountUiColors.textSecondary
                val fm = g2.fontMetrics
                g2.drawString(label, (width - fm.stringWidth(label)) / 2, (height - fm.height) / 2 + fm.ascent)
            } finally {
                g2.dispose()
            }
        }
    }

    /** 指标卡：标签 + 大号数值 */
    private class MetricCard(label: String, value: String) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(12, 14)
            add(
                JLabel(label).apply {
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                },
                BorderLayout.NORTH,
            )
            add(
                JLabel(value).apply {
                    font = AccountUiType.ui(AccountUiType.scaled(22), Font.BOLD)
                    foreground = AccountUiColors.textPrimary
                    border = JBUI.Borders.emptyTop(4)
                },
                BorderLayout.CENTER,
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val shape = Jelly.round(
                    0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(),
                    JBUI.scale(8).toFloat(),
                )
                g2.color = AccountUiColors.cardBg
                g2.fill(shape)
                g2.color = STATS_CARD_BORDER
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /** 趋势区右上角的小切换（总览 / 按账号） */
    private class SmallToggle(text: String, selected: Boolean, onClick: () -> Unit) : JComponent() {
        private val label = text
        private val isSelected = selected

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = AccountUiType.ui(AccountUiType.caption, if (selected) Font.BOLD else Font.PLAIN)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onClick()
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            return Dimension(fm.stringWidth(label) + JBUI.scale(16), fm.height + JBUI.scale(8))
        }

        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val shape = Jelly.round(0f, 0f, width.toFloat(), height.toFloat(), JBUI.scale(4).toFloat())
                g2.color = if (isSelected) STATS_CHIP_BG else AccountUiColors.cardBg
                g2.fill(shape)
                if (isSelected) {
                    g2.color = STATS_CARD_BORDER
                    g2.draw(shape)
                }
                g2.font = font
                g2.color = if (isSelected) AccountUiColors.textPrimary else AccountUiColors.textMuted
                val fm = g2.fontMetrics
                g2.drawString(label, (width - fm.stringWidth(label)) / 2, (height - fm.height) / 2 + fm.ascent)
            } finally {
                g2.dispose()
            }
        }
    }

    /** 账号用量行：账号名 + 右对齐数值 + 调用次数 */
    private class AccountUsageRow(name: String, value: String, count: Int) : JPanel(BorderLayout(JBUI.scale(10), 0)) {
        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(7, 0)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            add(
                JLabel(name).apply {
                    font = AccountUiType.ui(AccountUiType.label)
                    foreground = AccountUiColors.textSecondary
                },
                BorderLayout.CENTER,
            )
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false }
            right.add(
                JLabel(value).apply {
                    font = AccountUiType.ui(AccountUiType.label, Font.BOLD)
                    foreground = AccountUiColors.textPrimary
                }
            )
            right.add(
                JLabel("$count 次").apply {
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                    horizontalAlignment = SwingConstants.RIGHT
                }
            )
            add(right, BorderLayout.EAST)
        }
    }

    /**
     * 每日趋势折线：绿色折线 + 浅绿渐变填充 + 圆点。
     *
     * 点位 ≤ 12 时在点上方标数值；日期标签按可用宽度隔几个画一个，窄窗口不会糊成一片。
     */
    private class TrendChart : JComponent() {
        private var points: List<DailyUsage> = emptyList()

        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(0, JBUI.scale(170))
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        fun setPoints(list: List<DailyUsage>) {
            points = list
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = STATS_PLOT_BG
                g2.fill(Jelly.round(0f, 0f, width.toFloat(), height.toFloat(), JBUI.scale(8).toFloat()))

                if (points.isEmpty()) {
                    g2.font = AccountUiType.ui(AccountUiType.caption)
                    g2.color = AccountUiColors.textMuted
                    g2.drawString("没有可绘制的数据", JBUI.scale(14), height / 2)
                    return
                }

                val left = JBUI.scale(22).toFloat()
                val right = width - JBUI.scale(22).toFloat()
                val top = JBUI.scale(26).toFloat()
                val bottom = height - JBUI.scale(26).toFloat()
                if (right <= left || bottom <= top) return

                val maxCredit = points.maxOf { it.credit }.coerceAtLeast(1.0)
                val stepX = if (points.size <= 1) 0f else (right - left) / (points.size - 1)

                fun xAt(i: Int): Float = left + stepX * i
                fun yAt(v: Double): Float = (bottom - ((v / maxCredit) * (bottom - top))).toFloat()

                // 折线 + 填充
                val line = java.awt.geom.Path2D.Float()
                val area = java.awt.geom.Path2D.Float()
                for ((i, p) in points.withIndex()) {
                    val x = xAt(i)
                    val y = yAt(p.credit)
                    if (i == 0) {
                        line.moveTo(x, y)
                        area.moveTo(x, bottom)
                        area.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                area.lineTo(xAt(points.size - 1), bottom)
                area.closePath()
                g2.paint = GradientPaint(
                    0f, top, Color(STATS_GREEN.red, STATS_GREEN.green, STATS_GREEN.blue, 70),
                    0f, bottom, Color(STATS_GREEN.red, STATS_GREEN.green, STATS_GREEN.blue, 6),
                )
                g2.fill(area)
                g2.color = STATS_GREEN
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(line)

                // 点 / 数值 / 日期
                val valueFont = AccountUiType.ui(AccountUiType.micro)
                val dateFont = AccountUiType.ui(AccountUiType.micro)
                g2.font = valueFont
                val fmValue = g2.fontMetrics
                val labelEvery = ((points.size * JBUI.scale(46)) / (right - left).toInt().coerceAtLeast(1)).coerceAtLeast(1)
                for ((i, p) in points.withIndex()) {
                    val x = xAt(i)
                    val y = yAt(p.credit)
                    val dot = JBUI.scale(3)
                    g2.color = AccountUiColors.cardBg
                    g2.fillOval((x - dot).toInt(), (y - dot).toInt(), dot * 2, dot * 2)
                    g2.color = STATS_GREEN
                    g2.stroke = BasicStroke(1.6f)
                    g2.drawOval((x - dot).toInt(), (y - dot).toInt(), dot * 2, dot * 2)

                    if (points.size <= 12) {
                        g2.font = valueFont
                        g2.color = AccountUiColors.textSecondary
                        val text = formatCreditThousands(p.credit)
                        g2.drawString(text, (x - fmValue.stringWidth(text) / 2).toInt(), (y - JBUI.scale(8)).toInt())
                    }
                    if (i % labelEvery == 0 || i == points.size - 1) {
                        g2.font = dateFont
                        g2.color = AccountUiColors.textMuted
                        val text = p.date.format(DateTimeFormatter.ofPattern("MM-dd"))
                        val fm = g2.fontMetrics
                        g2.drawString(text, (x - fm.stringWidth(text) / 2).toInt(), (height - JBUI.scale(8)))
                    }
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        private val RANGES = arrayOf(
            RangeChoice("今天", 1),
            RangeChoice("近 7 天", 7),
            RangeChoice("近 30 天", 30),
            RangeChoice("近 90 天", 90),
        )

        fun show(project: Project) {
            UsageStatsDialog(project).show()
        }
    }
}
