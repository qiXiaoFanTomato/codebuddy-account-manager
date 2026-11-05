package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.credential.CredentialPaths
import dev.codebuddy.account.scan.DirectoryScanner
import dev.codebuddy.account.scan.ScanEntry
import dev.codebuddy.account.scan.ScanListener
import dev.codebuddy.account.scan.ScanOptions
import dev.codebuddy.account.scan.ScanProgress
import dev.codebuddy.account.scan.ScanSummary
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.table.AbstractTableModel

/**
 * 目录扫描窗口：边扫边显示。
 *
 * 交互要点：
 *  - 扫描在后台线程执行，每发现一个文件立刻入队，EDT 按固定节拍批量追加到表格，
 *    因此界面能持续看到文件名在增长，而不是等扫描结束才一次性出现；
 *  - 进度区同时给出「已扫描文件数 / 目录数 / 累计大小 / 当前路径」与进度条；
 *  - 目录不存在、不是目录在启动前直接拦截；权限不足等单点失败只跳过该项，
 *    并汇总到「跳过明细」区，不中断整体扫描；
 *  - 关闭窗口即取消扫描。
 */
class DirectoryScanDialog(private val project: Project) : DialogWrapper(project) {

    private val log = logger<DirectoryScanDialog>()

    private val pathField = JBTextField()
    private val filterField = JBTextField()
    private val recursiveBox = JCheckBox("包含子目录", true)
    private val includeDirBox = JCheckBox("显示目录项", false)

    private val startBtn = JButton("开始扫描")
    private val stopBtn = JButton("停止")
    private val clearBtn = JButton("清空")
    private val closeBtn = JButton("关闭")

