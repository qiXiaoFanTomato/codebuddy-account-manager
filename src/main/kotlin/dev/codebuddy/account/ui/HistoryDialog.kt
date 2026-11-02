package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.credential.CredentialPaths
import dev.codebuddy.account.history.HistoryReader
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 历史对话浏览器。
 *
 * 能力：
 *  - 跨账号汇总所有本地历史会话（只读）
 *  - 关键字 + 账号筛选，按「今天 / 昨天 / 更早」分组
 *  - 复制当前会话、导出 Markdown、打开会话所在目录
 *
 * 性能约束：
 *  - 会话列表在后台线程扫描（避免阻塞 EDT 导致卡死）
 *  - 消息内容按需加载，切换会话时取消上一次的加载
 *  - 列表使用 DefaultListModel 增量填充，不做全量重建
 */
class HistoryDialog(
    private val project: Project,
    private val accountLabels: Map<String, String>,
) : DialogWrapper(project) {

    /** 列表元素：分组标题（String）或会话（Conversation） */
    private val listModel = DefaultListModel<Any>()
    private val convList = JBList(listModel)
    private val contentArea = JBTextArea()
    private val headerLabel = JBLabel("正在扫描历史对话…")
    private val searchField = SearchTextField(false)
    private val accountCombo = JComboBox<AccountChoice>()
    private val loadingLabel = JBLabel("")
    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(0, JBUI.scale(4))
    }

    private var allConversations: List<HistoryReader.Conversation> = emptyList()
    private var filterText: String = ""
    private var currentConversation: HistoryReader.Conversation? = null
    private var loadToken = 0

    /** 重建下拉框时抑制 action 回调，避免重复过滤 */
    private var suppressCombo = false

    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val zone = ZoneId.systemDefault()

    init {
        title = "CodeBuddy 历史对话"
        setSize(1180, 740)

        convList.cellRenderer = ConversationRenderer()
        convList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) onSelect()
        }

        contentArea.isEditable = false
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        contentArea.border = JBUI.Borders.empty(8)

        loadingLabel.foreground = AccountUiColors.textMuted
        loadingLabel.border = JBUI.Borders.empty(4, 8)

        // 账号下拉：先放「全部账号」，扫描完成后补齐实际账号，避免初始为空
        accountCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? AccountChoice)?.label ?: "", index, isSelected, cellHasFocus
            )
        }
        accountCombo.addItem(AccountChoice(null, "全部账号"))

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        accountCombo.addActionListener { if (!suppressCombo) applyFilter() }

        init()
        loadAsync()
    }

    override fun createCenterPanel(): JComponent {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        split.dividerLocation = 460
        split.resizeWeight = 0.38

        // 左侧：筛选 + 列表
        val left = JPanel(BorderLayout())

        val filterPanel = JPanel()
        filterPanel.layout = BoxLayout(filterPanel, BoxLayout.Y_AXIS)
        filterPanel.border = JBUI.Borders.empty(6)

        val searchRow = JPanel(BorderLayout())
        searchRow.add(searchField, BorderLayout.CENTER)

        val accountRow = JPanel(BorderLayout(6, 0))
        accountRow.border = JBUI.Borders.emptyTop(6)
        accountCombo.preferredSize = Dimension(JBUI.scale(260), accountCombo.preferredSize.height)
        accountRow.add(JBLabel("账号:"), BorderLayout.WEST)
        accountRow.add(accountCombo, BorderLayout.CENTER)

        filterPanel.add(searchRow)
        filterPanel.add(accountRow)

        left.add(filterPanel, BorderLayout.NORTH)
        left.add(JBScrollPane(convList), BorderLayout.CENTER)

        val statusPanel = JPanel(BorderLayout())
        statusPanel.add(progressBar, BorderLayout.NORTH)
        statusPanel.add(loadingLabel, BorderLayout.CENTER)
        left.add(statusPanel, BorderLayout.SOUTH)
        split.leftComponent = left

        // 右侧：内容 + 操作
        val right = JPanel(BorderLayout())
        headerLabel.border = JBUI.Borders.empty(8, 10)
        headerLabel.font = headerLabel.font.deriveFont(Font.BOLD)
        right.add(headerLabel, BorderLayout.NORTH)
        right.add(JBScrollPane(contentArea), BorderLayout.CENTER)
        right.add(buildActionBar(), BorderLayout.SOUTH)
        split.rightComponent = right

        return split
    }

    private fun buildActionBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        bar.add(iconButton("复制内容") { copyContent() })
        bar.add(iconButton("导出 Markdown") { exportMarkdown() })
        bar.add(iconButton("打开会话目录") { openConversationDir() })
        return bar
    }

    private fun iconButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        isFocusPainted = false
        margin = JBUI.insets(3, 8)
        addActionListener { action() }
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = convList

    // ---------------------------------------------------------------
    // 异步加载
    // ---------------------------------------------------------------

    private fun loadAsync() {
        val dataRoot = CredentialPaths.dataRoot()
        if (dataRoot == null) {
            headerLabel.text = "找不到 CodeBuddy 数据目录"
            loadingLabel.text = "未找到 CodeBuddyExtension/Data 目录"
            return
        }
        loadingLabel.text = "扫描中…"
        progressBar.isVisible = true
        val startedAt = System.currentTimeMillis()

        ApplicationManager.getApplication().executeOnPooledThread {
            val list: List<HistoryReader.Conversation> = try {
                HistoryReader.listAllConversations(dataRoot) { scanned, total, label ->
                    ApplicationManager.getApplication().invokeLater {
                        if (progressBar.isVisible) {
                            loadingLabel.text = "扫描中… $scanned/$total 个账号（${label}）"
                        }
                    }
                }
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    progressBar.isVisible = false
                    headerLabel.text = "扫描历史会话失败"
                    loadingLabel.text = t.message ?: t.javaClass.simpleName
                }
                return@executeOnPooledThread
            }

            ApplicationManager.getApplication().invokeLater {
                try {
                    progressBar.isVisible = false
                    allConversations = list
                    initAccountCombo(list)
                    applyFilter()
                    val cost = System.currentTimeMillis() - startedAt
                    headerLabel.text = if (list.isEmpty()) {
                        "未找到历史会话"
                    } else {
                        "选择左侧会话查看内容"
                    }
                    if (list.isEmpty()) {
                        loadingLabel.text = "已扫描 Data 目录，未发现可读的 index.json（耗时 $cost ms）"
                    } else {
                        loadingLabel.text = "共 ${list.size} 个会话（耗时 $cost ms）"
                    }
                } catch (t: Throwable) {
                    headerLabel.text = "渲染历史会话失败"
                    loadingLabel.text = t.message ?: t.javaClass.simpleName
                }
            }
        }
    }

    /** 账号下拉：全部 + 会话里实际出现的账号 */
    private fun initAccountCombo(list: List<HistoryReader.Conversation>) {
        suppressCombo = true
        accountCombo.removeAllItems()
        accountCombo.addItem(AccountChoice(null, "全部账号"))
        list.map { it.sourceUid }
            .distinct()
            .forEach { uid ->
                accountCombo.addItem(AccountChoice(uid, accountLabels[uid] ?: uid.take(8)))
            }
        accountCombo.selectedIndex = 0
        suppressCombo = false
    }

    private fun applyFilter() {
        filterText = searchField.text.trim()
        val accountFilter = (accountCombo.selectedItem as? AccountChoice)?.uid

        val filtered = allConversations.filter { conv ->
            val label = accountLabels[conv.sourceUid] ?: conv.sourceUid
            val okAccount = accountFilter == null || conv.sourceUid == accountFilter
            val okText = filterText.isEmpty() ||
                conv.name.contains(filterText, ignoreCase = true) ||
                label.contains(filterText, ignoreCase = true) ||
                conv.workspace.contains(filterText, ignoreCase = true)
            okAccount && okText
        }

        listModel.clear()
        var group = ""
        for (conv in filtered) {
            val g = groupOf(conv.lastMessageAt)
            if (g != group) {
                group = g
                listModel.addElement("— $g —")
            }
            listModel.addElement(conv)
        }

        loadingLabel.text = "共 ${allConversations.size} 个会话" +
            if (filterText.isNotEmpty() || accountFilter != null) "，匹配 ${filtered.size} 个" else ""
    }

    private fun groupOf(lastMessageAt: String): String {
        val date = try {
            if (lastMessageAt.isBlank()) null else Instant.parse(lastMessageAt).atZone(zone).toLocalDate()
        } catch (_: Exception) { null } ?: return "更早"

        val today = LocalDate.now(zone)
        return when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            date.isAfter(today.minusDays(8)) -> "最近 7 天"
            else -> "更早"
        }
    }

    private fun onSelect() {
        val conv = convList.selectedValue as? HistoryReader.Conversation ?: return
        currentConversation = conv
        val token = ++loadToken

        val accountLabel = accountLabels[conv.sourceUid] ?: conv.sourceUid.take(8)
        headerLabel.text = "${conv.name}   ·   $accountLabel"
        contentArea.text = "加载中…"
        contentArea.caretPosition = 0

        ApplicationManager.getApplication().executeOnPooledThread {
            val messages = HistoryReader.readMessages(conv)
            val sb = StringBuilder()
            if (messages.isEmpty()) {
                sb.append("（未解析到消息内容）\n\n")
                sb.append("会话 ID: ${conv.id}\n")
                sb.append("消息目录: ${conv.messageDir ?: "不存在"}\n")
            } else {
                for (m in messages) {
                    val time = try {
                        if (m.timestamp.isBlank()) "" else " " + timeFmt.format(Instant.parse(m.timestamp))
                    } catch (_: Exception) { "" }
                    sb.append("── [${m.role}]$time ──\n")
                    sb.append(m.content.trim())
                    sb.append("\n\n")
                }
            }
            val text = sb.toString()
            val count = if (messages.isNotEmpty()) messages.size else HistoryReader.countMessages(conv)
            ApplicationManager.getApplication().invokeLater {
                // 若期间用户已切换到别的会话，丢弃这次结果
                if (token != loadToken) return@invokeLater
                contentArea.text = text
                contentArea.caretPosition = 0
                headerLabel.text = "${conv.name}   ·   $count 条   ·   $accountLabel"
            }
        }
    }

    // ---------------------------------------------------------------
    // 操作
    // ---------------------------------------------------------------

    private fun copyContent() {
        val text = contentArea.text
        if (text.isBlank()) return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        loadingLabel.text = "已复制当前会话内容"
    }

    private fun exportMarkdown() {
        val conv = currentConversation ?: run {
            Messages.showInfoMessage(project, "请先选择一个会话。", "导出会话")
            return
        }
        val descriptor = FileSaverDescriptor("导出会话", "选择保存位置", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val safeName = conv.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "conversation" }
        val wrapper = dialog.save(null as java.nio.file.Path?, "$safeName.md") ?: return

        val accountLabel = accountLabels[conv.sourceUid] ?: conv.sourceUid
        val body = buildString {
            append("# ${conv.name}\n\n")
            append("- 账号：$accountLabel\n")
            append("- 工作区：${conv.workspace}\n")
            append("- 会话 ID：${conv.id}\n")
            append("- 最后消息：${conv.lastMessageAt}\n\n")
            append("---\n\n")
            append(contentArea.text)
        }
        try {
            Files.writeString(wrapper.file.toPath(), body, StandardCharsets.UTF_8)
            loadingLabel.text = "已导出：${wrapper.file.name}"
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: "未知错误", "导出失败")
        }
    }

    private fun openConversationDir() {
        val conv = currentConversation ?: run {
            Messages.showInfoMessage(project, "请先选择一个会话。", "打开目录")
            return
        }
        val msgDir = conv.messageDir
        val dir = msgDir?.let { if (Files.isDirectory(it)) it else it.parent }
        if (dir == null) {
            Messages.showWarningDialog(project, "该会话没有本地消息目录。", "打开目录")
            return
        }
        try {
            Desktop.getDesktop().open(dir.toFile())
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "无法打开目录：${e.message}", "打开目录")
        }
    }

    // ---------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------

    private inner class ConversationRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            when (value) {
                is HistoryReader.Conversation -> {
                    c.text = value.summaryLine(accountLabels[value.sourceUid] ?: value.sourceUid.take(8))
                    c.border = JBUI.Borders.empty(6, 8)
                }

                is String -> {
                    c.text = value
                    c.font = c.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                    c.foreground = AccountUiColors.textMuted
                    c.border = JBUI.Borders.empty(8, 6, 2, 6)
                }
            }
            return c
        }
    }

    /** 账号筛选项 */
    private data class AccountChoice(val uid: String?, val label: String) {
        override fun toString(): String = label
    }

    companion object {
        /** 打开对话框；数据异步加载，因此始终返回 */
        fun show(project: Project, accountLabels: Map<String, String>) {
            HistoryDialog(project, accountLabels).show()
        }
    }
}
