package dev.codebuddy.account.pet

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.components.AccountUiKit
import dev.codebuddy.account.ui.components.JellyButton
import dev.codebuddy.account.ui.components.JellyButtonVariant
import dev.codebuddy.account.ui.foundation.AccountTableStyle
import dev.codebuddy.account.ui.tokens.AccountUiColors
import dev.codebuddy.account.ui.tokens.AccountUiType
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import javax.swing.Action
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerDateModel
import javax.swing.SwingUtilities


/**
 * 代点记录详情窗口（看板「事件记录 → 查看详细」）。
 *
 * 数据来自 [PetApprovalHistory] 落在 `~/.codebuddy/codebuddy-pet/approval-history.jsonl` 的账本，
 * 跨重启可查，支持「起止日期 + 项目」组合筛选；双击一行看完整命令与执行结果。
 *
 * 只读，不提供删除：账本是排查误点的依据，要清理由主人自己删文件。
 *
 * 渲染：面板与滚动区显式着色（界面固定浅色，不能跟随 IDE 深色主题），
 * 并在窗口首次可见时补一次 validate + repaint —— 否则 Swing 可能先画一片空白，
 * 要等鼠标划过才把表格行刷出来。
 */
class ApprovalHistoryDialog : DialogWrapper(true) {

    private val model = HistoryTableModel()
    private val table = JBTable(model)
    private val statusLabel = JBLabel()
    private val projectCombo = JComboBox(arrayOf(ALL_PROJECTS))

    private val fromSpinner = dateSpinner(LocalDate.now().minusDays(6))
    private val toSpinner = dateSpinner(LocalDate.now())

    /** 内容根面板：显示后要拿它补一次布局与重绘 */
    private val root = JPanel(BorderLayout(JBUI.scale(8), 0))

    init {
        title = "代点记录"
        setSize(960, 520)
        init()
        refreshProjects()
        load()
        // 窗口显示时内容可能还没完成布局：补一次 validate/repaint，别等鼠标划过才刷出组件
        SwingUtilities.invokeLater { repaintContent() }
    }

    override fun createCenterPanel(): JComponent {
        root.background = AccountUiColors.panelBg
        root.isOpaque = true
        root.border = JBUI.Borders.empty(12, 14)

        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
        }
        top.add(caption("从"))
        top.add(fromSpinner)
        top.add(caption("到"))
        top.add(toSpinner)
        top.add(caption("项目"))
        AccountUiKit.styleCombo(projectCombo) { it?.toString().orEmpty() }
        projectCombo.preferredSize = JBUI.size(180, projectCombo.preferredSize.height)
        top.add(projectCombo)
        top.add(JellyButton("查询", JellyButtonVariant.PRIMARY) { load() })
        for ((text, days) in QUICK_RANGES) {
            top.add(
                JellyButton(text, JellyButtonVariant.SECONDARY) {
                    setDate(fromSpinner, LocalDate.now().minusDays(days.toLong()))
                    setDate(toSpinner, LocalDate.now())
                    load()
                },
            )
        }
        root.add(top, BorderLayout.NORTH)

