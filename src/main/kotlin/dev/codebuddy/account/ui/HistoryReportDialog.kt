package dev.codebuddy.account.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.history.HistoryActivity
import dev.codebuddy.account.history.HistoryReport
import dev.codebuddy.account.history.HistoryReportBuilder
import dev.codebuddy.account.history.ReportBucket
import dev.codebuddy.account.history.ReportDimension
import dev.codebuddy.account.history.ReportEntry
import dev.codebuddy.account.hotswap.CodeBuddyBridge
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * 对话日报 / 周报（按项目统计）。
 *
 * 数据只来自本机各账号的历史会话索引（Data/<uid>/.../history/<workspace>/index.json），
 * 报告区间按「会话最后一条消息时间」划分：
 *  - 今日日报：今天
 *  - 本周周报：本周一 ~ 今天
 *
 * 维度可切换（项目 / 账号 / 日期 / 时段 / 会话类型）；
 * 「用 CodeBuddy 生成」会把统计事实拼成提示词复制到剪贴板并打开 CodeBuddy 对话窗口，
 * 由模型生成日报 / 周报正文。
 */
class HistoryReportDialog(
    private val project: Project,
    private val accountLabels: Map<String, String>,
) : DialogWrapper(project) {

    private val dayBtn = JToggleButton("今日日报", true)
    private val weekBtn = JToggleButton("本周周报", false)
    private val dimCombo = JComboBox<ReportDimension>()
    private val refreshBtn = JButton("重新统计")
    private val genBtn = JButton("用 CodeBuddy 生成")
    private val copyBtn = JButton("复制 Markdown")
    private val statusLabel = JBLabel("准备中…")
    private val titleLabel = JBLabel("")
    private val searchField = SearchTextField(false)
    private val kpiPanel = JPanel(GridLayout(1, 5, JBUI.scale(6), 0)).apply { isOpaque = false }
    private val bucketPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
    }
    private val tableModel = DetailTableModel()
    private val table = JBTable(tableModel)

    private var report: HistoryReport? = null

    /** 左侧维度条当前选中的分组（再次点击同项取消），用于筛选右侧明细 */
    private var selectedBucket: String? = null

    /** 左侧时间快捷筛选：today / yesterday / week，null 表示不限 */
    private var quickRange: String? = null

    /** 时间快捷筛选按钮（今天 / 昨天 / 本周） */
    private val quickBtns = LinkedHashMap<String, JToggleButton>()

    /** 每次统计递增，丢弃过期结果 */
    private var buildSeq = 0

    init {
        title = "CodeBuddy 对话日报 / 周报"
        setSize(1060, 720)

        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
        titleLabel.foreground = AccountUiColors.textPrimary
        titleLabel.toolTipText = "会话按最后一条消息的时间归入当天；周报为本周一至今天"
        statusLabel.foreground = AccountUiColors.textMuted
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())

        ButtonGroup().apply {
            add(dayBtn)
            add(weekBtn)
        }
        dayBtn.isFocusPainted = false
        weekBtn.isFocusPainted = false
        dayBtn.addActionListener { onRangeChanged() }
        weekBtn.addActionListener { onRangeChanged() }

        dimCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? ReportDimension)?.label ?: "", index, isSelected, cellHasFocus
            )
        }
        dimCombo.addActionListener {
            // 换维度后旧的分组键失效，清掉筛选
            selectedBucket = null
            render(report)
        }

        // 左侧时间快捷筛选
        for ((key, label) in listOf("today" to "今天", "yesterday" to "昨天", "week" to "本周")) {
            val btn = JToggleButton(label, false)
            btn.isFocusPainted = false
            btn.toolTipText = "按「$label」筛选右侧明细"
            btn.addActionListener {
                quickRange = if (quickRange == key) null else key
                render(report)
            }
            quickBtns[key] = btn
        }

        // 明细搜索：会话名 / 项目 / 账号 / 类型
        searchField.textEditor.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = render(report)
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = render(report)
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = render(report)
        })

        refreshBtn.isFocusPainted = false
        refreshBtn.addActionListener { reload(forceScan = true) }
        genBtn.isFocusPainted = false
        genBtn.toolTipText = "把统计事实写成提示词复制到剪贴板，并打开 CodeBuddy 对话窗口"
        genBtn.addActionListener { generateInCodeBuddy() }
        copyBtn.isFocusPainted = false
        copyBtn.addActionListener { copyMarkdown() }

        init()
        onRangeChanged()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.panelBg

        // ---------- 顶部：区间 / 维度 / 概览 ----------
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.background = AccountUiColors.surfaceBg
        top.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10)
        )

        val controlRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        controlRow.isOpaque = false
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        left.add(dayBtn)
        left.add(weekBtn)
        left.add(Box.createHorizontalStrut(JBUI.scale(6)))
        left.add(JBLabel("维度:"))
        left.add(dimCombo)
        left.add(refreshBtn)
        controlRow.add(left, BorderLayout.WEST)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        right.add(genBtn)
        right.add(copyBtn)
        controlRow.add(right, BorderLayout.EAST)

        val titleRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
        }
        titleRow.add(titleLabel, BorderLayout.WEST)
        titleRow.add(statusLabel, BorderLayout.EAST)

        val kpiWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(kpiPanel, BorderLayout.CENTER)
        }

        top.add(controlRow)
        top.add(titleRow)
        top.add(kpiWrap)

        // ---------- 中部：维度分布 + 明细表 ----------
        table.rowHeight = JBUI.scale(24)
        table.showHorizontalLines = false
        table.showVerticalLines = false
        table.intercellSpacing = Dimension(0, 0)
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.tableHeader.reorderingAllowed = false
        val widths = intArrayOf(300, 150, 120, 90, 70)
        for (i in widths.indices) {
            table.columnModel.getColumn(i).preferredWidth = JBUI.scale(widths[i])
        }

        val bucketScroll = JBScrollPane(bucketPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(360), 0)
        }
        // 左侧：时间快捷筛选 + 维度分布（条目可点击筛选明细）
        val leftPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(360), 0)
        }
        val quickRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8)
        }
        quickRow.add(JBLabel("时间").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textMuted
        })
        quickBtns.values.forEach { quickRow.add(it) }
        leftPanel.add(quickRow, BorderLayout.NORTH)
        leftPanel.add(bucketScroll, BorderLayout.CENTER)

        val body = JPanel(BorderLayout(JBUI.scale(10), 0))
        body.background = AccountUiColors.panelBg
        body.border = JBUI.Borders.empty(8, 10)
        body.add(leftPanel, BorderLayout.WEST)
        val searchRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(searchField, BorderLayout.CENTER)
        }
        val rightPane = JPanel(BorderLayout()).apply { isOpaque = false }
        rightPane.add(searchRow, BorderLayout.NORTH)
        rightPane.add(JBScrollPane(table).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        body.add(rightPane, BorderLayout.CENTER)

        root.add(top, BorderLayout.NORTH)
        root.add(body, BorderLayout.CENTER)
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = table

    // ---------------------------------------------------------------
    // 数据
    // ---------------------------------------------------------------

    /** 是否日报（否则为周报） */
    private fun isDaily(): Boolean = dayBtn.isSelected

    /** 维度筛选用的天数：日报 1 天，周报按本周一到今天实际跨度 */
    private fun days(): Int = range().let { (s, e) -> (s.until(e).days + 1).coerceAtLeast(1) }

    /** 日报：今天；周报：本周一 ~ 今天 */
    private fun range(): Pair<LocalDate, LocalDate> {
        val end = LocalDate.now()
        if (isDaily()) return end to end
        val monday = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday to end
    }

    private fun onRangeChanged() {
        val dims = ReportDimension.forRange(days())
        dimCombo.removeAllItems()
        dims.forEach { dimCombo.addItem(it) }
        dimCombo.selectedItem = ReportDimension.PROJECT
        // 区间变了，之前的筛选不再适用
        selectedBucket = null
        quickRange = null
        reload(forceScan = false)
    }

    private fun reload(forceScan: Boolean) {
        if (!forceScan && HistoryActivity.hasData()) {
            buildAsync()
            return
        }
        statusLabel.text = "正在扫描历史对话…"
        HistoryActivity.ensureFresh(force = forceScan) {
            if (project.isDisposed) return@ensureFresh
            buildAsync()
        }
    }

    /** 在后台统计消息条数并生成报告 */
    private fun buildAsync() {
        val (start, end) = range()
        val reportTitle = if (isDaily()) "今日日报" else "本周周报"
        val conversations = HistoryActivity.state().conversations
        val seq = ++buildSeq

        statusLabel.text = "正在统计消息条数…"
        refreshBtn.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            var failure: String? = null
            val built = try {
                HistoryReportBuilder.build(conversations, accountLabels, start, end, reportTitle)
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                null
            }
            post {
                if (seq != buildSeq) return@post
                refreshBtn.isEnabled = true
                if (built == null) {
                    statusLabel.text = "统计失败：${failure ?: "未知错误"}"
                    return@post
                }
                report = built
                selectedBucket = null
                quickRange = null
                render(built)
            }
        }
    }

    // ---------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------

    private fun render(r: HistoryReport?) {
        if (r == null) return
        val dim = dimCombo.selectedItem as? ReportDimension ?: ReportDimension.ACCOUNT

        titleLabel.text = "${r.title} · ${r.rangeLabel}"
        kpiPanel.removeAll()
        kpiPanel.add(miniKpi("会话", "${r.conversationCount} 个", AccountUiColors.accent))
        kpiPanel.add(miniKpi("消息", "${r.totalMessages} 条", AccountUiColors.success))
        kpiPanel.add(miniKpi("活跃项目", "${r.projectCount} 个", AccountUiColors.warning))
        kpiPanel.add(miniKpi("活跃账号", "${r.accountCount} 个", AccountUiColors.textPrimary))
        kpiPanel.add(miniKpi("活跃天数", "${r.activeDays} 天", AccountUiColors.textMuted))
        kpiPanel.revalidate()
        kpiPanel.repaint()

        bucketPanel.removeAll()
        val buckets = r.buckets(dim)
        // 时间快捷筛选按钮的选中态
        quickBtns.forEach { (key, btn) -> btn.isSelected = quickRange == key }
        val activeFilters = buildList {
            quickRange?.let { add(quickRangeLabel(it)) }
            selectedBucket?.let { add(it) }
        }
        if (activeFilters.isNotEmpty()) {
            bucketPanel.add(ActionLink("清除筛选（${activeFilters.joinToString(" / ")}）") {
                selectedBucket = null
                quickRange = null
                render(report)
            })
            bucketPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        if (buckets.isEmpty()) {
            bucketPanel.add(JBLabel("该区间没有历史对话").apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                foreground = AccountUiColors.textFaint
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(12, 2)
            })
        } else {
            val max = buckets.maxOf { it.conversations }.coerceAtLeast(1)
            for (b in buckets) {
                bucketPanel.add(bucketRow(b, max, b.label == selectedBucket))
                bucketPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }
        bucketPanel.add(Box.createVerticalGlue())
        bucketPanel.revalidate()
        bucketPanel.repaint()

        val rows = filteredEntries(r, dim)
        tableModel.setRows(rows)
        val scanError = HistoryActivity.state().error
        val kw = searchField.text.trim()
        statusLabel.text = buildString {
            append("共扫描 ${HistoryActivity.state().conversations.size} 个会话")
            if (scanError != null) append("　|　$scanError")
            if (kw.isNotEmpty()) append("　|　搜索「$kw」命中 ${rows.size} 个")
            if (activeFilters.isNotEmpty()) {
                append("　|　筛选 ${activeFilters.joinToString(" / ")}，明细 ${rows.size} 个")
            }
        }
    }

    private fun quickRangeLabel(key: String): String = when (key) {
        "today" -> "今天"
        "yesterday" -> "昨天"
        "week" -> "本周"
        else -> key
    }

    /** 按搜索关键字 + 维度条目 + 时间快捷筛选过滤明细 */
    private fun filteredEntries(r: HistoryReport, dim: ReportDimension): List<ReportEntry> {
        var rows = r.entries
        val sel = selectedBucket
        if (sel != null) {
            rows = rows.filter { r.groupKeyOf(it, dim) == sel }
        }
        val quick = quickRange
        if (quick != null) {
            val today = LocalDate.now()
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            rows = rows.filter { e ->
                val d = e.anchor?.toLocalDate() ?: return@filter false
                when (quick) {
                    "today" -> d == today
                    "yesterday" -> d == today.minusDays(1)
                    "week" -> !d.isBefore(monday) && !d.isAfter(today)
                    else -> true
                }
            }
        }
        val kw = searchField.text.trim()
        if (kw.isNotEmpty()) {
            rows = rows.filter { e ->
                e.name.contains(kw, true) || e.project.contains(kw, true) ||
                    e.account.contains(kw, true) || e.type.contains(kw, true)
            }
        }
        return rows
    }

    /** 当前生效的报告（应用了左侧筛选），用于导出与生成提示词 */
    private fun currentReport(): HistoryReport? {
        val r = report ?: return null
        val dim = dimCombo.selectedItem as? ReportDimension ?: return r
        val rows = filteredEntries(r, dim)
        return if (rows.size == r.entries.size) r else r.copy(entries = rows)
    }

    private fun miniKpi(title: String, value: String, color: Color): JComponent =
        object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.inner, elevated = true
        ) {
            init {
                border = JBUI.Borders.empty(8, 10)
                add(JLabel(title).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                    foreground = AccountUiColors.textMuted
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(JLabel(value).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
                    foreground = color
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }

    private fun bucketRow(b: ReportBucket, max: Int, selected: Boolean): JComponent {
        val row = FullWidthColumn()
        if (selected) {
            row.isOpaque = true
            row.background = AccountUiColors.accentSoft
        }
        val head = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        head.add(JLabel(b.label).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = if (selected) AccountUiColors.accent else AccountUiColors.textPrimary
        }, BorderLayout.WEST)
        head.add(JLabel(b.detail()).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textFaint
            horizontalAlignment = SwingConstants.RIGHT
        }, BorderLayout.EAST)

        val barWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(3)
            add(RatioBar(b.conversations.toDouble() / max), BorderLayout.CENTER)
        }

        row.add(head)
        row.add(barWrap)

        // 整行可点：点击即按该分组筛选右侧明细，再次点击同项取消
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.toolTipText = if (selected) "已按「${b.label}」筛选，点击取消" else "点击筛选「${b.label}」的会话明细"
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                selectedBucket = if (selectedBucket == b.label) null else b.label
                render(report)
            }
        })
        return row
    }

    // ---------------------------------------------------------------
    // 操作
    // ---------------------------------------------------------------

    /**
     * 把统计事实写成提示词，复制到剪贴板并打开 CodeBuddy 对话窗口。
     *
     * CodeBuddy 未对外提供「携带提示词发起会话」的 action，也没有可注入输入框的接口，
     * 因此只能把用户带到对话窗口由用户粘贴发送（同时避免把本地统计数据上传给第三方）。
     * 本窗口是模态的，会挡住 IDE 操作，所以先关窗再开窗。
     */
    private fun generateInCodeBuddy() {
        val r = currentReport() ?: run {
            statusLabel.text = "还没有可生成的报告"
            return
        }
        val prompt = r.toPrompt(if (isDaily()) "今日工作日报" else "本周工作周报")
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(prompt), null)

        close(CANCEL_EXIT_CODE)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val opened = CodeBuddyBridge.openChatWindow(project)
            val message = if (opened) {
                "已复制生成提示词并打开 CodeBuddy 对话窗口，在输入框粘贴（Ctrl+V）后发送即可生成${r.title}"
            } else {
                "已复制生成提示词，请打开 CodeBuddy 对话窗口粘贴发送（未找到 CodeBuddy 侧边栏）"
            }
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("CodeBuddy 日报 / 周报", message, NotificationType.INFORMATION)
                .notify(project)
        }
    }

    private fun copyMarkdown() {
        val r = currentReport() ?: run {
            statusLabel.text = "还没有可复制的报告"
            return
        }
        val dim = dimCombo.selectedItem as? ReportDimension ?: ReportDimension.ACCOUNT
        val text = r.toMarkdown(dim)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        statusLabel.text = "已复制 ${r.title}（${dim.label}）到剪贴板" +
            if (selectedBucket != null) "，含筛选「$selectedBucket」" else ""
    }

    private fun post(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any())
    }

    // ---------------------------------------------------------------
    // 组件
    // ---------------------------------------------------------------

    /** 维度分布用的细进度条（与额度进度条语义色无关，固定用品牌色） */
    private class RatioBar(private val ratio: Double) : JComponent() {

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(6))
            minimumSize = Dimension(0, JBUI.scale(6))
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(6))

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
                val clamped = ratio.coerceIn(0.0, 1.0)
                if (clamped <= 0.0) return
                val fw = (w * clamped).toInt().coerceIn(1, w.toInt())
                g2.color = AccountUiColors.accent
                g2.fill(RoundRectangle2D.Float(0f, 0f, fw.toFloat(), h, h, h))
            } finally {
                g2.dispose()
            }
        }
    }

    private class DetailTableModel : AbstractTableModel() {

        private var rows: List<ReportEntry> = emptyList()

        fun setRows(list: List<ReportEntry>) {
            rows = list
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(row: Int, column: Int): Any {
            val e = rows[row]
            return when (column) {
                0 -> e.name.ifBlank { "(未命名)" }
                1 -> e.project
                2 -> e.account
                3 -> e.type.ifBlank { "-" }
                4 -> e.messages.toString()
                else -> e.anchor?.format(HistoryReport.MINUTE_FMT) ?: "-"
            }
        }
    }

    companion object {
        private val COLUMNS = arrayOf("会话", "项目", "账号", "类型", "消息", "最近活跃")

        /** 与 AccountPanel 共用的通知组（plugin.xml 中注册） */
        private const val NOTIFICATION_GROUP = "CodeBuddy Account Manager"

        fun show(project: Project, accountLabels: Map<String, String>) {
            HistoryReportDialog(project, accountLabels).show()
        }
    }
}
