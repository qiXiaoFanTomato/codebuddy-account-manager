package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
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
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.credential.CredentialPaths
import dev.codebuddy.account.history.HistoryReader
import dev.codebuddy.account.history.ProjectResolver
import dev.codebuddy.account.scan.ScanProgress
import dev.codebuddy.account.scan.ScanSummary
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 历史对话浏览器：目录扫描与历史会话浏览合并在同一窗口。
 *
 * 能力：
 *  - 通过目录扫描功能扫描所选目录的 index.json（可重扫、可停止、含跳过明细）
 *  - 跨账号汇总所有本地历史会话（只读）
 *  - 关键字 + 账号筛选，可按时间或按项目分组；项目名来自项目路径清单精确反查
 *  - 复制当前会话、导出 Markdown、打开会话所在目录
 *
 * 性能约束：
 *  - 会话列表在后台线程扫描（避免阻塞 EDT 导致卡死）
 *  - 目录扫描剪掉 messages 子树，消息内容按需加载
 *  - 切换会话时取消上一次的加载
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
    private val groupCombo = JComboBox<GroupMode>()
    private lateinit var topBar: DialogTopBar
    private val loadingLabel = JBLabel("")
    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(0, JBUI.scale(4))
    }

    private val dirField = JBTextField()
    private val recursiveBox = JCheckBox("包含子目录", true)
    private val scanBtn = JButton("扫描")
    private val stopBtn = JButton("停止")
    private val scanStatusLabel = JBLabel("就绪")
    private val errorArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
    }
    private val errorScroll = JBScrollPane(errorArea).apply {
        preferredSize = Dimension(0, JBUI.scale(80))
        isVisible = false
    }

    /** 当前扫描的取消标志，关闭窗口或点「停止」时置位 */
    @Volatile
    private var cancelFlag = AtomicBoolean(false)
    private val progressRef = AtomicReference<ScanProgress?>(null)
    private val progressScheduled = AtomicBoolean(false)
    private var scanToken = 0

    private var allConversations: List<HistoryReader.Conversation> = emptyList()
    private var filterText: String = ""
    private var currentConversation: HistoryReader.Conversation? = null
    private var loadToken = 0

    /** workspace hash -> 项目名 / 项目目录，扫描完成后由 [ProjectResolver] 解析 */
    private var projectNames: Map<String, String> = emptyMap()
    private var projectPaths: Map<String, String?> = emptyMap()

    /** 重建下拉框时抑制 action 回调，避免重复过滤 */
    private var suppressCombo = false

    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val zone = ZoneId.systemDefault()

    init {
        title = "CodeBuddy 历史对话"
        setSize(1180, 740)
        dirField.text = CredentialPaths.dataRoot()?.toString() ?: ""

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

        for (mode in GroupMode.entries) groupCombo.addItem(mode)
        groupCombo.addActionListener { if (!suppressCombo) applyFilter() }

        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        accountCombo.addActionListener { if (!suppressCombo) applyFilter() }

        init()
        if (dirField.text.isNotBlank()) {
            startScan()
        } else {
            headerLabel.text = "找不到 CodeBuddy 数据目录"
            loadingLabel.text = "等待选择扫描目录"
            setScanError("未找到 CodeBuddyExtension/Data 目录，请通过「浏览…」选择目录")
        }
    }

    override fun createCenterPanel(): JComponent {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        split.dividerLocation = 460
        split.resizeWeight = 0.38

        // 顶部：标题 + 账号筛选 + 分组维度（统一顶栏样式）
        accountCombo.preferredSize = Dimension(JBUI.scale(240), accountCombo.preferredSize.height)
        groupCombo.preferredSize = Dimension(JBUI.scale(110), groupCombo.preferredSize.height)
        val accountRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        accountRow.add(JBLabel("账号"))
        accountRow.add(accountCombo)
        accountRow.add(JBLabel("分组"))
        accountRow.add(groupCombo)
        val top = DialogTopBar("CodeBuddy 历史对话", "", accountRow)
        topBar = top

        // 左侧：筛选 + 列表
        val left = JPanel(BorderLayout())

        val filterPanel = JPanel()
        filterPanel.layout = BoxLayout(filterPanel, BoxLayout.Y_AXIS)
        filterPanel.border = JBUI.Borders.empty(6)
        filterPanel.add(buildScanPanel())
        filterPanel.add(Box.createVerticalStrut(JBUI.scale(6)))

        val searchRow = JPanel(BorderLayout())
        searchRow.add(searchField, BorderLayout.CENTER)
        filterPanel.add(searchRow)

        left.add(filterPanel, BorderLayout.NORTH)
        left.add(JBScrollPane(convList), BorderLayout.CENTER)

        val statusPanel = JPanel(BorderLayout())
        statusPanel.add(progressBar, BorderLayout.NORTH)
        val statusBox = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        statusBox.add(loadingLabel)
        scanStatusLabel.foreground = AccountUiColors.textMuted
        scanStatusLabel.font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        scanStatusLabel.border = JBUI.Borders.empty(2, 8, 4, 8)
        statusBox.add(scanStatusLabel)
        statusPanel.add(statusBox, BorderLayout.CENTER)
        errorArea.foreground = AccountUiColors.danger
        errorArea.font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        statusPanel.add(errorScroll, BorderLayout.SOUTH)
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

        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.panelBg
        root.add(top, BorderLayout.NORTH)
        root.add(split, BorderLayout.CENTER)
        return root
    }

    /** 扫描目录区域：目录 + 递归选项 + 扫描 / 停止 */
    private fun buildScanPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val pathRow = JPanel(BorderLayout(JBUI.scale(6), 0))
        pathRow.add(JBLabel("扫描目录"), BorderLayout.WEST)
        pathRow.add(dirField, BorderLayout.CENTER)
        val browseBtn = JButton("浏览…")
        browseBtn.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            val chosen = FileChooser.chooseFile(descriptor, project, null)
            if (chosen != null) {
                dirField.text = chosen.path
                startScan()
            }
        }
        pathRow.add(browseBtn, BorderLayout.EAST)
        pathRow.maximumSize = Dimension(Int.MAX_VALUE, pathRow.preferredSize.height)
        panel.add(pathRow)
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))

        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        actionRow.add(recursiveBox)
        scanBtn.addActionListener { startScan() }
        stopBtn.addActionListener { stopScan() }
        stopBtn.isEnabled = false
        actionRow.add(scanBtn)
        actionRow.add(stopBtn)
        actionRow.maximumSize = Dimension(Int.MAX_VALUE, actionRow.preferredSize.height)
        panel.add(actionRow)
        return panel
    }

    private fun buildActionBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        bar.add(iconButton("复制内容") { copyContent() })
        bar.add(iconButton("导出 Markdown") { exportMarkdown() })
        bar.add(iconButton("打开会话目录") { openConversationDir() })
        return bar
    }

    private fun iconButton(text: String, action: () -> Unit): JComponent =
        JellyButton(text, JellyButtonVariant.SECONDARY, null, text, action)

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = convList

    override fun doCancelAction() {
        cancelFlag.set(true)
        super.doCancelAction()
    }

    override fun dispose() {
        cancelFlag.set(true)
        super.dispose()
    }

    // ---------------------------------------------------------------
    // 目录扫描（统一复用 DirectoryScanner）
    // ---------------------------------------------------------------

    /** 扫描所选目录中的历史对话；重复调用会先中止上一次扫描 */
    private fun startScan() {
        val raw = dirField.text.trim()
        if (raw.isEmpty()) {
            setScanError("请填写要扫描的目录")
            return
        }
        val root: Path = try {
            Paths.get(raw)
        } catch (_: InvalidPathException) {
            setScanError("路径格式不正确：$raw")
            return
        }
        if (!Files.exists(root)) {
            setScanError("目录不存在：$root")
            return
        }
        if (!Files.isDirectory(root)) {
            setScanError("不是目录：$root")
            return
        }

        cancelFlag.set(true)
        val cancel = AtomicBoolean(false)
        cancelFlag = cancel
        val token = ++scanToken

        resetScanView()
        val options = HistoryReader.scanOptions(recursiveBox.isSelected)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                HistoryReader.listAllConversations(
                    root = root,
                    options = options,
                    progress = { p ->
                        progressRef.set(p)
                        scheduleProgressFlush()
                    },
                    cancel = cancel,
                )
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater({
                    if (token != scanToken) return@invokeLater
                    finishScanView()
                    headerLabel.text = "扫描历史会话失败"
                    setScanError(t.message ?: t.javaClass.simpleName)
                }, ModalityState.any())
                return@executeOnPooledThread
            }

            // 项目名解析涉及读盘（清单未命中时采样消息），留在后台线程
            val projects = try {
                ProjectResolver.resolve(result.conversations)
            } catch (_: Throwable) {
                emptyMap<String, ProjectResolver.Project>()
            }

            ApplicationManager.getApplication().invokeLater({
                if (token != scanToken) return@invokeLater
                try {
                    finishScanView()
                    allConversations = result.conversations
                    projectNames = projects.mapValues { (_, p) -> p.name }
                    projectPaths = projects.mapValues { (_, p) -> p.path }
                    initAccountCombo(result.conversations)
                    applyFilter()
                    headerLabel.text = if (result.conversations.isEmpty()) {
                        "未找到历史会话"
                    } else {
                        "选择左侧会话查看内容"
                    }
                    showSummary(result.summary)
                } catch (t: Throwable) {
                    headerLabel.text = "渲染历史会话失败"
                    setScanError(t.message ?: t.javaClass.simpleName)
                }
            }, ModalityState.any())
        }
    }

    private fun stopScan() {
        if (!cancelFlag.getAndSet(true)) {
            scanStatusLabel.text = "正在停止…"
            scanStatusLabel.foreground = AccountUiColors.warning
        }
    }

    private fun resetScanView() {
        listModel.clear()
        allConversations = emptyList()
        currentConversation = null
        contentArea.text = ""
        errorArea.text = ""
        errorScroll.isVisible = false
        progressRef.set(null)
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
        scanBtn.isEnabled = false
        stopBtn.isEnabled = true
        dirField.isEnabled = false
        recursiveBox.isEnabled = false
        headerLabel.text = "正在扫描历史对话…"
        loadingLabel.text = "扫描中…"
        scanStatusLabel.text = "正在扫描目录…"
        scanStatusLabel.foreground = AccountUiColors.textMuted
        syncTopBar()
    }

    private fun finishScanView() {
        progressBar.isVisible = false
        progressBar.isIndeterminate = false
        scanBtn.isEnabled = true
        stopBtn.isEnabled = false
        dirField.isEnabled = true
        recursiveBox.isEnabled = true
    }

    /** 节流刷新进度：目录扫描回调很密集，避免 EDT 队列堆积 */
    private fun scheduleProgressFlush() {
        if (!progressScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({ flushProgress() }, ModalityState.any())
    }

    private fun flushProgress() {
        val p = progressRef.getAndSet(null)
        progressScheduled.set(false)
        if (p != null && progressBar.isVisible) {
            scanStatusLabel.text =
                "扫描中… ${p.files} 个命中文件 · ${p.directories} 个目录 · ${p.currentPath}"
        }
        if (progressRef.get() != null) scheduleProgressFlush()
    }

    private fun showSummary(summary: ScanSummary) {
        scanStatusLabel.text = buildString {
            if (summary.cancelled) append("已取消 · ")
            append("扫描完成：").append(summary.files).append(" 个命中文件")
            append(" · ").append(summary.directories).append(" 个目录")
            append(" · 用时 ").append(summary.elapsedMs).append(" ms")
            if (summary.skipped > 0) append(" · 跳过 ").append(summary.skipped).append(" 项")
            if (summary.truncated) append(" · 已达扫描上限")
        }
        scanStatusLabel.foreground =
            if (summary.cancelled) AccountUiColors.warning else AccountUiColors.textPrimary
        if (summary.errors.isNotEmpty()) {
            errorArea.append(
                summary.errors.joinToString("\n") { "${it.path} — ${it.reason}" } + "\n"
            )
            errorScroll.isVisible = true
        }
        if (allConversations.isEmpty() && !summary.cancelled) {
            loadingLabel.text = "已扫描所选目录，未发现可读的 index.json"
            syncTopBar()
        }
    }

    private fun setScanError(message: String) {
        scanStatusLabel.text = message
        scanStatusLabel.foreground = AccountUiColors.danger
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
        val accountFilter = (accountCombo.selectedItem as? AccountChoice)?.key
        val mode = groupCombo.selectedItem as? GroupMode ?: GroupMode.TIME

        val filtered = allConversations.filter { conv ->
            val label = accountLabels[conv.sourceUid] ?: conv.sourceUid
            val project = projectNames[conv.workspace] ?: "未标注项目"
            val okAccount = accountFilter == null || conv.sourceUid == accountFilter
            val okText = filterText.isEmpty() ||
                conv.name.contains(filterText, ignoreCase = true) ||
                label.contains(filterText, ignoreCase = true) ||
                project.contains(filterText, ignoreCase = true) ||
                conv.workspace.contains(filterText, ignoreCase = true)
            okAccount && okText
        }

        // 稳定排序：按项目聚拢，组内沿用扫描结果的时间倒序
        val ordered = if (mode == GroupMode.PROJECT) {
            filtered.sortedBy { projectNames[it.workspace] ?: "未标注项目" }
        } else {
            filtered
        }

        listModel.clear()
        var group = ""
        for (conv in ordered) {
            val g = if (mode == GroupMode.PROJECT) {
                projectNames[conv.workspace] ?: "未标注项目"
            } else {
                groupOf(conv.lastMessageAt)
            }
            if (g != group) {
                group = g
                listModel.addElement("— $g —")
            }
            listModel.addElement(conv)
        }

        loadingLabel.text = "共 ${allConversations.size} 个会话" +
            if (filterText.isNotEmpty() || accountFilter != null) "，匹配 ${filtered.size} 个" else ""
        syncTopBar()
    }

    /** 顶栏副标题跟随扫描 / 筛选状态 */
    private fun syncTopBar() {
        if (!::topBar.isInitialized) return
        topBar.update("CodeBuddy 历史对话", loadingLabel.text)
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
            try {
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
                        sb.append("── ${roleLabel(m.role)}$time ──\n")
                        sb.append(m.content.trim())
                        sb.append("\n\n")
                    }
                }
                val text = sb.toString()
                val count = if (messages.isNotEmpty()) messages.size else HistoryReader.countMessages(conv)
                // 窗口是模态对话框：必须显式指定 ModalityState.any()，否则回调被挂起
                ApplicationManager.getApplication().invokeLater({
                    // 若期间用户已切换到别的会话，丢弃这次结果
                    if (token != loadToken) return@invokeLater
                    contentArea.text = text
                    contentArea.caretPosition = 0
                    headerLabel.text = "${conv.name}   ·   $count 条   ·   $accountLabel"
                }, ModalityState.any())
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater({
                    if (token != loadToken) return@invokeLater
                    contentArea.text = "读取会话消息失败：${t.message ?: t.javaClass.simpleName}"
                    contentArea.caretPosition = 0
                }, ModalityState.any())
            }
        }
    }

    // ---------------------------------------------------------------
    // 操作
    // ---------------------------------------------------------------

    /** 角色名转中文，便于阅读 */
    private fun roleLabel(role: String): String = when (role.lowercase()) {
        "user" -> "用户"
        "assistant" -> "助手"
        "tool" -> "工具"
        "system" -> "系统"
        else -> role
    }

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
            append("- 项目：${projectNames[conv.workspace] ?: "未标注项目"}\n")
            projectPaths[conv.workspace]?.let { append("- 项目目录：$it\n") }
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
                    c.font = c.font.deriveFont(Font.BOLD, AccountUiType.label)
                    c.foreground = AccountUiColors.textMuted
                    c.border = JBUI.Borders.empty(8, 6, 2, 6)
                }
            }
            return c
        }
    }

    /** 账号筛选项 */

    companion object {
        /** 打开对话框；数据异步加载，因此始终返回 */
        fun show(project: Project, accountLabels: Map<String, String>) {
            HistoryDialog(project, accountLabels).show()
        }
    }
}

/** 历史对话列表的分组维度 */
private enum class GroupMode(val label: String) {
    TIME("按时间"),
    PROJECT("按项目"),
    ;

    override fun toString(): String = label
}