        AccountTableStyle.apply(table, intArrayOf(110, 60, 120, 150, 300, 170))
        table.background = AccountUiColors.tableBodyBg
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val entry = model.entryAt(table.convertRowIndexToModel(row)) ?: return
                showDetail(entry)
            }
        })
        // 滚动区必须显式浅色化：IDE 深色主题下 viewport 会铺深灰，表格看着像没渲染
        val scroll = AccountUiKit.styleScroll(
            JBScrollPane(table),
            AccountUiColors.tableBodyBg,
        ).apply {
            preferredSize = JBUI.size(920, 360)
            border = JBUI.Borders.emptyTop(6)
        }
        root.add(scroll, BorderLayout.CENTER)
        root.add(
            statusLabel.apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = AccountUiColors.textMuted
                border = JBUI.Borders.empty(6, 2, 0, 0)
            },
            BorderLayout.SOUTH,
        )

        // 从隐藏变为可见时再补一次：首次 show 时上面的 invokeLater 可能还没跑完
        root.addHierarchyListener { e ->
            val showingChanged = e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (showingChanged && root.isShowing) SwingUtilities.invokeLater { repaintContent() }
        }
        return root
    }

    override fun createActions(): Array<Action> {
        // 只读窗口：留一个「关闭」，不要「确定」误导主人以为有改动
        okAction.putValue(Action.NAME, "关闭")
        return arrayOf(okAction)
    }

    // ------------------------------------------------------------------

    /** 内容区补布局与重绘（只在窗口已显示时执行，避免提前触发无意义的 layout） */
    private fun repaintContent() {
        if (!root.isShowing) return
        root.revalidate()
        root.repaint()
        table.revalidate()
        table.repaint()
    }

    private fun load() {
        val picked = (projectCombo.selectedItem as? String).orEmpty()
        val project = if (picked == ALL_PROJECTS) "" else picked
        val entries = try {
            PetApprovalHistory.query(fromMillis(), toMillis(), project, limit = MAX_ROWS)
        } catch (_: Throwable) {
            emptyList()
        }
        model.setRows(entries)
        val autos = entries.count { it.auto }
        val failed = entries.count { it.result == "fail" }
        val pending = entries.count { it.result == "pending" }
        statusLabel.text = if (entries.isEmpty()) {
            "该时间范围内没有记录（双击一行看完整命令与结果）"
        } else {
            "共 ${entries.size} 条 · 代点 $autos 次 · 失败 $failed 条 · 待回填 $pending 条" +
                "（双击一行看完整命令与结果）"
        }
        // 查询完成后再补一次，筛选变化时行也会立刻出现
        SwingUtilities.invokeLater { repaintContent() }
    }

    /** 项目下拉：保留当前选择，选项来自账本里去重后的项目名 */
    private fun refreshProjects() {
        val keep = projectCombo.selectedItem as? String ?: ALL_PROJECTS
        val names = listOf(ALL_PROJECTS) + try {
            PetApprovalHistory.projects()
        } catch (_: Throwable) {
            emptyList()
        }
        projectCombo.removeAllItems()
        names.distinct().forEach { projectCombo.addItem(it) }
        projectCombo.selectedItem = if (names.contains(keep)) keep else ALL_PROJECTS
    }

    private fun showDetail(entry: PetApprovalHistory.Entry) {
        val where = listOf(entry.project, entry.title).filter { it.isNotBlank() }.joinToString(" · ")
        val text = buildString {
            append("时间：").append(formatTime(entry.at)).append('\n')
            append("类型：").append(if (entry.auto) "代点（已替主人确认）" else "待确认（未代点）").append('\n')
            if (where.isNotBlank()) append("位置：").append(where).append('\n')
            append("工具：").append(entry.tool.ifBlank { "-" }).append('\n')
            append("来源：").append(entry.source).append('\n')
            append("结果：").append(entry.resultLabel)
            if (entry.resultText.isNotBlank()) append('\n').append(entry.resultText)
            append("\n\n命令：\n").append(entry.what)
        }
        Messages.showInfoMessage(text, "代点详情")
    }

    private fun fromMillis(): Long {
        val date = (fromSpinner.value as? Date)?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
            ?: LocalDate.now()
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun toMillis(): Long {
        val date = (toSpinner.value as? Date)?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
            ?: LocalDate.now()
        return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    }

    private fun dateSpinner(date: LocalDate): JSpinner {
        val value = Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        val spinner = JSpinner(SpinnerDateModel(value, null, null, Calendar.DAY_OF_MONTH))
        spinner.editor = JSpinner.DateEditor(spinner, DATE_PATTERN)
        (spinner.editor as? JSpinner.DefaultEditor)?.textField?.apply {
            font = AccountUiType.ui(AccountUiType.body)
            background = AccountUiColors.cardBg
            foreground = AccountUiColors.textPrimary
            isOpaque = true
        }
        return spinner
    }

    private fun setDate(spinner: JSpinner, date: LocalDate) {
        spinner.value = Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
    }

    private fun caption(text: String): JBLabel = JBLabel(text).apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
    }

    /** 表格模型：只读，结果列把输出摘要压在一行里 */
    private class HistoryTableModel : javax.swing.table.AbstractTableModel() {

        private val columns = arrayOf("时间", "类型", "项目", "对话", "命令", "结果")
        private var rows: List<PetApprovalHistory.Entry> = emptyList()

        fun setRows(list: List<PetApprovalHistory.Entry>) {
            rows = list
            fireTableDataChanged()
        }

        fun entryAt(row: Int): PetApprovalHistory.Entry? = rows.getOrNull(row)

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun getValueAt(row: Int, column: Int): Any {
            val entry = rows[row]
            return when (column) {
                0 -> ApprovalHistoryDialog.formatTime(entry.at)
                1 -> if (entry.auto) "代点" else "待确认"
                2 -> entry.project.ifBlank { "未标注项目" }
                3 -> entry.title
                4 -> entry.what.replace(ApprovalHistoryDialog.WHITESPACE, " ").trim()
                5 -> if (entry.resultText.isBlank()) {
                    entry.resultLabel
                } else {
                    entry.resultLabel + " · " +
                        entry.resultText.replace(ApprovalHistoryDialog.WHITESPACE, " ").trim()
                }
                else -> ""
            }
        }
    }

    private companion object {
        const val ALL_PROJECTS = "全部项目"
        const val DATE_PATTERN = "yyyy-MM-dd"
        const val MAX_ROWS = 500

        /** TIME 只含月/日/时/分/秒，必须先转成带时区的本地时间，直接格式化 Instant 会抛 UnsupportedTemporalTypeException */
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        val WHITESPACE = Regex("\\s+")

        fun formatTime(at: Long): String =
            TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

        /** 快捷区间：文案 to 往前天数（0 = 今天） */
        val QUICK_RANGES = listOf("今天" to 0, "近 7 天" to 6, "近 30 天" to 29)
    }
}
