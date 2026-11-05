package dev.codebuddy.account.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.usage.ModelCatalogService
import dev.codebuddy.account.usage.ModelQuotaRule
import dev.codebuddy.account.usage.ModelQuotaTracker
import dev.codebuddy.account.usage.formatCredit
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

/**
 * 模型限额规则编辑面板（设置页使用）。
 *
 * 每行一条规则：模型关键字 + 独立限额积分；
 * 右侧只读列展示当前周期的实际消耗与剩余（来自 [ModelQuotaTracker]）。
 */
class ModelQuotaRulesEditor : JPanel(BorderLayout()) {

    private val rules = mutableListOf<ModelQuotaRule>()

    private val modelCombo = JComboBox<String>().apply {
        isEditable = true
        prototypeDisplayValue = "deepseek-v4.1-flash"
        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val id = value?.toString() ?: ""
                val option = ModelCatalogService.state().models.firstOrNull { it.id == id }
                val text = if (option != null && option.name.isNotBlank()) option.label() else id
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }
    private val limitSpinner = JSpinner(SpinnerNumberModel(200.0, 0.0, 1_000_000.0, 10.0))
    private val addBtn = JButton("添加")
    private val refreshModelsBtn = JButton("从 CodeBuddy 加载模型")
    private val modelStatusLabel = JBLabel("")
    private val removeBtn = JButton("删除选中")
    private val upBtn = JButton("上移")
    private val downBtn = JButton("下移")

    private val tableModel = RulesTableModel()
    private val table = JBTable(tableModel)

