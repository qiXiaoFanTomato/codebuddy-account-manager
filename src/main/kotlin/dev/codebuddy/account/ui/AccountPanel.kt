package dev.codebuddy.account.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.history.HistoryReader
import dev.codebuddy.account.hotswap.CodeBuddyBridge
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.service.QuotaScheduler
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.SwitchResult
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 账号管理主面板（卡片式）。
 */
class AccountPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val library = AccountLibrary.getInstance()
    private val gridView = AccountGridView()
    private val statusLabel = JBLabel("就绪")
    private val rangeLabel = JBLabel("")
    private val filterField = JBTextField(14)
    private var sortMode: AccountSortMode = AccountSortMode.BY_DOMAIN
    private val emptyPanel = buildEmptyPanel()

    /** 当前中心视图：首页看板 / 卡片视图 */
    private var viewMode: String = VIEW_DASHBOARD

    private val dashboard = DashboardPanel(
        onOpenList = { setView(VIEW_LIST) },
        onRefreshAll = { refreshQuotas(library.listSaved()) },
        onOpenQuotaDetail = { QuotaDetailDialog.show(project) },
        onOpenUsageDetail = { UsageDetailDialog.show(project) },
        onOpenHistory = { doOpenHistory() },
        onOpenReport = { doOpenReport() },
        onCheckin = { doCheckin() },
        onOpenSettings = { doOpenSettings() },
    )

    private lateinit var boardBtn: JToggleButton
    private lateinit var listBtn: JToggleButton
    private lateinit var refreshTile: IconTile
    private lateinit var batchTile: IconTile
    private lateinit var selectAllBox: JCheckBox
    private lateinit var totalChip: Chip
    private lateinit var selectionBar: JComponent
    private lateinit var guideBody: JComponent
    private lateinit var guideArrow: JLabel
    private lateinit var pagerPanel: JComponent
    private lateinit var pageLabel: JLabel
    private lateinit var prevPageBtn: JButton
    private lateinit var nextPageBtn: JButton
    private lateinit var pageSizeCombo: JComboBox<String>

    init {
        background = AccountUiColors.panelBg

        add(buildTopBar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildFooterBar(), BorderLayout.SOUTH)

        gridView.onSelectionChanged = { syncSelectionState() }
        gridView.onPageChanged = { updatePager() }
        gridView.onAction = { card, action -> handleGridAction(card, action) }

        // 多项目窗口同步：任一窗口改动账号库 / 配额后，其他窗口的面板跟着刷新
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(AccountEvents.TOPIC, object : AccountEvents {
                override fun accountsChanged() = scheduleRefresh()
                override fun quotaChanged() = scheduleRefresh()
            })

        refresh()
    }

    /** 外部数据变更后刷新本面板（切回 EDT，避免跨线程操作 Swing） */
    private fun scheduleRefresh() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            CodeBuddyStatusBarWidget.invalidateCache()
            refresh()
        }
    }

    // ---------------------------------------------------------------
    // 布局
    // ---------------------------------------------------------------

    private fun buildTopBar(): JComponent {
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.background = AccountUiColors.surfaceBg
        root.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8)
        )
        root.add(buildGuidePanel())
        root.add(Box.createVerticalStrut(JBUI.scale(6)))
        root.add(buildSearchBar())
        root.add(Box.createVerticalStrut(JBUI.scale(4)))
        root.add(buildSelectionBar())
        return root
    }

    /** 顶部说明条：可折叠，文案只描述本插件真实读写范围 */
    private fun buildGuidePanel(): JComponent {
        val card = object : RoundedCard(
            AccountUiColors.accentSoft, AccountUiColors.borderActive, AccountUiRadius.inner
        ) {}
        card.border = JBUI.Borders.empty(7, 10)

        val titleRow = FullWidthRow(BorderLayout())
        val title = JLabel("CodeBuddy 账号管理说明（点击展开 / 收起）").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.accent
        }
        guideArrow = JLabel("▸").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.accent
        }
        titleRow.add(title, BorderLayout.WEST)
        titleRow.add(guideArrow, BorderLayout.EAST)

        guideBody = FullWidthColumn().apply {
            isVisible = false
            border = JBUI.Borders.emptyTop(5)
            add(guideLine("切换账号：读取本机 auth 目录的 .info 凭据写入 IDE 的 secret-storage.xml，写入前自动备份。"))
            add(guideLine("权限范围：只读写本机 CodeBuddy 凭据与账号库目录，不访问系统钥匙串。"))
            add(guideLine("网络范围：额度与消耗明细请求 codebuddy.cn / workbuddy.cn 接口；凭据不上传、不代理。"))
        }

        card.add(titleRow)
        card.add(guideBody)
        installGuideToggle(titleRow)
        return card
    }

    /** 说明条标题行可点击展开 / 收起 */
    private fun installGuideToggle(target: Component) {
        target.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                guideBody.isVisible = !guideBody.isVisible
                guideArrow.text = if (guideBody.isVisible) "▾" else "▸"
                var p: Container? = guideBody.parent
                while (p != null) {
                    p.revalidate()
                    p = p.parent
                }
                guideBody.repaint()
            }

            override fun mouseEntered(e: MouseEvent) {
                target.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            override fun mouseExited(e: MouseEvent) {
                target.cursor = Cursor.getDefaultCursor()
            }
        })
        if (target is Container) {
            for (child in target.components) installGuideToggle(child)
        }
    }

    private fun guideLine(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
        foreground = AccountUiColors.textMuted
        toolTipText = text
        border = JBUI.Borders.empty(1, 0)
    }

    /** 搜索 / 视图切换 / 排序 + 右侧图标操作 */
    private fun buildSearchBar(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }

        boardBtn = JToggleButton("首页", viewMode == VIEW_DASHBOARD).apply {
            isFocusPainted = false
            margin = JBUI.insets(3, 10)
            addActionListener { setView(VIEW_DASHBOARD) }
        }
        listBtn = JToggleButton("卡片视图", viewMode == VIEW_LIST).apply {
            isFocusPainted = false
            margin = JBUI.insets(3, 10)
            addActionListener { setView(VIEW_LIST) }
        }
        ButtonGroup().apply {
            add(boardBtn)
            add(listBtn)
        }

        filterField.emptyText.text = "搜索 CodeBuddy 账号"
        filterField.toolTipText = "按名称 / UID / 域名 / 手机号 / 别名筛选"
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        left.add(boardBtn)
        left.add(listBtn)
        left.add(Box.createHorizontalStrut(JBUI.scale(6)))
        left.add(JLabel(AllIcons.Actions.Search))
        left.add(filterField)
        left.add(Box.createHorizontalStrut(JBUI.scale(2)))
        left.add(toolbarLabel("排序"))
        left.add(buildSortCombo())

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(3), 0)).apply { isOpaque = false }
        right.add(IconTile(AllIcons.General.Add, "从 auth 目录导入账号") { doImport() })
        batchTile = IconTile(AllIcons.Actions.ListFiles, "批量同步全部账号历史") { doSyncAllAccounts() }
        right.add(batchTile)
        refreshTile = IconTile(AllIcons.Actions.Refresh, "刷新额度") { doRefreshQuota() }
        right.add(refreshTile)
        right.add(IconTile(AllIcons.ToolbarDecorator.Export, "导出账号库") { doExport() })
        right.add(IconTile(AllIcons.ToolbarDecorator.Import, "导入账号库") { doImportLibrary() })
        right.add(IconTile(AllIcons.General.GearPlain, "设置") { doOpenSettings() })
        right.add(buildMoreMenu())

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        return row
    }

    private fun buildSortCombo(): JComboBox<AccountSortMode> =
        JComboBox(AccountSortMode.entries.toTypedArray()).apply {
            preferredSize = Dimension(JBUI.scale(112), preferredSize.height)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(
                    list, (value as? AccountSortMode)?.label ?: "", index, isSelected, cellHasFocus
                )
            }
            addActionListener {
                (selectedItem as? AccountSortMode)?.let {
                    sortMode = it
                    refresh()
                }
            }
        }

    /** 全选行：复选框 + 账号总数 */
    private fun buildSelectionBar(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        row.border = JBUI.Borders.emptyTop(2)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        selectAllBox = JCheckBox("全选").apply {
            isOpaque = false
            isFocusPainted = false
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            addActionListener { gridView.selectAll(isSelected) }
        }
        totalChip = Chip("全部 0", AccountUiColors.accentSoft, AccountUiColors.accent, arc = 11)
        left.add(selectAllBox)
        left.add(totalChip)

        row.add(left, BorderLayout.WEST)
        row.add(toolbarLabel("勾选账号后可批量操作"), BorderLayout.EAST)
        selectionBar = row
        return row
    }

    /** 更多操作：低频功能收进菜单，避免工具栏拥挤 */
    private fun buildMoreMenu(): JComponent {
        val btn = JButton("更多 ▾").apply {
            isFocusPainted = false
            margin = JBUI.insets(3, JBUI.scale(8))
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        }
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit) {
            menu.add(JMenuItem(text).apply { addActionListener { action() } })
        }
        item("切换到此账号") { doSwitch() }
        item("智能调度") { doSmartSwitch() }
        item("重命名") { doEditAlias() }
        item("删除") { doDelete() }
        menu.addSeparator()
        item("历史对话") { doOpenHistory() }
        item("对话日报 / 周报") { doOpenReport() }
        item("历史共享到当前") { doSyncHistoryToCurrent() }
        menu.addSeparator()
        item("额度明细") { QuotaDetailDialog.show(project) }
        item("消耗明细") { UsageDetailDialog.show(project) }
        menu.addSeparator()
        item("回滚上一次切换") { doRollback() }
        item("强制登出") { doForceLogout() }

        btn.addActionListener { menu.show(btn, 0, btn.height) }
        return btn
    }

    private fun toolbarLabel(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textMuted
    }

    private fun applyFilter() {
        gridView.setFilter(filterField.text.trim())
    }
    private fun buildCenter(): JPanel {
        val stack = JPanel(CardLayout())
        stack.background = AccountUiColors.panelBg
        stack.add(dashboard, VIEW_DASHBOARD)
        stack.add(gridView, VIEW_LIST)
        stack.add(emptyPanel, VIEW_EMPTY)
        centerStack = stack
        return stack
    }

    private var centerStack: JPanel? = null

    /** 切换中心视图（看板 / 列表），并同步底部状态与按钮状态 */
    private fun setView(mode: String) {
        if (viewMode == mode) return
        viewMode = mode
        boardBtn.isSelected = mode == VIEW_DASHBOARD
        listBtn.isSelected = mode == VIEW_LIST
        applyViewState()
        refresh()
    }

    private fun buildEmptyPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.background = AccountUiColors.panelBg

        // 空态装在卡片里居中：视觉上是一个「待填充」的容器，而不是一段飘着的文字
        val box = object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true
        ) {}
        box.border = JBUI.Borders.empty(26, 32)
        box.preferredSize = Dimension(JBUI.scale(320), JBUI.scale(196))

        val icon = JBLabel(AllIcons.General.User)
        icon.alignmentX = Component.CENTER_ALIGNMENT
        val title = JBLabel("还没有任何账号").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.BOLD, JBUI.scale(15).toFloat())
            foreground = AccountUiColors.textPrimary
        }
        val hint = JBLabel("从 CodeBuddy auth 目录导入已有账号").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textMuted
        }
        val link = ActionLink("立即导入") { doImport() }.apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }

        box.add(icon)
        box.add(Box.createVerticalStrut(JBUI.scale(14)))
        box.add(title)
        box.add(Box.createVerticalStrut(JBUI.scale(6)))
        box.add(hint)
        box.add(Box.createVerticalStrut(JBUI.scale(16)))
        box.add(link)
        panel.add(box)
        return panel
    }

    /** 底部条：左侧分页范围与状态、右侧每页条数与翻页 */
    private fun buildFooterBar(): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        panel.background = AccountUiColors.surfaceBg
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 1, 0, 0, 0),
            JBUI.Borders.empty(5, 10)
        )

        val smallFont = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        rangeLabel.font = smallFont
        rangeLabel.foreground = AccountUiColors.textSecondary
        statusLabel.font = smallFont
        statusLabel.foreground = AccountUiColors.textMuted

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false }
        left.add(rangeLabel)
        left.add(statusLabel)
        panel.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        pageSizeCombo = JComboBox(arrayOf("10 条/页", "20 条/页", "50 条/页")).apply {
            selectedIndex = 1
            font = smallFont
            addActionListener {
                val size = when (selectedIndex) {
                    0 -> 10
                    2 -> 50
                    else -> 20
                }
                gridView.setPageSize(size)
                updatePager()
            }
        }
        prevPageBtn = JButton("上一页").apply {
            isFocusPainted = false
            margin = JBUI.insets(2, 8)
            font = smallFont
            addActionListener {
                gridView.setPage(gridView.pageIndex() - 1)
                updatePager()
            }
        }
        pageLabel = JLabel("第 1 / 1 页").apply {
            font = smallFont
            foreground = AccountUiColors.textSecondary
            border = JBUI.Borders.empty(0, 4)
        }
        nextPageBtn = JButton("下一页").apply {
            isFocusPainted = false
            margin = JBUI.insets(2, 8)
            font = smallFont
            addActionListener {
                gridView.setPage(gridView.pageIndex() + 1)
                updatePager()
            }
        }

        right.add(pageSizeCombo)
        right.add(prevPageBtn)
        right.add(pageLabel)
        right.add(nextPageBtn)
        pagerPanel = right
        panel.add(pagerPanel, BorderLayout.EAST)
        return panel
    }

    // ---------------------------------------------------------------
    // 状态
    // ---------------------------------------------------------------

    private fun refresh() {
        // 让状态栏下一次绘制读取最新凭据与配额
        CodeBuddyStatusBarWidget.invalidateCache()

        val saved = library.listSaved()
        val active = library.currentActive()
        val quotaMap = library.quotaCache.loadAll()

        val sorted = AccountSorter.sort(saved, sortMode, active?.accountKey) { key ->
            quotaMap[key]?.usableRemainPercent
        }
        gridView.setAccounts(sorted, active?.accountKey, quotaMap)
        dashboard.update()

        val target = when {
            saved.isEmpty() -> VIEW_EMPTY
            viewMode == VIEW_DASHBOARD -> VIEW_DASHBOARD
            else -> VIEW_LIST
        }
        centerStack?.let { (it.layout as CardLayout).show(it, target) }
        totalChip.text = "全部 ${saved.size}"
        statusLabel.text = buildSummary(saved, active, quotaMap)
        applyViewState()
        updatePager()
        syncSelectionState()
    }

    /** 首页看板不显示全选行与分页控件 */
    private fun applyViewState() {
        if (!::selectionBar.isInitialized || !::pagerPanel.isInitialized) return
        val cardsVisible = viewMode == VIEW_LIST
        selectionBar.isVisible = cardsVisible
        pagerPanel.isVisible = cardsVisible
    }

    /** 底部概览：账号数 / token 过期数 / 当前账号 / 额度最高账号 */
    private fun buildSummary(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotaMap: Map<String, dev.codebuddy.account.quota.QuotaSummary>,
    ): String {
        val expired = saved.count { it.isRefreshTokenExpired }
        val best = saved
            .filter { it.accountKey != active?.accountKey && !it.isRefreshTokenExpired }
            .mapNotNull { s ->
                quotaMap[s.accountKey]?.takeIf { it.error == null }?.usableRemainPercent?.let { s to it }
            }
            .maxByOrNull { it.second }

        return buildString {
            append("共 ${saved.size} 个账号")
            if (expired > 0) append("　|　Token 过期 $expired")
            append("　|　当前：${active?.displayName ?: "未检测到"}")
            if (best != null) append("　|　额度最高：${best.first.displayName} ${"%.0f".format(best.second)}%")
        }
    }

    /** 卡片勾选变化后同步全选复选框 */
    private fun syncSelectionState() {
        if (!::selectAllBox.isInitialized) return
        val total = gridView.totalCount()
        val checked = gridView.checkedCount()
        selectAllBox.isSelected = total > 0 && checked >= total
    }

    /** 分页范围与翻页按钮状态 */
    private fun updatePager() {
        if (!::pageLabel.isInitialized) return
        val total = gridView.totalCount()
        val pages = gridView.pageCount()
        val index = gridView.pageIndex()
        val size = gridView.pageSize()
        val from = if (total == 0) 0 else index * size + 1
        val to = minOf(index * size + size, total)
        rangeLabel.text = "显示 $from - $to 条，共 $total 条"
        pageLabel.text = "第 ${index + 1} / $pages 页"
        prevPageBtn.isEnabled = index > 0
        nextPageBtn.isEnabled = index < pages - 1
    }

    // ---------------------------------------------------------------
    // 操作
    // ---------------------------------------------------------------

    private fun doImport() {
        val count = library.importFromAuthDir()
        refresh()
        if (count > 0) notify("导入完成", "从 auth 目录导入/更新了 $count 个账号。")
        else notify("没有新账号", "auth 目录下未发现新的可导入凭据。", NotificationType.INFORMATION)
    }

    private fun doOpenHistory() {
        val labels = library.listSaved().associate { it.uid to it.displayName }
        HistoryDialog.show(project, labels)
    }

    /** 基于历史对话生成今日日报 / 本周周报（按项目分组，可交给 CodeBuddy 生成正文） */
    private fun doOpenReport() {
        val labels = library.listSaved().associate { it.uid to it.displayName }
        HistoryReportDialog.show(project, labels)
    }

    /** 一键签到：对所有账号执行每日签到（实验特性，接口非官方） */
    private fun doCheckin() {
        if (!AccountSettings.getInstance().checkinEnabled) {
            notify("今日签到", "签到已在设置中关闭（Settings | Tools | CodeBuddy 账号管理器）", NotificationType.WARNING)
            return
        }
        CheckinTracker.checkinAll { summary ->
            notify("今日签到", summary)
            dashboard.update()
        }
    }

    /** 把全部账号的历史会话合并到「当前激活账号」，无需切号 */
    private fun doSyncHistoryToCurrent() {
        val target = library.currentActive()
        if (target == null) {
            notify(
                "未检测到当前账号",
                "没有读到 IDEA 当前使用的 CodeBuddy 凭据，无法确定同步目标。",
                NotificationType.WARNING
            )
            return
        }

        val ok = Messages.showYesNoDialog(
            project,
            "将把账号库中所有账号的历史会话合并到当前账号「${target.displayName}」。\n\n" +
                "• 合并会话索引 index.json，并复制当前账号缺失的会话消息目录\n" +
                "• 只新增不覆盖，不会删除任何账号的数据\n" +
                "• 只处理当前账号已存在的工作区目录\n\n" +
                "建议同步后重启 IDE 再查看侧边栏。确定继续？",
            "历史共享到当前账号",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        batchTile.isEnabled = false
        statusLabel.text = "正在把历史会话同步到 ${target.displayName}…"

        val copyMessages = AccountSettings.getInstance().copyMessagesOnSync
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = library.syncHistoryIndexes(target.uid, copyMessages = copyMessages)
            ApplicationManager.getApplication().invokeLater {
                HistoryReader.clearCache()
                batchTile.isEnabled = true
                refresh()
                if (r.error != null) {
                    notify("历史同步失败", r.error, NotificationType.ERROR)
                } else {
                    notify(
                        "历史同步完成",
                        "目标账号：${target.displayName}\n" +
                            "新增会话 ${r.conversationsAdded} 个，复制消息目录 ${r.conversationsCopied} 个" +
                            "（扫描 ${r.workspacesScanned} 个工作区，更新 ${r.workspacesUpdated} 个）。\n" +
                            "若侧边栏未刷新，请重启 IDE。"
                    )
                }
            }
        }
    }

    private fun doSwitch() {
        val card = gridView.selectedCard() ?: return
        switchToWithMode(card.snapshot)
    }

    /** 卡片底部图标操作 */
    private fun handleGridAction(card: AccountGridCard, action: GridCardAction) {
        when (action) {
            GridCardAction.SWITCH -> switchToWithMode(card.snapshot)
            GridCardAction.RENAME -> doEditAlias(card.snapshot)
            GridCardAction.REFRESH -> refreshQuotas(listOf(card.snapshot))
            GridCardAction.EXPORT -> doExportSingle(card.snapshot)
            GridCardAction.DELETE -> doDelete(card.snapshot)
        }
    }

    /** 智能调度：先消耗快过期的额度，再看额度富余；支持热切换 */
    private fun doSmartSwitch() {
        val active = library.currentActive()
        val saved = library.listSaved()
        if (saved.isEmpty()) {
            notify("没有账号", "账号库为空，请先导入账号。", NotificationType.WARNING)
            return
        }

        val candidates = QuotaScheduler.rank(saved, library.quotaCache.loadAll(), exclude = active?.accountKey)
        if (candidates.isEmpty()) {
            notify(
                "没有可用候选",
                "其他账号要么 token 过期、额度未知或已耗尽。\n建议先点「刷新额度」再试。",
                NotificationType.WARNING
            )
            return
        }

        val best = candidates.first()
        val hot = AccountSettings.getInstance().hotSwitchEnabled && CodeBuddyBridge.isAvailable()
        val ok = Messages.showYesNoDialog(
            project,
            "调度顺序（先消耗快过期的额度，再看剩余富余）：\n\n" +
                QuotaScheduler.describe(candidates, 3) + "\n\n" +
                "推荐：${best.snapshot.displayName}（${best.snapshot.domainHost}）\n" +
                "切换方式：${if (hot) "热切换，不重启 IDE" else "写入凭据后需重启 IDE"}\n\n确定切换？",
            "智能调度",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        switchToWithMode(best.snapshot)
    }

    /** 按设置决定「热切换」或「常规切换（需重启 IDE）」 */
    private fun switchToWithMode(target: CredentialSnapshot) {
        val settings = AccountSettings.getInstance()
        if (!settings.hotSwitchEnabled) {
            syncAndSwitch(target)
            return
        }

        syncHistoryOnly(target)
        val r = library.hotSwitchTo(target.accountKey, project)
        refresh()

        if (r.success) {
            notify(
                "已热切换到 ${target.displayName}",
                r.message
                    ?: "CodeBuddy 已重新加载新账号，无需重启 IDE。\n若账号/额度仍显示旧值，稍等几秒或再执行一次切换。"
            )
        } else {
            notify(
                "热切换未完成",
                "${r.message ?: "未知原因"}\n\n凭据已写入，重启 IDE 后生效。",
                NotificationType.WARNING
            )
        }
    }

    /** 仅同步历史会话（热切路径使用，不再弹同步确认） */
    private fun syncHistoryOnly(target: CredentialSnapshot) {
        val settings = AccountSettings.getInstance()
        if (!settings.autoSyncHistoryOnSwitch) return
        val r = library.syncHistoryIndexes(target.uid, copyMessages = settings.copyMessagesOnSync)
        HistoryReader.clearCache()
        if (r.error != null) {
            notify("历史同步失败", r.error, NotificationType.WARNING)
        }
    }

    /** 切换账号（含可选的历史会话同步），供「切换」与「智能选号」复用 */
    private fun syncAndSwitch(target: CredentialSnapshot) {
        val settings = AccountSettings.getInstance()

        // 默认同步时不再追问；关闭默认同步后仍然每次询问
        val sync = settings.autoSyncHistoryOnSwitch || Messages.showYesNoDialog(
            project,
            "是否同时把其他账号的历史会话索引同步到 ${target.displayName}？\n（保证切号后侧边栏能看到全部历史对话）",
            "切换账号",
            "同步",
            "跳过",
            Messages.getQuestionIcon()
        ) == Messages.YES

        var syncMsg = ""
        if (sync) {
            val r = library.syncHistoryIndexes(target.uid, copyMessages = settings.copyMessagesOnSync)
            HistoryReader.clearCache()
            syncMsg = if (r.error != null) "\n（历史索引同步失败：${r.error}）"
            else "\n已同步历史会话：新增 ${r.conversationsAdded} 个会话，复制消息目录 ${r.conversationsCopied} 个。"
        }

        when (val result = library.switchTo(target.accountKey)) {
            is SwitchResult.Success -> {
                refresh()
                notify(
                    "切换成功",
                    "已切换到 ${target.displayName}。$syncMsg\n\n请重启 IDE 使 CodeBuddy 插件重新加载凭据。"
                )
            }

            is SwitchResult.Failure -> notify("切换失败", result.message, NotificationType.ERROR)
        }
    }

    /** 批量同步：把全量历史会话索引 + 缺失消息目录合并到每一个账号 */
    private fun doSyncAllAccounts() {
        val saved = library.listSaved()
        if (saved.isEmpty()) {
            notify("没有账号", "账号库为空，请先导入账号。", NotificationType.WARNING)
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            "将把所有账号（含 auth 目录中未导入的）的历史会话互相合并到每一个账号。\n\n" +
                "• 每个账号的 index.json 覆盖前会备份为 index.json.bak\n" +
                "• 只新增不覆盖、不删除任何数据\n" +
                "• 账号较多时耗时较长，过程中可继续使用 IDE\n\n确定继续？",
            "批量同步历史",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        val copyMessages = AccountSettings.getInstance().copyMessagesOnSync
        batchTile.isEnabled = false
        statusLabel.text = "正在批量同步 ${saved.size} 个账号的历史会话…"

        ApplicationManager.getApplication().executeOnPooledThread {
            var added = 0
            var copied = 0
            var failed = 0
            for (s in saved) {
                val r = library.syncHistoryIndexes(s.uid, copyMessages = copyMessages)
                if (r.error != null) failed++
                else {
                    added += r.conversationsAdded
                    copied += r.conversationsCopied
                }
            }
            ApplicationManager.getApplication().invokeLater {
                HistoryReader.clearCache()
                batchTile.isEnabled = true
                refresh()
                notify(
                    "批量同步完成",
                    "账号 ${saved.size} 个：新增会话 $added 个，复制消息目录 $copied 个，失败 $failed 个。\n" +
                        "若侧边栏未刷新，请重启 IDE。"
                )
            }
        }
    }

    private fun doRollback() {
        if (!library.hasRollback()) {
            notify("无可回滚", "还没有切换备份。", NotificationType.WARNING)
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            "将把激活凭据恢复为上一次切换前的状态，确定继续？",
            "回滚",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        when (val r = library.rollbackLastSwitch()) {
            is SwitchResult.Success -> {
                refresh()
                notify("回滚成功", "已恢复上一次切换前的凭据。请重启 IDE 生效。")
            }
            is SwitchResult.Failure -> notify("回滚失败", r.message, NotificationType.ERROR)
        }
    }

    private fun doForceLogout() {
        val ok = Messages.showYesNoDialog(
            project,
            "将清除 IDEA 中 CodeBuddy 的登录状态（回到未登录）。\n\n" +
                "• 账号库中的快照会保留，之后可随时切换回来\n" +
                "• 清除后需重启 IDE\n\n确定继续？",
            "强制退出登录",
            Messages.getWarningIcon()
        ) == Messages.YES
        if (!ok) return

        when (val r = library.forceLogout()) {
            is SwitchResult.Success -> {
                refresh()
                notify(
                    "已退出登录",
                    "已清除 CodeBuddy 登录凭据。请重启 IDE，CodeBuddy 会回到未登录状态，之后可重新登录。"
                )
            }
            is SwitchResult.Failure -> notify("操作失败", r.message, NotificationType.WARNING)
        }
    }

    private fun doRefreshQuota() {
        val card = gridView.selectedCard()
        val targets = when {
            card != null -> listOf(card.snapshot)
            AccountSettings.getInstance().refreshAllWhenNoSelection -> library.listSaved()
            else -> emptyList()
        }
        if (targets.isEmpty()) {
            statusLabel.text = "请先选择一个账号（或在设置中开启「未选中时刷新全部」）"
            return
        }
        refreshQuotas(targets)
    }

    /** 刷新指定账号的额度（看板与工具栏复用） */
    private fun refreshQuotas(targets: List<CredentialSnapshot>) {
        if (targets.isEmpty()) {
            statusLabel.text = "账号库为空，请先导入账号"
            return
        }
        statusLabel.text = "正在查询 ${targets.size} 个账号的额度…"
        refreshTile.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = 0
            var fail = 0
            for (s in targets) {
                val summary = library.refreshQuota(s.accountKey)
                if (summary == null || summary.error != null) fail++ else ok++
            }
            ApplicationManager.getApplication().invokeLater {
                refresh()
                refreshTile.isEnabled = true
                AccountEvents.fireQuotaChanged()
                notify("额度刷新完成", "成功 $ok 个，失败 $fail 个。")
            }
        }
    }

    /** @param target 为 null 时作用于当前选中卡片 */
    private fun doEditAlias(target: CredentialSnapshot? = null) {
        val snapshot = target ?: gridView.selectedCard()?.snapshot ?: return
        val input = Messages.showInputDialog(
            project,
            "为该账号设置别名（留空则显示原昵称）：",
            "重命名账号",
            Messages.getQuestionIcon(),
            snapshot.alias,
            null
        ) ?: return
        if (library.setAlias(snapshot.accountKey, input)) {
            refresh()
        } else {
            notify("重命名失败", "请查看 IDE 日志。", NotificationType.ERROR)
        }
    }

    /** @param target 为 null 时作用于当前选中卡片 */
    private fun doDelete(target: CredentialSnapshot? = null) {
        val snapshot = target ?: gridView.selectedCard()?.snapshot ?: return
        if (snapshot.accountKey == library.currentActive()?.accountKey) {
            notify("无法删除", "不能删除当前激活账号。", NotificationType.WARNING)
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            "确定从账号库移除 ${snapshot.displayName}？\n（不会影响 IDE 当前凭据）",
            "删除账号",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        if (library.delete(snapshot.accountKey)) {
            library.quotaCache.remove(snapshot.accountKey)
            refresh()
            notify("已删除", "${snapshot.displayName} 已从账号库移除。")
        } else {
            notify("删除失败", "请查看 IDE 日志。", NotificationType.ERROR)
        }
    }

    /** 导出单个账号的凭据快照 */
    private fun doExportSingle(snapshot: CredentialSnapshot) {
        val descriptor = FileSaverDescriptor("导出账号凭据", "选择保存位置", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as java.nio.file.Path?, "codebuddy-${snapshot.uid.take(8)}.json")
            ?: return
        val path = wrapper.file.toPath()
        try {
            Files.writeString(path, library.exportJson(setOf(snapshot.accountKey)))
            notify(
                "导出成功",
                "已导出 ${snapshot.displayName} 到：${path.toAbsolutePath()}\n\n注意：文件包含完整凭据，请妥善保管。"
            )
        } catch (e: Exception) {
            notify("导出失败", e.message ?: "未知错误", NotificationType.ERROR)
        }
    }

    private fun doExport() {
        val descriptor = FileSaverDescriptor("导出账号库", "选择保存位置", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as java.nio.file.Path?, "codebuddy-accounts.json") ?: return
        val path = wrapper.file.toPath()
        try {
            Files.writeString(path, library.exportJson())
            notify("导出成功", "已导出到：${path.toAbsolutePath()}\n\n注意：文件包含完整凭据，请妥善保管。")
        } catch (e: Exception) {
            notify("导出失败", e.message ?: "未知错误", NotificationType.ERROR)
        }
    }

    private fun doImportLibrary() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val file = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project)
            .firstOrNull() ?: return
        val count = try {
            library.importJson(Files.readString(Paths.get(file.path)))
        } catch (e: Exception) {
            -1
        }
        if (count >= 0) {
            refresh()
            notify("导入完成", "从文件导入了 $count 个账号。")
        } else {
            notify("导入失败", "文件格式不正确。", NotificationType.ERROR)
        }
    }

    private fun doOpenSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AccountSettingsConfigurable::class.java)
        // 设置可能改了状态栏显隐、刷新间隔
        refresh()
    }

    private fun notify(title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeBuddy Account Manager")
            .createNotification(title, content, type)
            .notify(project)
    }

    private companion object {
        /** CardLayout 的三个视图名 */
        const val VIEW_DASHBOARD = "dashboard"
        const val VIEW_LIST = "list"
        const val VIEW_EMPTY = "empty"
    }
}
