package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.usage.UsagePage
import dev.codebuddy.account.usage.UsageRecord
import dev.codebuddy.account.usage.UsageStats
import dev.codebuddy.account.usage.formatCredit
import java.awt.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * 请求消耗明细：对应网页 https://<domain>/profile/plans-usage 的「消耗明细」页。
 *
 * 数据来自接口 /billing/meter/get-user-request-usage（见 UsageService），
 * 一次拉取时间范围内最多 1000 条，前端分页；合计与模型分布按已加载记录统计。
 *
 * 接口只提供「一次请求」的模型与积分，没有会话 ID，
 * 因此无法把消耗精确归属到某个历史对话。
 *
 * 「加载中」状态必须有出口：请求超时、后台线程抛错、渲染异常都会落到明确的失败提示，
 * 回调统一切回 EDT（[ModalityState.any]，避免被模态窗挡住），不会停在「正在读取…」。
 */
class UsageDetailDialog(private val project: Project) : DialogWrapper(project) {

    private val library = AccountLibrary.getInstance()

    private val accountCombo = JComboBox<AccountChoice>()
    private val rangeCombo = JComboBox(RANGE_CHOICES).apply { selectedItem = RANGE_CHOICES[1] }
    private val refreshBtn = JButton("刷新")
    private val statusLabel = JBLabel("")
    private val summaryTitle = JBLabel("尚未查询消耗明细")
    private val summaryModels = JBLabel("")
    private val prevBtn = JButton("上一页")
    private val nextBtn = JButton("下一页")
    private val pageLabel = JBLabel("")
    private val pageSizeCombo = JComboBox(PAGE_SIZES)

    private val tableModel = UsageTableModel()
    private val table = JBTable(tableModel)

    private var records: List<UsageRecord> = emptyList()
    private var pageIndex = 1
    private var pageSize = PAGE_SIZES[1]
    private var loadSeq = 0
    private var suppressCombo = false