    init {
        background = AccountUiColors.panelBg

        val inputRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        inputRow.add(JBLabel("模型："))
        inputRow.add(modelCombo)
        inputRow.add(refreshModelsBtn)
        inputRow.add(JBLabel("限额积分："))
        inputRow.add(limitSpinner)
        inputRow.add(addBtn)
        inputRow.add(modelStatusLabel.apply { foreground = AccountUiColors.textFaint })

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        buttonRow.add(removeBtn)
        buttonRow.add(upBtn)
        buttonRow.add(downBtn)
        buttonRow.add(JBLabel("关键字按「模型名包含关键字」匹配，可覆盖同一系列模型").apply {
            foreground = AccountUiColors.textFaint
        })

        val top = JPanel(BorderLayout()).apply { isOpaque = false }
        top.add(inputRow, BorderLayout.NORTH)
        top.add(buttonRow, BorderLayout.SOUTH)

        table.rowHeight = JBUI.scale(24)
        table.showHorizontalLines = false
        table.showVerticalLines = false
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.tableHeader.reorderingAllowed = false
        AccountTableStyle.apply(table, intArrayOf(160, 90, 90, 90, 80))

        add(top, BorderLayout.NORTH)
        add(table, BorderLayout.CENTER)

        addBtn.addActionListener { addRule() }
        removeBtn.addActionListener { removeSelected() }
        upBtn.addActionListener { moveSelected(-1) }
        downBtn.addActionListener { moveSelected(1) }
        refreshModelsBtn.addActionListener { loadModels(force = true) }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) syncButtons() }

        syncButtons()
        // 首次展示先用缓存（无缓存时后台拉取一次）
        renderModelOptions()
        loadModels(force = false)
    }

    /** 当前规则列表 */
    fun rules(): List<ModelQuotaRule> = rules.map { it.copy() }

    /** 覆盖规则列表 */
    fun setRules(list: List<ModelQuotaRule>) {
        rules.clear()
        rules.addAll(list.map { it.copy() })
        tableModel.fireTableDataChanged()
        syncButtons()
        renderModelOptions()
    }

    /** 刷新只读的消耗列（限额统计完成后调用） */
    fun refreshUsage() {
        tableModel.fireTableDataChanged()
    }

    private fun addRule() {
        val keyword = currentKeyword()
        if (keyword.isEmpty()) return
        val limit = (limitSpinner.value as? Number)?.toDouble() ?: 0.0
        if (rules.none { it.keyword.equals(keyword, ignoreCase = true) }) {
            rules.add(ModelQuotaRule(keyword, limit))
            tableModel.fireTableDataChanged()
        }
        modelCombo.editor.item = ""
        renderModelOptions()
    }

    /** 当前输入或选中的模型 id（可编辑下拉允许手填关键字） */
    private fun currentKeyword(): String {
        val editorText = (modelCombo.editor.item as? String)?.trim().orEmpty()
        val selected = modelCombo.selectedItem?.toString()?.trim().orEmpty()
        return editorText.ifEmpty { selected }
    }

    /** 从 CodeBuddy 拉取模型目录（force=true 时忽略缓存） */
    private fun loadModels(force: Boolean) {
        refreshModelsBtn.isEnabled = false
        modelStatusLabel.text = "加载中…"
        ModelCatalogService.ensureFresh(force) { renderModelOptions() }
        // 缓存命中时 ensureFresh 不会回调，这里兜底恢复界面状态
        if (!ModelCatalogService.state().loading) renderModelOptions()
    }

    /** 用目录内容重建下拉选项，并保留规则里已有关键字 */
    private fun renderModelOptions() {
        val st = ModelCatalogService.state()
        val current = currentKeyword()

        modelCombo.removeAllItems()
        for (m in st.models) modelCombo.addItem(m.id)
        for (rule in rules) {
            if (rule.keyword.isBlank()) continue
            val exists = (0 until modelCombo.itemCount).any { modelCombo.getItemAt(it) == rule.keyword }
            if (!exists) modelCombo.addItem(rule.keyword)
        }

        refreshModelsBtn.isEnabled = !st.loading
        modelStatusLabel.text = when {
            st.loading -> "加载中…"
            st.error != null -> "加载失败：${st.error}"
            st.loaded -> "已加载 ${st.models.size} 个模型"
            else -> "未加载模型目录"
        }
        if (current.isNotEmpty()) modelCombo.editor.item = current
    }

    private fun removeSelected() {
        val index = table.selectedRow
        if (index < 0 || index >= rules.size) return
        rules.removeAt(index)
        tableModel.fireTableDataChanged()
        syncButtons()
    }

    private fun moveSelected(offset: Int) {
        val index = table.selectedRow
        val target = index + offset
        if (index < 0 || index >= rules.size || target < 0 || target >= rules.size) return
        val item = rules.removeAt(index)
        rules.add(target, item)
        tableModel.fireTableDataChanged()
        table.selectionModel.setSelectionInterval(target, target)
        syncButtons()
    }

    private fun syncButtons() {
        val index = table.selectedRow
        val has = index >= 0 && index < rules.size
        removeBtn.isEnabled = has
        upBtn.isEnabled = has && index > 0
        downBtn.isEnabled = has && index < rules.size - 1
    }

    /** 表格模型：前两列可编辑，后三列只读展示实际消耗 */
    private inner class RulesTableModel : AbstractTableModel() {

        private val columns = arrayOf("模型关键字", "限额积分", "已用", "剩余", "状态")

        override fun getRowCount(): Int = rules.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0 || column == 1

        override fun getValueAt(row: Int, column: Int): Any {
            val rule = rules[row]
            val usage = ModelQuotaTracker.state().rules.firstOrNull { it.keyword == rule.keyword }
            return when (column) {
                0 -> rule.keyword
                1 -> formatCredit(rule.limit)
                2 -> usage?.let { formatCredit(it.used) } ?: "-"
                3 -> usage?.let { formatCredit(it.remain) } ?: "-"
                else -> usage?.statusText() ?: "-"
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (row < 0 || row >= rules.size) return
            val rule = rules[row]
            when (column) {
                0 -> rule.keyword = value?.toString()?.trim() ?: ""
                1 -> rule.limit = (value as? Number)?.toDouble()
                    ?: value?.toString()?.trim()?.toDoubleOrNull()
                    ?: rule.limit
            }
            fireTableCellUpdated(row, column)
        }
    }
}