    private val statusLabel = JBLabel("就绪")
    private val progressBar = JProgressBar().apply {
        isIndeterminate = false
        isVisible = false
        preferredSize = Dimension(0, JBUI.scale(6))
    }
    private val errorArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
    }
    private val errorScroll = JBScrollPane(errorArea).apply {
        preferredSize = Dimension(0, JBUI.scale(90))
        isVisible = false
    }

    private val model = ScanTableModel()
    private val table = JBTable(model)

    /** 扫描线程写入，EDT 读取 */
    private val pending = ConcurrentLinkedQueue<ScanEntry>()
    private val pendingErrors = ConcurrentLinkedQueue<String>()
    private val progressFiles = AtomicInteger(0)
    private val progressDirs = AtomicInteger(0)
    private val progressBytes = AtomicLong(0)

    @Volatile
    private var currentPath: String = ""

    private val cancelFlag = AtomicBoolean(false)
    private val flushScheduled = AtomicBoolean(false)
    private var worker: Future<*>? = null

    init {
        title = "扫描目录"
        setSize(940, 640)
        pathField.text = CredentialPaths.dataRoot()?.toString() ?: System.getProperty("user.home")
        init()
    }

    // ---------------------------------------------------------------
    // 布局
    // ---------------------------------------------------------------

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(8)))
        root.add(buildTopArea(), BorderLayout.NORTH)
        root.add(buildTableArea(), BorderLayout.CENTER)
        root.add(buildBottomArea(), BorderLayout.SOUTH)
        return root
    }

    /** 只保留取消按钮，关闭即取消扫描 */
    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = pathField

    /** 目录输入 + 选项 */
    private fun buildTopArea(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val pathRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { isOpaque = false }
        pathRow.add(toolbarLabel("目录"), BorderLayout.WEST)
        pathRow.add(pathField, BorderLayout.CENTER)
        pathRow.add(browseBtn(), BorderLayout.EAST)
        pathRow.maximumSize = Dimension(Int.MAX_VALUE, pathRow.preferredSize.height)
        panel.add(pathRow)
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))

        val optionRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false }
        optionRow.add(recursiveBox)
        optionRow.add(includeDirBox)
        optionRow.add(toolbarLabel("文件名过滤"))
        filterField.preferredSize = Dimension(JBUI.scale(180), filterField.preferredSize.height)
        filterField.emptyText.text = "可留空，如 .json, index"
        optionRow.add(filterField)
        panel.add(optionRow)

        return panel
    }

    private fun toolbarLabel(text: String): JLabel = JLabel(text).apply {
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = AccountUiColors.textMuted
    }

    private fun browseBtn(): JComponent {
        val btn = JButton("浏览…")
        btn.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            val chosen = FileChooser.chooseFile(descriptor, project, null)
            if (chosen != null) pathField.text = chosen.path
        }
        return btn
    }

    private fun buildTableArea(): JComponent {
        AccountUiKit.styleTable(table, intArrayOf(520, 90, 150))
        table.emptyText.text = "尚未扫描"
        return JBScrollPane(table)
    }

    /** 进度条 + 状态行 + 跳过明细 */
    private fun buildBottomArea(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))

        panel.add(progressBar, BorderLayout.NORTH)

        val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { isOpaque = false }
        statusLabel.foreground = AccountUiColors.textMuted
        statusLabel.font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        statusRow.add(statusLabel, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        startBtn.addActionListener { startScan() }
        stopBtn.addActionListener { stopScan() }
        clearBtn.addActionListener { clearResults() }
        closeBtn.addActionListener { doCancelAction() }
        buttons.add(startBtn)
        buttons.add(stopBtn)
        buttons.add(clearBtn)
        buttons.add(closeBtn)
        statusRow.add(buttons, BorderLayout.EAST)
        panel.add(statusRow, BorderLayout.CENTER)

        errorArea.foreground = AccountUiColors.danger
        errorArea.font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        panel.add(errorScroll, BorderLayout.SOUTH)

        return panel
    }

    // ---------------------------------------------------------------
    // 扫描控制
    // ---------------------------------------------------------------

    private fun startScan() {
        val raw = pathField.text.trim()
        if (raw.isEmpty()) {
            showError("请填写要扫描的目录")
            return
        }
        val root: Path = try {
            Paths.get(raw)
        } catch (_: InvalidPathException) {
            showError("路径格式不正确：$raw")
            return
        }
        if (!Files.exists(root)) {
            showError("目录不存在：$root")
            return
        }
        if (!Files.isDirectory(root)) {
            showError("不是目录：$root")
            return
        }

        resetResults()
        cancelFlag.set(false)
        setScanning(true)

        val options = ScanOptions(
            recursive = recursiveBox.isSelected,
            includeDirectories = includeDirBox.isSelected,
            nameFilters = parseFilters(filterField.text),
        )

        worker = AppExecutorUtil.getAppExecutorService().submit(Runnable {
            try {
                DirectoryScanner.scan(root, options, scanListener, cancelFlag)
            } catch (e: Throwable) {
                log.warn("目录扫描异常终止: $root", e)
                ApplicationManager.getApplication().invokeLater({
                    showError("扫描异常终止：${e.message ?: e.javaClass.simpleName}")
                    setScanning(false)
                }, ModalityState.any())
            }
        })
    }

    private fun stopScan() {
        if (!cancelFlag.getAndSet(true)) {
            statusLabel.text = "正在停止…"
            statusLabel.foreground = AccountUiColors.warning
        }
    }

    private fun clearResults() {
        cancelFlag.set(true)
        resetResults()
        statusLabel.text = "已清空"
        statusLabel.foreground = AccountUiColors.textMuted
    }

    private fun resetResults() {
        pending.clear()
        pendingErrors.clear()
        progressFiles.set(0)
        progressDirs.set(0)
        progressBytes.set(0)
        currentPath = ""
        model.clear()
        errorArea.text = ""
        errorScroll.isVisible = false
    }

    private fun setScanning(scanning: Boolean) {
        startBtn.isEnabled = !scanning
        stopBtn.isEnabled = scanning
        pathField.isEnabled = !scanning
        filterField.isEnabled = !scanning
        recursiveBox.isEnabled = !scanning
        includeDirBox.isEnabled = !scanning
        progressBar.isVisible = scanning
        progressBar.isIndeterminate = scanning
        if (!scanning) {
            progressBar.isIndeterminate = false
            progressBar.value = 100
        }
    }

    // ---------------------------------------------------------------
    // 扫描回调（后台线程）
    // ---------------------------------------------------------------

    private val scanListener = object : ScanListener {

        override fun onEntry(entry: ScanEntry) {
            pending.add(entry)
            progressFiles.incrementAndGet()
            progressBytes.addAndGet(entry.size)
            scheduleFlush()
        }

        override fun onProgress(progress: ScanProgress) {
            progressDirs.set(progress.directories)
            currentPath = progress.currentPath
        }

        override fun onSkipped(path: Path, reason: String) {
            pendingErrors.add("$path — $reason")
            scheduleFlush()
        }

        override fun onFinished(summary: ScanSummary) {
            ApplicationManager.getApplication().invokeLater({
                flushNow()
                onScanFinished(summary)
            }, ModalityState.any())
        }
    }

    // ---------------------------------------------------------------
    // EDT 刷新
    // ---------------------------------------------------------------

    /** 节流刷新：多次入队只安排一次 EDT 任务，避免 EDT 队列堆积 */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({ flushNow() }, ModalityState.any())
    }

    private fun flushNow() {
        flushScheduled.set(false)
        val batch = ArrayList<ScanEntry>(256)
        while (batch.size < 500) {
            val entry = pending.poll() ?: break
            batch.add(entry)
        }
        if (batch.isNotEmpty()) {
            model.addRows(batch)
            val lastRow = model.rowCount - 1
            table.scrollRectToVisible(table.getCellRect(lastRow, 0, true))
        }

        val errors = ArrayList<String>()
        while (true) {
            val e = pendingErrors.poll() ?: break
            errors.add(e)
        }
        if (errors.isNotEmpty()) {
            errorArea.append(errors.joinToString("\n") + "\n")
            errorScroll.isVisible = true
        }

        updateStatus()
        if (pending.isNotEmpty()) scheduleFlush()
    }

    private fun updateStatus() {
        val current = currentPath
        statusLabel.text = buildString {
            append("已扫描 ").append(progressFiles.get()).append(" 个文件")
            append(" · ").append(progressDirs.get()).append(" 个目录")
            append(" · ").append(formatBytes(progressBytes.get()))
            if (current.isNotEmpty()) append(" · 当前：").append(current)
        }
        statusLabel.foreground = AccountUiColors.textMuted
    }

    private fun onScanFinished(summary: ScanSummary) {
        setScanning(false)
        statusLabel.text = buildString {
            if (summary.cancelled) append("已取消 · ")
            append("完成：").append(summary.files).append(" 个文件")
            append(" · ").append(summary.directories).append(" 个目录")
            append(" · ").append(formatBytes(summary.totalBytes))
            append(" · 用时 ").append(summary.elapsedMs).append(" ms")
            if (summary.skipped > 0) append(" · 跳过 ").append(summary.skipped).append(" 项")
            if (summary.truncated) append(" · 已达文件数上限")
        }
        statusLabel.foreground =
            if (summary.cancelled) AccountUiColors.warning else AccountUiColors.textPrimary
        if (summary.errors.isNotEmpty()) {
            errorArea.append(
                summary.errors.joinToString("\n") { "${it.path} — ${it.reason}" } + "\n"
            )
            errorScroll.isVisible = true
        }
    }

    private fun showError(message: String) {
        statusLabel.text = message
        statusLabel.foreground = AccountUiColors.danger
    }

    /** 过滤条件：逗号 / 分号 / 空格分隔 */
    private fun parseFilters(text: String): Set<String> =
        text.split(',', ';', ' ', '，', '；')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    override fun doCancelAction() {
        cancelFlag.set(true)
        super.doCancelAction()
    }

    override fun dispose() {
        cancelFlag.set(true)
        super.dispose()
    }

    companion object {
        fun show(project: Project) {
            DirectoryScanDialog(project).show()
        }
    }
}

/** 扫描结果表格：文件 / 大小 / 修改时间 */
private class ScanTableModel : AbstractTableModel() {

    private val rows = ArrayList<ScanEntry>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 3

    override fun getColumnName(column: Int): String = when (column) {
        0 -> "文件"
        1 -> "大小"
        else -> "修改时间"
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = rows[rowIndex]
        return when (columnIndex) {
            0 -> entry.relativePath
            1 -> if (entry.isDirectory) "—" else formatBytes(entry.size)
            else -> formatTime(entry.modifiedAtMillis)
        }
    }

    /** 批量追加：一次事件通知，避免逐行刷新拖慢 EDT */
    fun addRows(batch: List<ScanEntry>) {
        if (batch.isEmpty()) return
        val first = rows.size
        rows.addAll(batch)
        fireTableRowsInserted(first, rows.size - 1)
    }

    fun clear() {
        rows.clear()
        fireTableDataChanged()
    }
}

/** 字节数格式化 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format("%.1f %s", value, units[index])
}

/** 毫秒时间戳格式化 */
internal fun formatTime(millis: Long): String {
    if (millis <= 0) return "—"
    return TIME_FMT.format(Instant.ofEpochMilli(millis))
}

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
