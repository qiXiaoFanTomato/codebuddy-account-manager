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
    private val cardList = AccountCardList()
    private val statusLabel = JBLabel("就绪")
    private val filterField = JBTextField(14)
    private var sortMode: AccountSortMode = AccountSortMode.BY_DOMAIN
    private val emptyPanel = buildEmptyPanel()

    /** 当前中心视图：看板 / 账号列表 */
    private var viewMode: String = VIEW_DASHBOARD

    private val dashboard = DashboardPanel(
        onOpenList = { setView(VIEW_LIST) },
        onRefreshAll = { refreshQuotas(library.listSaved()) },
        onOpenQuotaDetail = { QuotaDetailDialog.show(project) },
        onOpenUsageDetail = { UsageDetailDialog.show(project) },
        onOpenHistory = { doOpenHistory() },
    )

    private lateinit var boardBtn: JToggleButton
    private lateinit var listBtn: JToggleButton
    private lateinit var switchBtn: JButton
    private lateinit var syncHistoryBtn: JButton
    private lateinit var quotaBtn: JButton
    private lateinit var deleteBtn: JButton
    private lateinit var aliasBtn: JButton

    init {
        background = AccountUiColors.panelBg

        add(buildToolbar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)

        cardList.onSelectionChanged = { updateButtonState() }

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

    private fun buildToolbar(): JPanel {
        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.surfaceBg

        // 第一行：操作按钮（按「导入 / 切换 / 额度 / 编辑」分组，用竖线分隔）
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 6))
        actions.isOpaque = false
        actions.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(2, 4)
        )

        val importBtn = iconButton("从 auth 目录导入", AllIcons.Actions.Download) { doImport() }
        val historyBtn = iconButton("历史对话", AllIcons.Actions.ListFiles) { doOpenHistory() }
        syncHistoryBtn = iconButton("历史共享到当前", AllIcons.Actions.Refresh) { doSyncHistoryToCurrent() }
        val syncAllBtn = iconButton("批量同步全部", null) { doSyncAllAccounts() }
        switchBtn = iconButton("切换", AllIcons.Actions.Refresh) { doSwitch() }
        val smartBtn = iconButton("智能调度", AllIcons.General.User) { doSmartSwitch() }
        quotaBtn = iconButton("刷新额度", AllIcons.Actions.Refresh) { doRefreshQuota() }
        val detailBtn = iconButton("额度明细", null) { QuotaDetailDialog.show(project) }
        val usageBtn = iconButton("消耗明细", null) { UsageDetailDialog.show(project) }
        aliasBtn = iconButton("重命名", AllIcons.Actions.Edit) { doEditAlias() }
        deleteBtn = iconButton("删除", AllIcons.Actions.GC) { doDelete() }
        val rollbackBtn = iconButton("回滚", AllIcons.Actions.Rollback) { doRollback() }
        val logoutBtn = iconButton("强制登出", AllIcons.Actions.Cancel) { doForceLogout() }

        actions.add(importBtn)
        actions.add(historyBtn)
        actions.add(syncHistoryBtn)
        actions.add(syncAllBtn)
        actions.add(toolbarSeparator())
        actions.add(switchBtn)
        actions.add(smartBtn)
        actions.add(quotaBtn)
        actions.add(detailBtn)
        actions.add(usageBtn)
        actions.add(toolbarSeparator())
        actions.add(aliasBtn)
        actions.add(deleteBtn)
        actions.add(rollbackBtn)
        actions.add(logoutBtn)

        // 第二行：筛选 / 排序 / 库操作
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        filters.isOpaque = false
        filters.border = JBUI.Borders.empty(0, 4, 2, 4)
        filterField.toolTipText = "按名称 / UID / 域名 / 手机号筛选账号"
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

        val sortCombo = JComboBox(AccountSortMode.entries.toTypedArray()).apply {
            selectedItem = sortMode
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

        val exportBtn = iconButton("导出", AllIcons.ToolbarDecorator.Export) { doExport() }
        val importLibBtn = iconButton("导入库", AllIcons.ToolbarDecorator.Import) { doImportLibrary() }
        val settingsBtn = iconButton("设置", AllIcons.General.GearPlain) { doOpenSettings() }

        // 视图切换：看板 / 账号列表
        boardBtn = JToggleButton("看板", viewMode == VIEW_DASHBOARD).apply {
            isFocusPainted = false
            margin = JBUI.insets(3, 10)
            addActionListener { setView(VIEW_DASHBOARD) }
        }
        listBtn = JToggleButton("账号列表", viewMode == VIEW_LIST).apply {
            isFocusPainted = false
            margin = JBUI.insets(3, 10)
            addActionListener { setView(VIEW_LIST) }
        }
        ButtonGroup().apply {
            add(boardBtn)
            add(listBtn)
        }
        filters.add(boardBtn)
        filters.add(listBtn)
        filters.add(Box.createHorizontalStrut(JBUI.scale(10)))

        filters.add(JBLabel("筛选:"))
        filters.add(filterField)
        filters.add(Box.createHorizontalStrut(JBUI.scale(6)))
        filters.add(JBLabel("排序:"))
        filters.add(sortCombo)
        filters.add(Box.createHorizontalStrut(JBUI.scale(6)))
        filters.add(exportBtn)
        filters.add(importLibBtn)
        filters.add(settingsBtn)

        root.add(actions, BorderLayout.NORTH)
        root.add(filters, BorderLayout.CENTER)
        return root
    }

    private fun applyFilter() {
        cardList.setFilter(filterField.text.trim())
    }

    private fun iconButton(text: String, icon: Icon?, action: () -> Unit): JButton {
        val btn = JButton(text, icon).apply {
            isFocusPainted = false
            margin = JBUI.insets(4, 9)
            addActionListener { action() }
        }
        return btn
    }

    /** 工具栏分组竖线：把 12 个按钮切成可扫视的几段 */
    private fun toolbarSeparator(): JComponent = JLabel("│").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
        foreground = AccountUiColors.border
        border = JBUI.Borders.empty(0, 2)
    }

    private fun buildCenter(): JPanel {
        val stack = JPanel(CardLayout())
        stack.background = AccountUiColors.panelBg
        stack.add(dashboard, VIEW_DASHBOARD)
        stack.add(cardList, VIEW_LIST)
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

    private fun buildStatusBar(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = AccountUiColors.surfaceBg
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 1, 0, 0, 0),
            JBUI.Borders.empty(5, 12)
        )
        statusLabel.foreground = AccountUiColors.textMuted
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())

        // 左侧状态圆点：让底部栏有明确的「状态区」锚点
        val left = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JLabel("●").apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(8).toFloat())
                foreground = AccountUiColors.accent
            }, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }
        panel.add(left, BorderLayout.WEST)
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
        cardList.setAccounts(sorted, active?.accountKey, quotaMap, grouped = sortMode == AccountSortMode.BY_DOMAIN)
        dashboard.update()

        val target = when {
            saved.isEmpty() -> VIEW_EMPTY
            viewMode == VIEW_DASHBOARD -> VIEW_DASHBOARD
            else -> VIEW_LIST
        }
        centerStack?.let { (it.layout as CardLayout).show(it, target) }
        statusLabel.text = buildSummary(saved, active, quotaMap)
        updateButtonState()
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

    private fun updateButtonState() {
        val hasSelection = cardList.selectedCard() != null
        switchBtn.isEnabled = hasSelection
        quotaBtn.isEnabled = hasSelection
        aliasBtn.isEnabled = hasSelection
        deleteBtn.isEnabled = hasSelection
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

        syncHistoryBtn.isEnabled = false
        statusLabel.text = "正在把历史会话同步到 ${target.displayName}…"

        val copyMessages = AccountSettings.getInstance().copyMessagesOnSync
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = library.syncHistoryIndexes(target.uid, copyMessages = copyMessages)
            ApplicationManager.getApplication().invokeLater {
                HistoryReader.clearCache()
                syncHistoryBtn.isEnabled = true
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
        val card = cardList.selectedCard() ?: return
        switchToWithMode(card.snapshot)
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
        syncHistoryBtn.isEnabled = false
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
                syncHistoryBtn.isEnabled = true
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
        val card = cardList.selectedCard()
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
        quotaBtn.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = 0
            var fail = 0
            for (s in targets) {
                val summary = library.refreshQuota(s.accountKey)
                if (summary == null || summary.error != null) fail++ else ok++
            }
            ApplicationManager.getApplication().invokeLater {
                refresh()
                quotaBtn.isEnabled = true
                AccountEvents.fireQuotaChanged()
                notify("额度刷新完成", "成功 $ok 个，失败 $fail 个。")
            }
        }
    }

    private fun doEditAlias() {
        val card = cardList.selectedCard() ?: return
        val current = card.snapshot.alias
        val input = Messages.showInputDialog(
            project,
            "为该账号设置别名（留空则显示原昵称）：",
            "重命名账号",
            Messages.getQuestionIcon(),
            current,
            null
        ) ?: return
        if (library.setAlias(card.snapshot.accountKey, input)) {
            refresh()
        } else {
            notify("重命名失败", "请查看 IDE 日志。", NotificationType.ERROR)
        }
    }

    private fun doDelete() {
        val card = cardList.selectedCard() ?: return
        if (card.isActive) {
            notify("无法删除", "不能删除当前激活账号。", NotificationType.WARNING)
            return
        }
        val ok = Messages.showYesNoDialog(
            project,
            "确定从账号库移除 ${card.snapshot.displayName}？\n（不会影响 IDE 当前凭据）",
            "删除账号",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        if (library.delete(card.snapshot.accountKey)) {
            library.quotaCache.remove(card.snapshot.accountKey)
            refresh()
            notify("已删除", "${card.snapshot.displayName} 已从账号库移除。")
        } else {
            notify("删除失败", "请查看 IDE 日志。", NotificationType.ERROR)
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
