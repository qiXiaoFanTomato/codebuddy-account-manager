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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.growth.GrowthTracker
import dev.codebuddy.account.history.HistoryReader
import dev.codebuddy.account.hotswap.CodeBuddyBridge
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.quota.QuotaSummary
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
 * 账号管理主面板（档案柜版，1:1 复刻 design/plugin-ui.html）。
 *
 * 外壳自上而下三带：
 *  1. 深墨标题栏：品牌 mark + 视图 tabs（账号 / 看板 / 额度明细）+ 更多；
 *  2. 抽屉面工具栏：第一行文字动作，第二行筛选 / 排序 / 导入导出 / 设置；
 *  3. 档案纸内容区：对应三个视图，每屏顶部一条 `.view-head`；
 *  底部是同一条深墨状态栏（朱红圆点 + 读数 + 维度下拉）。
 */
class AccountPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val library = AccountLibrary.getInstance()
    private val gridView = AccountGridView()

    /** 底栏右侧：进度 / 结果提示（切换、刷新、同步的即时反馈） */
    private val statusLabel = JBLabel("悬停看明细 · 点击读数切换维度")

    /** 账号视图 meta：账号数 / 认证域数 / 分页范围 */
    private val rangeLabel = JBLabel("").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textSecondary
    }

    /** 看板视图 meta */
    private val dashboardMeta = JBLabel("").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textSecondary
    }

    /** 底栏读数：CodeBuddy: <账号> · <额度> */
    private lateinit var readoutLabel: JLabel

    private val filterField = JBTextField(14)
    private var sortMode: AccountSortMode = AccountSortMode.BY_DOMAIN
    private val emptyPanel = buildEmptyPanel()

    /** 内嵌的额度明细视图（对应 `#view-quota`） */
    private val quotaView = QuotaView()

    /** 当前中心视图：看板 / 账号卡片 / 额度明细（看板在第一位，默认落在看板） */
    private var viewMode: String = VIEW_DASHBOARD

    private val dashboard = DashboardPanel(
        onOpenList = { setView(VIEW_LIST) },
        onRefreshAll = { refreshQuotas(library.listSaved()) },
        onOpenQuotaDetail = { QuotaDetailDialog.show(project) },
        onOpenUsageDetail = { UsageDetailDialog.show(project) },
        onOpenHistory = { doOpenHistory() },
        onOpenReport = { doOpenReport() },
        onCheckin = { doCheckin() },
        onGrowth = { doGrowth() },
        onGrowthTravel = { doGrowthTravel() },
        onGrowthActivity = { doGrowthActivity() },
        onGrowthKeepalive = { doGrowthKeepalive() },
        onOpenGrowthCenter = { openGrowthCenter() },
        onOpenSettings = { doOpenSettings() },
    )

    private lateinit var viewTabs: SegmentedTabs
    private lateinit var refreshBtn: JellyButton
    private lateinit var selectAllBox: JCheckBox
    private lateinit var selectionHint: JLabel
    private lateinit var pagerPanel: JComponent
    private lateinit var pageLabel: JLabel
    private lateinit var prevPageBtn: JComponent
    private lateinit var nextPageBtn: JComponent
    private lateinit var pageSizeCombo: JComboBox<String>

    /** 「批量同步全部账号历史」菜单项：执行中禁用 */
    private var batchItem: JMenuItem? = null

    /** 账号视图底部的切换日志（对应 `.log-bar`） */
    private val logLines = ArrayDeque<String>()
    private lateinit var logPanel: JPanel

    init {
        background = AccountUiColors.panelBg
        // 以保存的选择为准（面板可能早于启动活动创建）
        AccountUiColors.loadPersistedTheme()

        add(buildTopBar(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildFooterBar(), BorderLayout.SOUTH)

        // 额度明细视图自己刷新后，把状态栏读数与账号卡片一起带起来
        quotaView.onChanged = { refresh() }

        gridView.onSelectionChanged = { syncSelectionState() }
        gridView.onPageChanged = { updatePager() }
        gridView.onAction = { card, action -> handleGridAction(card, action) }

        // 多项目窗口同步：任一窗口改动账号库 / 配额后，其他窗口的面板跟着刷新
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(AccountEvents.TOPIC, object : AccountEvents {
                override fun accountsChanged() = scheduleRefresh()
                override fun quotaChanged() = scheduleRefresh()

                // 本窗口发起的布局变更自带过渡动画，重建会打断它
                override fun dashboardLayoutChanged(origin: Any) {
                    if (origin !== dashboard) scheduleRefresh()
                }
            })

        // 色调切换：卡片底色在构造时固定，需要重建一次才能换色
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(UiThemeEvents.TOPIC, object : UiThemeListener {
                override fun themeChanged(theme: AccentTheme) = scheduleRefresh()
            })

        appendLog("写入凭据前会生成 secret-storage.xml.bak-<时间戳>")
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
        root.background = AccountUiColors.ink
        // 两条带：上面深墨标题栏、下面抽屉面工具栏（对应 plugin-ui.html 的 .titlebar / .toolbar）
        root.add(buildTitleBar())
        root.add(buildToolDrawer())
        return root
    }

    /** 深墨标题栏：品牌 mark + 视图 tabs（账号 / 看板 / 额度明细）+ 更多 */
    private fun buildTitleBar(): JComponent {
        val bar = JPanel(BorderLayout(JBUI.scale(18), 0))
        bar.background = AccountUiColors.ink
        bar.border = JBUI.Borders.empty(0, AccountUiSpacing.cardPadH)
        val height = JBUI.scale(46)
        bar.preferredSize = Dimension(0, height)
        bar.minimumSize = bar.preferredSize
        bar.maximumSize = Dimension(Int.MAX_VALUE, height)

        // 品牌：18px 方框 + 内嵌朱印点（对应 `.brand-mark`）
        val brand = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(9), 0)).apply { isOpaque = false }
        brand.add(BrandMark())
        brand.add(JLabel("CodeBuddy Accounts").apply {
            font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.tab)
            foreground = AccountUiColors.onInk
        })

        viewTabs = SegmentedTabs(
            listOf("看板", "账号", "额度明细"),
            viewTabIndex(viewMode),
            { index -> setView(viewModeOf(index)) },
        ).apply { onDark = true }
        val tabsHolder = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)).apply {
            isOpaque = false
            add(viewTabs)
        }

        // 标题栏工具区：与 HTML `.titlebar-tools` 的 ⋯ 对应，低频动作都收在这里
        val tools = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        tools.add(buildMoreMenu())

        bar.add(brand, BorderLayout.WEST)
        bar.add(tabsHolder, BorderLayout.CENTER)
        bar.add(tools, BorderLayout.EAST)
        return bar
    }

    /** 视图 → tab 下标（看板在第一位） */
    private fun viewTabIndex(mode: String): Int = when (mode) {
        VIEW_DASHBOARD -> 0
        VIEW_LIST -> 1
        else -> 2
    }

    /** tab 下标 → 视图 */
    private fun viewModeOf(index: Int): String = when (index) {
        0 -> VIEW_DASHBOARD
        1 -> VIEW_LIST
        else -> VIEW_QUOTA
    }

    /**
     * 抽屉面工具栏：两行。
     *
     * 第一行是动作（导入 / 历史 / 切换 / 调度 / 刷新 / 额度明细 / 重命名 / 删除 / 回滚），
     * 第二行是筛选、排序、导出导入与设置，右侧是勾选状态与「已选中」，与
     * design/plugin-ui.html 的 `.toolbar` 一一对应。
     */
    private fun buildToolDrawer(): JComponent {
        val drawer = JPanel()
        drawer.layout = BoxLayout(drawer, BoxLayout.Y_AXIS)
        drawer.background = AccountUiColors.surfaceBg
        drawer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(AccountUiSpacing.sm, AccountUiSpacing.cardPadH)
        )
        drawer.add(buildActionRow())
        drawer.add(buildFilterRow())
        return drawer
    }

    /** 工具栏第一行：文字动作（窄窗时自动折行） */
    private fun buildActionRow(): JComponent {
        val row = FullWidthRow(FlowWrapLayout(JBUI.scale(6), JBUI.scale(6)))
        row.border = JBUI.Borders.emptyBottom(AccountUiSpacing.sm)

        row.add(toolButton("从 auth 目录导入", JellyButtonVariant.PRIMARY) { doImport() })
        row.add(toolButton("历史对话") { doOpenHistory() })
        row.add(toolButton("切换") { doSwitch() })
        row.add(toolButton("智能调度") { doSmartSwitch() })
        refreshBtn = toolButton("刷新额度") { doRefreshQuota() }
        row.add(refreshBtn)
        row.add(toolButton("额度明细") { setView(VIEW_QUOTA) })
        row.add(toolButton("重命名") { doEditAlias() })
        row.add(toolButton("删除", danger = true) { doDelete() })
        row.add(toolButton("回滚") { doRollback() })
        return row
    }

    /** 工具栏第二行：筛选 / 排序 / 导出导入 / 设置 + 勾选状态 */
    private fun buildFilterRow(): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        row.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.divider, 1, 0, 0, 0),
            JBUI.Borders.emptyTop(AccountUiSpacing.sm),
        )

        val left = JPanel(FlowWrapLayout(JBUI.scale(6), JBUI.scale(6))).apply { isOpaque = false }
        left.add(toolbarLabel("筛选:"))
        left.add(buildFilterField())
        left.add(toolbarLabel("排序:"))
        left.add(buildSortCombo())
        left.add(toolButton("导出") { doExport() })
        left.add(toolButton("导入库") { doImportLibrary() })
        left.add(toolButton("设置") { doOpenSettings() })

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        selectAllBox = JCheckBox("全选").apply {
            isOpaque = false
            isFocusPainted = false
            font = font.deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textMuted
            addActionListener { gridView.selectAll(isSelected) }
        }
        selectionHint = JLabel("已选中：—").apply {
            font = AccountUiType.ui(AccountUiType.label)
            foreground = AccountUiColors.textSecondary
        }
        right.add(selectAllBox)
        right.add(selectionHint)

        // 左侧筛选区放 CENTER：FlowWrapLayout 需要拿到实际可用宽度才会折行
        row.add(left, BorderLayout.CENTER)
        row.add(right, BorderLayout.EAST)
        return row
    }

    /** 统一文字动作按钮：白底细框，primary 为墨蓝实心，danger 悬停转朱印 */
    private fun toolButton(
        text: String,
        variant: JellyButtonVariant = JellyButtonVariant.SECONDARY,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): JellyButton = JellyButton(text, variant, null, text, onClick).apply {
        this.danger = danger
    }

    /** 筛选框：等宽小字 + 1px 铅线，对应 `.tw-input` */
    private fun buildFilterField(): JComponent {
        filterField.emptyText.text = "名称 / UID / 域名 / 手机号"
        filterField.toolTipText = "按名称 / UID / 域名 / 手机号 / 别名筛选"
        // 占位提示是中文，走界面字体（等宽字体没有中文字形）
        filterField.font = AccountUiType.ui(AccountUiType.label)
        filterField.isOpaque = true
        filterField.background = AccountUiColors.cardBg
        filterField.foreground = AccountUiColors.textPrimary
        filterField.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border),
            JBUI.Borders.empty(4, 8),
        )
        // 与 JellyButton 等高（28dp），工具栏一行内顶边与底边都对齐
        filterField.preferredSize = Dimension(JBUI.scale(240), JBUI.scale(28))
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })
        return filterField
    }

    /** 排序下拉：对应 `.tw-select` */
    private fun buildSortCombo(): JComboBox<AccountSortMode> {
        val combo = JComboBox(AccountSortMode.entries.toTypedArray())
        AccountUiKit.styleCombo(combo) { (it as? AccountSortMode)?.label ?: "" }
        combo.preferredSize = Dimension(JBUI.scale(124), JBUI.scale(28))
        combo.addActionListener {
            (combo.selectedItem as? AccountSortMode)?.let {
                sortMode = it
                refresh()
            }
        }
        return combo
    }

    /** 更多操作：低频功能收进菜单，避免工具栏拥挤 */
    private fun buildMoreMenu(): JComponent {
        lateinit var btn: JComponent
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit): JMenuItem =
            JMenuItem(text).apply { addActionListener { action() } }.also { menu.add(it) }

        item("切换到此账号") { doSwitch() }
        item("智能调度") { doSmartSwitch() }
        item("重命名") { doEditAlias() }
        item("删除") { doDelete() }
        menu.addSeparator()
        item("历史对话") { doOpenHistory() }
        item("对话日报 / 周报") { doOpenReport() }
        item("历史共享到当前") { doSyncHistoryToCurrent() }
        batchItem = item("批量同步全部账号历史") { doSyncAllAccounts() }
        item("扫描目录…") { DirectoryScanDialog.show(project) }
        menu.addSeparator()
        item("额度明细窗口") { QuotaDetailDialog.show(project) }
        item("消耗明细") { UsageDetailDialog.show(project) }
        item("成长中心窗口") { openGrowthCenter() }
        menu.addSeparator()
        item("界面色调…") { showThemeMenu(btn) }
        item("回滚上一次切换") { doRollback() }
        item("强制登出") { doForceLogout() }

        // 标题栏右侧的 ⋯：与 HTML `.titlebar-tools` 同一位置
        btn = JellyButton("", JellyButtonVariant.SECONDARY, AllIcons.Actions.More, "更多操作") {
            menu.show(btn, 0, btn.height)
        }.apply { onDark = true }
        return btn
    }

    /**
     * 色调选择菜单：单选列表 + 每个色调自己的色卡图标。
     *
     * 切换后重建看板与账号卡片，让卡片、徽标、描边都取到新的主色
     * （卡片底色在构造时就固定了，只 repaint 不会换色）。
     */
    private fun showThemeMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        val group = ButtonGroup()
        for (theme in AccentTheme.entries) {
            val swatch = ThemeSwatchIcon(
                JBColor(theme.palette.accentLight, theme.palette.accentDark),
                JBColor(theme.palette.sheenLight, theme.palette.sheenDark),
            )
            val item = JRadioButtonMenuItem(theme.displayName, swatch).apply {
                isSelected = theme == AccountUiColors.theme
                addActionListener { applyAccentTheme(theme) }
            }
            group.add(item)
            menu.add(item)
        }
        menu.show(anchor, 0, anchor.height)
    }

    /** 应用色调并刷新界面（同时记住选择） */
    private fun applyAccentTheme(theme: AccentTheme) {
        if (AccountUiColors.theme == theme) return
        AccountUiColors.applyTheme(theme)
        AccountUiColors.persistTheme(theme)
        // 广播后由本面板与其它窗口统一重建
        UiThemeEvents.fire(theme)
    }

    private fun toolbarLabel(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = AccountUiColors.textMuted
    }

    private fun applyFilter() {
        gridView.setFilter(filterField.text.trim())
    }
    private fun buildCenter(): JPanel {
        val stack = JPanel(CardLayout())
        stack.background = AccountUiColors.panelBg
        stack.add(buildAccountsView(), VIEW_LIST)
        stack.add(buildDashboardView(), VIEW_DASHBOARD)
        stack.add(buildQuotaViewPanel(), VIEW_QUOTA)
        stack.add(emptyPanel, VIEW_EMPTY)
        centerStack = stack
        return stack
    }

    /** 账号视图：`.view-head` + 卡片网格 + 底部切换日志 */
    private fun buildAccountsView(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = AccountUiColors.panelBg
        panel.add(viewHead("账号库", rangeLabel, pagerControls()), BorderLayout.NORTH)
        panel.add(gridView, BorderLayout.CENTER)

        logPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, AccountUiSpacing.cardPadH, AccountUiSpacing.sm, AccountUiSpacing.cardPadH)
        }
        panel.add(logPanel, BorderLayout.SOUTH)
        return panel
    }

    /** 看板视图：`.view-head` + 卡片网格 */
    private fun buildDashboardView(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = AccountUiColors.panelBg
        panel.add(viewHead("看板", dashboardMeta, dashboard.headerControls()), BorderLayout.NORTH)
        panel.add(dashboard, BorderLayout.CENTER)
        return panel
    }

    /** 额度明细视图：`.view-head` + 账号额度卡列表 */
    private fun buildQuotaViewPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = AccountUiColors.panelBg
        panel.add(viewHead("额度明细", quotaView.metaLabel, quotaView.headerControls()), BorderLayout.NORTH)
        panel.add(quotaView, BorderLayout.CENTER)
        return panel
    }

    /** 视图标题行：对应 `.view-head`（标题 + 右侧读数 + 操作） */
    private fun viewHead(title: String, meta: JLabel, actions: JComponent? = null): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(16), 0))
        row.isOpaque = false
        row.border = JBUI.Borders.empty(
            AccountUiSpacing.lg,
            AccountUiSpacing.cardPadH,
            AccountUiSpacing.sm,
            AccountUiSpacing.cardPadH,
        )

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply { isOpaque = false }
        left.add(
            JLabel(title).apply {
                font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.scaled(20))
                foreground = AccountUiColors.textPrimary
            }
        )
        left.add(meta)
        row.add(left, BorderLayout.WEST)
        if (actions != null) {
            actions.alignmentY = Component.CENTER_ALIGNMENT
            row.add(actions, BorderLayout.EAST)
        }
        return row
    }

    /** 账号视图标题右侧的分页控件（每页条数 + 上一页 / 页码 / 下一页） */
    private fun pagerControls(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        val small = AccountUiType.ui(AccountUiType.caption)

        pageSizeCombo = JComboBox(arrayOf("10 条/页", "20 条/页", "50 条/页")).apply {
            selectedIndex = 1
            AccountUiKit.styleCombo(this)
            preferredSize = Dimension(JBUI.scale(96), JBUI.scale(28))
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
        prevPageBtn = JellyButton("上一页", JellyButtonVariant.SECONDARY, null, "回到上一页") {
            gridView.setPage(gridView.pageIndex() - 1)
            updatePager()
        }
        pageLabel = JLabel("第 1 / 1 页").apply {
            font = small
            foreground = AccountUiColors.textMuted
            border = JBUI.Borders.empty(0, 4)
        }
        nextPageBtn = JellyButton("下一页", JellyButtonVariant.SECONDARY, null, "翻到下一页") {
            gridView.setPage(gridView.pageIndex() + 1)
            updatePager()
        }

        row.add(pageSizeCombo)
        row.add(prevPageBtn)
        row.add(pageLabel)
        row.add(nextPageBtn)
        pagerPanel = row
        return row
    }

    private var centerStack: JPanel? = null

    /** 切换中心视图（账号 / 看板 / 额度明细），并同步 tab 与底栏读数 */
    private fun setView(mode: String) {
        if (viewMode == mode) return
        viewMode = mode
        if (::viewTabs.isInitialized) {
            viewTabs.setSelectedIndex(viewTabIndex(mode))
        }
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

        val badge = GlyphBadge("CB", AccountUiColors.accentSoft, AccountUiColors.accent, size = 40).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val title = JBLabel("还没有任何账号").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.BOLD, AccountUiType.number)
            foreground = AccountUiColors.textPrimary
        }
        val hint = JBLabel("从 CodeBuddy auth 目录导入已有账号").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textMuted
        }
        val action = JellyButton("立即导入", JellyButtonVariant.PRIMARY, null, "从 auth 目录导入") { doImport() }
        val actionRow = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            add(action)
        }

        box.add(badge)
        box.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.lg)))
        box.add(title)
        box.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))
        box.add(hint)
        box.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.lg)))
        box.add(actionRow)
        panel.add(box)
        return panel
    }

    /**
     * 底部状态栏：朱红圆点 + 读数 + 下拉箭头，右侧是进度提示。
     *
     * 对应 `.statusbar`：读数本身是按钮，点开就是「状态栏额度维度」菜单。
     */
    private fun buildFooterBar(): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        panel.background = AccountUiColors.ink
        panel.border = JBUI.Borders.empty(7, AccountUiSpacing.cardPadH)

        val smallFont = AccountUiType.ui(AccountUiType.caption)
        statusLabel.font = smallFont
        statusLabel.foreground = AccountUiColors.onInkDim

        readoutLabel = JLabel("CodeBuddy: —").apply {
            // 读数含中文账号名，走界面字体
            font = AccountUiType.ui(AccountUiType.label)
            foreground = AccountUiColors.onInk
        }
        val caret = JLabel("▾").apply {
            font = smallFont
            foreground = AccountUiColors.onInkDim
        }
        val readout = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            toolTipText = "点击切换状态栏额度维度"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(JLabel("●").apply {
                font = smallFont
                foreground = AccountUiColors.seal
            })
            add(readoutLabel)
            add(caret)
        }
        readout.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showDimMenu(readout)
        })
        panel.add(readout, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply { isOpaque = false }
        right.add(statusLabel)
        panel.add(right, BorderLayout.EAST)
        return panel
    }

    /** 状态栏维度菜单（五个维度与 HTML `.dim-menu` 一致） */
    private fun showDimMenu(anchor: Component) {
        val settings = AccountSettings.getInstance()
        val menu = JPopupMenu()
        val group = ButtonGroup()
        for (mode in QuotaDisplayMode.entries) {
            val item = JRadioButtonMenuItem(mode.label, mode == settings.statusBarMode)
            item.addActionListener {
                settings.setStatusBarMode(mode)
                CodeBuddyStatusBarWidget.invalidateCache()
                refresh()
            }
            group.add(item)
            menu.add(item)
        }
        // 状态栏贴着底边，菜单向上展开
        menu.show(anchor, 0, -menu.preferredSize.height - JBUI.scale(2))
    }

    /** 底栏读数文案：按当前维度从真实额度拼出来 */
    private fun readoutText(active: CredentialSnapshot?, quota: QuotaSummary?): String {
        val name = active?.displayName ?: "未检测到账号"
        if (quota == null) return "CodeBuddy: $name · 额度未查询"
        if (quota.error != null) return "CodeBuddy: $name · 查询失败"
        if (quota.packages.isEmpty()) return "CodeBuddy: $name · 无套餐"

        val unit = quota.unit.ifBlank { "credits" }
        val body = when (AccountSettings.getInstance().statusBarMode) {
            QuotaDisplayMode.TOTAL_PERCENT -> "${"%.0f".format(quota.usableRemainPercent)}% 剩余"
            QuotaDisplayMode.REMAIN ->
                "${AccountUiKit.formatNumber(quota.usableRemain)} / ${AccountUiKit.formatNumber(quota.usableSize)} $unit"
            QuotaDisplayMode.GROUP_DETAIL -> quota.detailText()
            QuotaDisplayMode.LOWEST_PACKAGE -> quota.tightest?.let {
                "${it.displayGroup} ${"%.0f".format(it.remainPercent)}%"
            } ?: quota.summaryText()
            QuotaDisplayMode.PACKAGE ->
                quota.groupByKeyword(AccountSettings.getInstance().statusBarPackage)?.let {
                    "${it.name} ${AccountUiKit.formatNumber(it.remain)} $unit"
                } ?: quota.summaryText()
        }
        return "CodeBuddy: $name · $body"
    }

    /** 切换日志：账号视图底部的 `.log-bar`，只保留最近几行 */
    private fun appendLog(text: String, notice: Boolean = false) {
        logLines.addLast(text)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        if (!::logPanel.isInitialized) return

        logPanel.removeAll()
        logLines.forEachIndexed { index, line ->
            logPanel.add(
                JLabel("› $line").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = if (notice && index == logLines.size - 1) {
                        AccountUiColors.brassInk
                    } else {
                        AccountUiColors.textMuted
                    }
                }
            )
        }
        logPanel.revalidate()
        logPanel.repaint()
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
        // 额度明细视图重建成本高（每个账号一张卡），只在它可见时刷新
        if (viewMode == VIEW_QUOTA) quotaView.reload()

        val target = when {
            saved.isEmpty() -> VIEW_EMPTY
            viewMode == VIEW_DASHBOARD -> VIEW_DASHBOARD
            viewMode == VIEW_QUOTA -> VIEW_QUOTA
            else -> VIEW_LIST
        }
        centerStack?.let { (it.layout as CardLayout).show(it, target) }

        // 底栏读数（状态栏口径）+ 各视图标题右侧的 meta
        readoutLabel.text = readoutText(active, active?.let { quotaMap[it.accountKey] })
        // 详细概览挂在底栏提示上，悬停可见，不占视觉
        statusLabel.toolTipText = buildSummary(saved, active, quotaMap)
        rangeLabel.text = buildString {
            append("${saved.size} 个账号")
            val domains = saved.map { it.domainHost.ifBlank { "—" } }.distinct().size
            if (saved.isNotEmpty()) append(" · $domains 个认证域")
            append(" · 数据已脱敏")
        }
        dashboardMeta.text = "「管理卡片」进入编辑模式可排序 / 增删 · 今天 " + java.time.LocalDate.now()

        applyViewState()
        updatePager()
        syncSelectionState()
    }

    /** 分页控件只在账号视图出现；其它视图用各自的标题行 */
    private fun applyViewState() {
        if (!::pagerPanel.isInitialized) return
        pagerPanel.isVisible = viewMode == VIEW_LIST
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

    /** 卡片勾选变化后同步全选复选框与「已选中」提示 */
    private fun syncSelectionState() {
        if (!::selectAllBox.isInitialized) return
        val total = gridView.totalCount()
        val checked = gridView.checkedCount()
        selectAllBox.isSelected = total > 0 && checked >= total
        val name = gridView.selectedCard()?.snapshot?.displayName
        selectionHint.text = "已选中：${name ?: "—"}"
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
        pageLabel.text = "第 ${index + 1} / $pages 页 · $from-$to 条"
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
        // 立即刷新看板，让「正在执行签到…」立刻出现
        dashboard.update()
    }

    /** 一键完成成长任务：接取未接取任务 + 行为上报类任务自动完成 + 领取奖励（实验特性，接口非官方） */
    private fun doGrowth() {
        if (!AccountSettings.getInstance().growthEnabled) {
            notify(
                "成长中心",
                "成长中心任务已在设置中关闭（Settings | Tools | CodeBuddy 账号管理器）",
                NotificationType.WARNING
            )
            return
        }
        GrowthTracker.completeAll { summary ->
            notify("成长中心", summary)
            dashboard.update()
        }
        // 立即刷新看板，让「正在执行成长任务…」立刻出现
        dashboard.update()
    }

    /** 旅行巡检：无猫则领养，有猫则派出 / 到站领奖 */
    private fun doGrowthTravel() {
        if (!AccountSettings.getInstance().growthEnabled) {
            notify("成长中心", "成长中心已在设置中关闭", NotificationType.WARNING)
            return
        }
        GrowthTracker.travelAll { summary ->
            notify("旅行巡检", summary)
            dashboard.update()
        }
        dashboard.update()
    }

    /** 活跃上报：为每个账号发一条对话活跃事件 */
    private fun doGrowthActivity() {
        if (!AccountSettings.getInstance().growthEnabled) {
            notify("成长中心", "成长中心已在设置中关闭", NotificationType.WARNING)
            return
        }
        GrowthTracker.activityAll { summary ->
            notify("活跃上报", summary)
            dashboard.update()
        }
        dashboard.update()
    }

    /** 全部保活：刷新各账号 accessToken 并写回账号库 */
    private fun doGrowthKeepalive() {
        if (!AccountSettings.getInstance().growthEnabled) {
            notify("成长中心", "成长中心已在设置中关闭", NotificationType.WARNING)
            return
        }
        GrowthTracker.keepaliveAll { summary ->
            notify("全部保活", summary)
            dashboard.update()
        }
        dashboard.update()
    }

    /** 打开成长中心窗口（任务明细表 + 全部动作） */
    private fun openGrowthCenter() {
        GrowthCenterDialog(project).show()
    }

    /** 单账号一键完成成长任务（卡片上的快捷入口） */
    private fun doGrowthOne(accountKey: String) {
        if (!AccountSettings.getInstance().growthEnabled) {
            notify("成长中心", "成长中心已在设置中关闭", NotificationType.WARNING)
            return
        }
        GrowthTracker.completeAll(setOf(accountKey)) { summary ->
            notify("成长中心", summary)
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

        batchItem?.isEnabled = false
        statusLabel.text = "正在把历史会话同步到 ${target.displayName}…"

        val copyMessages = AccountSettings.getInstance().copyMessagesOnSync
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = library.syncHistoryIndexes(target.uid, copyMessages = copyMessages)
            ApplicationManager.getApplication().invokeLater {
                HistoryReader.clearCache()
                batchItem?.isEnabled = true
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
            GridCardAction.DETAIL -> QuotaDetailDialog.show(project)
            GridCardAction.RENAME -> doEditAlias(card.snapshot)
            GridCardAction.REFRESH -> refreshQuotas(listOf(card.snapshot))
            GridCardAction.EXPORT -> doExportSingle(card.snapshot)
            GridCardAction.DELETE -> doDelete(card.snapshot)
            GridCardAction.GROWTH -> doGrowthOne(card.snapshot.accountKey)
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
                appendLog("已写入 secret-storage.xml")
                appendLog("已备份 secret-storage.xml.bak-<时间戳>")
                appendLog("已切换到 ${target.displayName}，重启 IDE 后生效", notice = true)
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
        batchItem?.isEnabled = false
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
                batchItem?.isEnabled = true
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
                appendLog("已恢复上一次切换前的凭据")
                appendLog("已回滚，重启 IDE 后生效", notice = true)
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
        refreshBtn.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = 0
            var fail = 0
            for (s in targets) {
                val summary = library.refreshQuota(s.accountKey)
                if (summary == null || summary.error != null) fail++ else ok++
            }
            ApplicationManager.getApplication().invokeLater {
                refresh()
                refreshBtn.isEnabled = true
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
        /** CardLayout 的四个视图名 */
        const val VIEW_DASHBOARD = "dashboard"
        const val VIEW_LIST = "list"
        const val VIEW_QUOTA = "quota"
        const val VIEW_EMPTY = "empty"

        /** 切换日志最多保留的行数（对应 `.log-bar` 的一小段历史） */
        const val MAX_LOG_LINES = 4
    }
}

/** 品牌标记：白框 + 朱红点，对应设计稿的 `.brand-mark` */
private class BrandMark : JComponent() {

    private val side = JBUI.scale(18)

    init {
        isOpaque = false
        preferredSize = Dimension(side, side)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = AccountUiColors.onInk
            g2.stroke = BasicStroke(2f)
            g2.draw(java.awt.geom.Rectangle2D.Float(1f, 1f, (side - 2).toFloat(), (side - 2).toFloat()))
            g2.color = AccountUiColors.seal
            val dot = (side / 3).toFloat()
            g2.fill(java.awt.geom.Rectangle2D.Float((side - dot) / 2f, (side - dot) / 2f, dot, dot))
        } finally {
            g2.dispose()
        }
    }
}