    init {
        title = "CodeBuddy 消耗明细"
        setSize(1060, 720)

        summaryTitle.font = summaryTitle.font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        summaryModels.foreground = AccountUiColors.textMuted
        statusLabel.foreground = AccountUiColors.textMuted
        pageLabel.foreground = AccountUiColors.textMuted

        suppressCombo = true
        accountCombo.addActionListener {
            if (!suppressCombo) {
                pageIndex = 1
                load()
            }
        }
        rangeCombo.addActionListener {
            pageIndex = 1
            load()
        }
        pageSizeCombo.addActionListener {
            pageSize = pageSizeCombo.selectedItem as? Int ?: PAGE_SIZES[1]
            pageIndex = 1
            renderPage()
        }
        refreshBtn.addActionListener { load() }
        prevBtn.addActionListener { if (pageIndex > 1) { pageIndex--; renderPage() } }
        nextBtn.addActionListener { if (pageIndex < pageCount()) { pageIndex++; renderPage() } }

        init()
        reloadAccounts()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.panelBg

        // ---------- 顶部：筛选 + 汇总 ----------
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.background = AccountUiColors.surfaceBg
        top.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10)
        )

        val filterRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        filterRow.isOpaque = false
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        left.isOpaque = false
        left.add(JBLabel("账号:"))
        accountCombo.preferredSize = Dimension(JBUI.scale(300), accountCombo.preferredSize.height)
        left.add(accountCombo)
        left.add(JBLabel("时间:"))
        left.add(rangeCombo)
        left.add(refreshBtn)
        filterRow.add(left, BorderLayout.WEST)
        filterRow.add(statusLabel, BorderLayout.CENTER)

        val summary = JPanel()
        summary.layout = BoxLayout(summary, BoxLayout.Y_AXIS)
        summary.isOpaque = false
        summary.border = JBUI.Borders.emptyTop(8)
        summary.add(summaryTitle)
        summary.add(Box.createVerticalStrut(JBUI.scale(3)))
        summary.add(summaryModels)

        top.add(filterRow)
        top.add(summary)

        // ---------- 中部：明细表 ----------
        table.rowHeight = JBUI.scale(24)
        table.showHorizontalLines = false
        table.showVerticalLines = false
        table.intercellSpacing = Dimension(0, 0)
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.tableHeader.reorderingAllowed = false

        val widths = intArrayOf(150, 100, 160, 90, 140)
        for (i in widths.indices) {
            table.columnModel.getColumn(i).preferredWidth = JBUI.scale(widths[i])
        }
        // 积分右对齐
        table.columnModel.getColumn(COL_CREDIT).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).also {
                horizontalAlignment = SwingConstants.RIGHT
                border = JBUI.Borders.emptyRight(8)
            }
        }
        // 请求列：悬停显示完整提示词
        table.columnModel.getColumn(COL_PROMPT).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val modelRow = table?.convertRowIndexToModel(row) ?: -1
                val record = tableModel.recordAt(modelRow)
                toolTipText = record?.prompt?.ifBlank { record.displayPrompt }
                border = JBUI.Borders.emptyRight(8)
                return c
            }
        }

        val scroll = JBScrollPane(table).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        // ---------- 底部：分页 ----------
        val pager = JPanel(BorderLayout())
        pager.isOpaque = false
        pager.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 1, 0, 0, 0),
            JBUI.Borders.empty(6, 10)
        )
        val pagerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        pagerLeft.isOpaque = false
        pagerLeft.add(prevBtn)
        pagerLeft.add(nextBtn)
        pagerLeft.add(pageLabel)
        val pagerRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        pagerRight.isOpaque = false
        pagerRight.add(JBLabel("每页"))
        pageSizeCombo.selectedItem = pageSize
        pagerRight.add(pageSizeCombo)
        pagerRight.add(JBLabel("条"))
        pager.add(pagerLeft, BorderLayout.WEST)
        pager.add(pagerRight, BorderLayout.EAST)

        root.add(top, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        root.add(pager, BorderLayout.SOUTH)
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = table

    // ---------------------------------------------------------------
    // 数据
    // ---------------------------------------------------------------

    private fun reloadAccounts() {
        val saved = library.listSaved()
        val activeKey = library.currentActive()?.accountKey

        suppressCombo = true
        accountCombo.removeAllItems()
        for (s in saved) {
            accountCombo.addItem(AccountChoice(s.accountKey, "${s.displayName}（${s.domainHost}）"))
        }
        val items = (0 until accountCombo.itemCount).map { accountCombo.getItemAt(it) }
        accountCombo.selectedItem = items.firstOrNull { it.key == activeKey } ?: items.firstOrNull()
        suppressCombo = false

        if (items.isEmpty()) {
            statusLabel.text = "账号库为空，请先在面板中导入账号"
            summaryTitle.text = "暂无数据"
            summaryModels.text = ""
            records = emptyList()
            renderPage()
            return
        }
        load()
    }

    private fun load() {
        val choice = accountCombo.selectedItem as? AccountChoice
        val range = rangeCombo.selectedItem as? RangeChoice
        if (choice == null || range == null) {
            statusLabel.text = "请先选择账号"
            summaryTitle.text = "暂无数据"
            summaryModels.text = ""
            return
        }

        val seq = ++loadSeq
        setBusy(true)
        statusLabel.text = "正在查询…"
        summaryTitle.text = "正在读取消耗明细…"
        summaryModels.text = ""
        records = emptyList()
        tableModel.setRows(emptyList())
        renderPage()

        val startTime = range.startTime()
        val endTime = range.endTime()
        val finished = AtomicBoolean(false)

        // 看门狗：网络层异常挂起时也要把界面从「加载中」放出来
        val watchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                if (finished.compareAndSet(false, true)) {
                    post {
                        if (seq != loadSeq) return@post
                        setBusy(false)
                        statusLabel.text = "${choice.label} · ${range.label}　|　请求超时"
                        summaryTitle.text = "查询超时"
                        summaryModels.text = "超过 $TIMEOUT_SECONDS 秒没有返回，请检查网络后重试"
                    }
                }
            },
            TIMEOUT_SECONDS.toLong(),
            TimeUnit.SECONDS
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            val page = try {
                library.fetchUsage(choice.key, startTime, endTime)
            } catch (t: Throwable) {
                UsagePage(error = t.message ?: t.javaClass.simpleName)
            }
            watchdog.cancel(false)
            // 已被看门狗判定超时则丢弃结果
            if (!finished.compareAndSet(false, true)) return@executeOnPooledThread

            post {
                if (seq != loadSeq) return@post
                setBusy(false)
                applyResult(choice, range, page)
            }
        }
    }

    private fun applyResult(choice: AccountChoice, range: RangeChoice, page: UsagePage) {
        if (page.error != null) {
            records = emptyList()
            statusLabel.text = "${choice.label} · ${range.label}　|　${page.error}"
            summaryTitle.text = "查询失败"
            summaryModels.text = ""
            renderPage()
            return
        }

        records = page.records
        pageIndex = 1
        statusLabel.text = buildString {
            append(choice.label)
            append("　|　")
            append(range.label)
            if (page.truncated) {
                append("　|　共 ${page.total} 条，仅加载最近 ${page.records.size} 条")
            }
        }

        try {
            if (records.isEmpty()) {
                summaryTitle.text = "该时间段没有消耗记录"
                summaryModels.text = ""
            } else {
                summaryTitle.text = "合计 ${formatCredit(UsageStats.totalCredit(records))} credits · ${records.size} 次请求"
                summaryModels.text = "模型：" + UsageStats.byModel(records)
                    .filter { it.count > 0 }
                    .take(4)
                    .joinToString("　·　") { it.text() }
            }
            renderPage()
        } catch (t: Throwable) {
            records = emptyList()
            tableModel.setRows(emptyList())
            renderPage()
            summaryTitle.text = "结果渲染失败"
            summaryModels.text = t.message ?: t.javaClass.simpleName
        }
    }

    private fun renderPage() {
        val count = pageCount()
        if (pageIndex > count) pageIndex = count
        val from = (pageIndex - 1) * pageSize
        val to = minOf(from + pageSize, records.size)
        val rows = if (from >= records.size) emptyList() else records.subList(from, to)
        tableModel.setRows(rows)

        pageLabel.text = "第 $pageIndex / $count 页　共 ${records.size} 条"
        prevBtn.isEnabled = pageIndex > 1
        nextBtn.isEnabled = pageIndex < count
    }

    private fun pageCount(): Int =
        if (records.isEmpty()) 1 else (records.size + pageSize - 1) / pageSize

    /** 请求期间只禁用「刷新」，账号 / 时间仍可切换（切换后会丢弃旧结果） */
    private fun setBusy(busy: Boolean) {
        refreshBtn.isEnabled = !busy
        refreshBtn.text = if (busy) "查询中…" else "刷新"
    }

    /** 统一切回 EDT：用 any 模态态，避免被其它模态窗挡住导致界面停在加载中 */
    private fun post(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any())
    }

    // ---------------------------------------------------------------
    // 组件
    // ---------------------------------------------------------------

    /** 账号下拉项 */
    private data class AccountChoice(val key: String, val label: String) {
        override fun toString(): String = label
    }

    /** 时间范围下拉项，[days] = 0 表示今天 */
    private class RangeChoice(val label: String, private val days: Int) {
        fun startTime(): String = LocalDate.now().minusDays(days.toLong()).toString() + " 00:00:00"
        fun endTime(): String = LocalDate.now().toString() + " 23:59:59"
        override fun toString(): String = label
    }

    private class UsageTableModel : AbstractTableModel() {

        private var rows: List<UsageRecord> = emptyList()

        fun setRows(list: List<UsageRecord>) {
            rows = list
            fireTableDataChanged()
        }

        fun recordAt(index: Int): UsageRecord? = rows.getOrNull(index)

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                COL_TIME -> r.requestTime
                COL_PRODUCT -> r.productLabel
                COL_MODEL -> r.model.ifBlank { "-" }
                COL_CREDIT -> r.creditText()
                COL_CLIENT -> r.client.ifBlank { "-" }
                else -> r.displayPrompt
            }
        }
    }

    companion object {
        private const val COL_TIME = 0
        private const val COL_PRODUCT = 1
        private const val COL_MODEL = 2
        private const val COL_CREDIT = 3
        private const val COL_CLIENT = 4
        private const val COL_PROMPT = 5

        /** 单次查询的最长等待时间（秒） */
        private const val TIMEOUT_SECONDS = 40

        private val COLUMNS = arrayOf("时间", "产品", "模型", "积分", "使用端", "请求")
        private val PAGE_SIZES = arrayOf(10, 20, 50, 100)
        private val RANGE_CHOICES = arrayOf(
            RangeChoice("今天", 0),
            RangeChoice("近 7 天", 7),
            RangeChoice("近 30 天", 30),
        )

        fun show(project: Project) {
            UsageDetailDialog(project).show()
        }
    }
}
