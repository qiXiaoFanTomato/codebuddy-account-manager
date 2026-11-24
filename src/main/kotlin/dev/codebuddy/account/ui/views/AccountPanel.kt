package dev.codebuddy.account.ui.views

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.growth.GrowthTracker
import dev.codebuddy.account.history.HistoryReader
import dev.codebuddy.account.hotswap.CodeBuddyBridge
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.pet.CodeBuddyActions
import dev.codebuddy.account.pet.QuickPhraseDialog
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.QuotaScheduler
import dev.codebuddy.account.service.SwitchResult
import dev.codebuddy.account.ui.AccountSorter
import dev.codebuddy.account.ui.AccountSortMode
import dev.codebuddy.account.ui.components.AccountGridCard
import dev.codebuddy.account.ui.components.AccountUiKit
import dev.codebuddy.account.ui.components.FullWidthRow
import dev.codebuddy.account.ui.components.GlyphBadge
import dev.codebuddy.account.ui.components.GridCardAction
import dev.codebuddy.account.ui.components.JellyButton
import dev.codebuddy.account.ui.components.JellyButtonVariant
import dev.codebuddy.account.ui.components.RoundedCard
import dev.codebuddy.account.ui.components.SegmentedTabs
import dev.codebuddy.account.ui.components.ThemeSwatchIcon
import dev.codebuddy.account.ui.foundation.FlowWrapLayout
import dev.codebuddy.account.ui.foundation.NumberDisplay
import dev.codebuddy.account.ui.foundation.NumberDisplayBuilder
import dev.codebuddy.account.ui.foundation.RollingReadout
import dev.codebuddy.account.ui.foundation.ViewportTrigger
import dev.codebuddy.account.ui.statusbar.CodeBuddyStatusBarWidget
import dev.codebuddy.account.ui.tokens.AccentTheme
import dev.codebuddy.account.ui.tokens.AccountUiColors
import dev.codebuddy.account.ui.tokens.AccountUiRadius
import dev.codebuddy.account.ui.tokens.AccountUiSpacing
import dev.codebuddy.account.ui.tokens.AccountUiType
import dev.codebuddy.account.ui.tokens.baseLabelFont
import dev.codebuddy.account.ui.tokens.UiThemeEvents
import dev.codebuddy.account.ui.tokens.UiThemeListener
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
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
    private val statusLabel = JBLabel("")

    /** 账号视图 meta：账号数 / 认证域数 / 分页范围 */
    private val rangeLabel = JBLabel("").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textSecondary
    }

    /** 底栏读数：CodeBuddy: <账号> · <额度>，额度下降时递减滚动 */
    private lateinit var readoutLabel: RollingReadoutLabel

    private val settings = AccountSettings.getInstance()

    private val filterField = JBTextField(14)
    private var sortMode: AccountSortMode = settings.uiSortMode
    private val emptyPanel = buildEmptyPanel()

    /** 内嵌的额度明细视图（对应 `#view-quota`） */
    private val quotaView = QuotaView()

    /** 当前中心视图：看板 / 账号卡片 / 额度明细（取上次离开时的视图） */
    private var viewMode: String = settings.uiViewMode.takeIf {
        it == VIEW_DASHBOARD || it == VIEW_LIST || it == VIEW_QUOTA
    } ?: VIEW_DASHBOARD

    /** 排序下拉（构造时按 [sortMode] 回填，避免"下拉显示默认、实际按域名"） */
    private lateinit var sortCombo: JComboBox<AccountSortMode>

    /** 正在执行的动作 id：同一动作执行期间禁用入口，防连点（切换会重复写凭据） */
    private val inFlight = mutableSetOf<String>()

    /** 工具栏动作按钮（忙碌时统一禁用） */
    private val actionButtons = linkedMapOf<String, JellyButton>()

    /** 已写入凭据、等 IDE 重启生效的 accountKey（卡片显示「待重启」）。后台任务写、EDT 读 */
    @Volatile
    private var pendingRestartKey: String? = null

    /** 上次渲染的数据指纹：内容没变就不重建界面（消除一次动作刷新两遍） */
    private var lastRenderStamp: String? = null

    private val dashboard = DashboardPanel(
        onOpenList = { setView(VIEW_LIST) },
        onRefreshAll = { refreshQuotas(library.listSaved()) },
        onOpenQuotaDetail = { QuotaDetailDialog.show(project) },
        onOpenExpiringQuota = { QuotaDetailDialog.showExpiring(project) },
        onOpenUsageDetail = { UsageDetailDialog.show(project) },
        onOpenHistory = { doOpenHistory() },
        onOpenReport = { doOpenReport() },
        onCheckin = { doCheckin() },
        onGrowth = { doGrowth() },
        onGrowthTravel = { doGrowthTravel() },
        onGrowthActivity = { doGrowthActivity() },
        onGrowthKeepalive = { doGrowthKeepalive() },
        onOpenGrowthCenter = { openGrowthCenter() },
        onOpenUsageStats = { UsageStatsDialog.show(project) },
        onOpenSettings = { doOpenSettings() },
        onOpenPhrases = { QuickPhraseDialog.show(project.name) },
        onResetCodeBuddy = { CodeBuddyActions.resetNodeService(project) },
    )

    private lateinit var viewTabs: SegmentedTabs

    /** 抽屉面工具栏容器：只在账号视图出现，看板 / 额度明细整条隐藏 */
    private lateinit var toolDrawer: JComponent

    private lateinit var selectAllBox: JCheckBox
    private lateinit var selectionHint: JLabel
    private lateinit var bulkButton: JellyButton
    private lateinit var pagerPanel: JComponent
    private lateinit var pageLabel: JLabel
    private lateinit var prevPageBtn: JComponent
    private lateinit var nextPageBtn: JComponent
    private lateinit var pageSizeCombo: JComboBox<String>

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
        gridView.onContextMenu = { card, x, y -> showCardMenu(card, x, y) }

        // 多项目窗口同步：任一窗口改动账号库 / 配额后，其他窗口的面板跟着刷新
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(AccountEvents.TOPIC, object : AccountEvents {
                override fun accountsChanged() = scheduleRefresh()
                override fun quotaChanged() = scheduleRefresh()

                // 本窗口发起的布局变更自带过渡动画，重建会打断它
                override fun dashboardLayoutChanged(origin: Any) {
                    if (origin !== dashboard) scheduleRefresh()
                }

                // 限流记录变化：看板的「模型频率限制」卡片要跟着更新
                override fun runtimeChanged() = scheduleRefresh()
            })

        // 色调切换：卡片底色在构造时固定，需要重建一次才能换色
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(UiThemeEvents.TOPIC, object : UiThemeListener {
                override fun themeChanged(theme: AccentTheme) = scheduleRefresh()
            })

        gridView.setPageSize(settings.uiPageSize)
        installShortcuts()

        appendLog("写入凭据前会生成 secret-storage.xml.bak-<时间戳>")
        refresh()
    }

    /** 面板内快捷键：聚焦筛选（Ctrl+F）/ 清空筛选（Esc）/ 切换选中账号（Ctrl+Enter） */
    private fun installShortcuts() {
        fun bind(key: String, name: String, action: () -> Unit) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        // 筛选框只在账号视图可见，其它视图按快捷键不做无效聚焦
        bind("control F", "cb.focusFilter") {
            if (viewMode == VIEW_LIST) filterField.requestFocusInWindow()
        }
        bind("ESCAPE", "cb.clearFilter") {
            if (filterField.text.isNotEmpty()) filterField.text = ""
        }
        // 切换作用于账号卡片上的勾选，只在账号视图响应
        bind("control ENTER", "cb.switchSelected") {
            if (viewMode == VIEW_LIST) doSwitch()
        }
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
        val brandRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(9), 0)).apply { isOpaque = false }
        brandRow.add(AccountUiKit.brandMark())
        brandRow.add(JLabel("CodeBuddy Accounts").apply {
            font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.tab)
            foreground = AccountUiColors.onInk
        })
        val brand = centeredInTitleBar(brandRow)

        viewTabs = AccountUiKit.segmented(
            listOf("看板", "账号", "额度明细"),
            viewTabIndex(viewMode),
            { index -> setView(viewModeOf(index)) },
        ).apply { onDark = true }
        val tabsHolder = centeredInTitleBar(viewTabs, JBUI.scale(14), GridBagConstraints.WEST)

        // 标题栏工具区：与 HTML `.titlebar-tools` 的 ⋯ 对应，低频动作都收在这里
        val tools = centeredInTitleBar(buildMoreMenu(), 0, GridBagConstraints.EAST)

        bar.add(brand, BorderLayout.WEST)
        bar.add(tabsHolder, BorderLayout.CENTER)
        bar.add(tools, BorderLayout.EAST)
        return bar
    }

    /**
     * 标题栏分组容器：FlowLayout 只把行贴在容器顶部，46dp 高的深墨带里品牌会明显偏上；
     * GridBagLayout 的 WEST / EAST 锚点在水平靠边的同时保持垂直居中，对应设计稿的 align-items:center。
     */
    private fun centeredInTitleBar(
        content: JComponent,
        leftInset: Int = 0,
        anchor: Int = GridBagConstraints.CENTER,
    ): JComponent = JPanel(GridBagLayout()).apply {
        isOpaque = false
        add(content, GridBagConstraints().apply {
            this.anchor = anchor
            weightx = 1.0
            insets = Insets(0, leftInset, 0, 0)
        })
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
     *
     * 工具栏只在账号视图出现：看板与额度明细整条隐藏，避免在这些页面上出现
     * 依赖勾选 / 选中账号的入口。
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
        toolDrawer = drawer
        return drawer
    }

    /** 工具栏第一行：文字动作（窄窗时自动折行） */
    private fun buildActionRow(): JComponent {
        val row = FullWidthRow(FlowWrapLayout(JBUI.scale(6), JBUI.scale(6)))
        row.border = JBUI.Borders.emptyBottom(AccountUiSpacing.sm)

        row.add(toolButton("添加账号", JellyButtonVariant.PRIMARY) { doAddAccount() })
        row.add(toolButton("从 auth 目录导入") { doImport() })
        row.add(toolButton("历史对话") { doOpenHistory() })
        row.add(toolButton("历史共享到当前", tooltip = "把全部账号的历史会话合并到当前激活账号") {
            doSyncHistoryToCurrent()
        })
        row.add(
            toolButton("批量同步历史", tooltip = "把全部账号的历史会话互相合并到每一个账号") {
                doSyncAllAccounts()
            }
        )
        row.add(toolButton("切换") { doSwitch() })
        row.add(toolButton("智能调度") { doSmartSwitch() })
        row.add(toolButton("刷新额度") { doRefreshQuota() })
        row.add(
            toolButton("强制刷新", tooltip = "重启 CodeBuddy 内核并重新读取凭据（相当于 CodeBuddy 的「重置插件状态」）") {
                doForceRefreshCodeBuddy()
            }
        )
        row.add(toolButton("额度视图") { setView(VIEW_QUOTA) })
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
            // 全选作用于「当前筛选结果」（跨页），不只是当前页
            toolTipText = "全选当前筛选结果（跨页）"
            addActionListener { gridView.selectAll(isSelected) }
        }
        selectionHint = JLabel("已选中：—").apply {
            font = AccountUiType.ui(AccountUiType.label)
            foreground = AccountUiColors.textSecondary
        }
        bulkButton = AccountUiKit.button("批量操作", JellyButtonVariant.SECONDARY, null, "对勾选的账号执行批量操作") {
            showBulkMenu(bulkButton)
        }.apply { isEnabled = false }
        right.add(selectAllBox)
        right.add(selectionHint)
        right.add(bulkButton)

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
        tooltip: String? = null,
        onClick: () -> Unit,
    ): JellyButton = AccountUiKit.button(text, variant, null, tooltip ?: text, onClick).apply {
        this.danger = danger
        actionButtons[text] = this
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
        val combo = JComboBox(AccountSortMode.values())
        AccountUiKit.styleCombo(combo) { (it as? AccountSortMode)?.label ?: "" }
        combo.preferredSize = Dimension(JBUI.scale(124), JBUI.scale(28))
        // 先回填当前排序，再挂监听：否则构造期默认选中第 0 项，
        // 会出现「下拉显示默认、实际按域名分组」的不一致
        combo.selectedItem = sortMode
        combo.addActionListener {
            val picked = combo.selectedItem as? AccountSortMode ?: return@addActionListener
            if (picked == sortMode) return@addActionListener
            sortMode = picked
            settings.setUiSortMode(picked)
            refresh()
        }
        sortCombo = combo
        return combo
    }

    /** 更多操作：低频功能收进菜单，避免工具栏拥挤 */
    private fun buildMoreMenu(): JComponent {
        lateinit var btn: JComponent
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit): JMenuItem =
            JMenuItem(text).apply { addActionListener { action() } }.also { menu.add(it) }

        item("切换到选中账号") { doSwitch() }
        item("智能调度") { doSmartSwitch() }
        item("重命名") { doEditAlias() }
        item("删除") { doDelete() }
        menu.addSeparator()
        item("历史对话") { doOpenHistory() }
        item("对话日报 / 周报") { doOpenReport() }
        item("历史共享到当前") { doSyncHistoryToCurrent() }
        item("批量同步全部账号历史") { doSyncAllAccounts() }
        menu.addSeparator()
        item("额度明细窗口") { QuotaDetailDialog.show(project) }
        item("消耗明细") { UsageDetailDialog.show(project) }
        item("用量统计…") { UsageStatsDialog.show(project) }
        item("成长计划") { openGrowthCenter() }
        item("操作记录…") { openOperationLog() }
        menu.addSeparator()
        // 高频动作的护栏开关：关掉后「切换」不再弹确认框
        menu.add(
            JCheckBoxMenuItem("切换前确认").apply {
                isSelected = settings.confirmBeforeSwitch
                addActionListener { settings.setConfirmBeforeSwitch(isSelected) }
            }
        )
        item("界面色调…") { showThemeMenu(btn) }
        item("回滚上一次切换") { doRollback() }
        menu.addSeparator()
        item("强制刷新 CodeBuddy") { doForceRefreshCodeBuddy() }
        item("强制登出") { doForceLogout() }

        // 标题栏右侧的 ⋯：与 HTML `.titlebar-tools` 同一位置
        btn = AccountUiKit.button("", JellyButtonVariant.SECONDARY, AllIcons.Actions.More, "更多操作") {
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
        for (theme in AccentTheme.values()) {
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
        val text = filterField.text.trim()
        settings.setUiFilterText(text)
        gridView.setFilter(text)
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
        panel.add(viewHead("看板", null, dashboard.headerControls()), BorderLayout.NORTH)
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
    private fun viewHead(title: String, meta: JLabel?, actions: JComponent? = null): JComponent {
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
        if (meta != null) left.add(meta)
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
            selectedIndex = when (settings.uiPageSize) {
                10 -> 0
                50 -> 2
                else -> 1
            }
            AccountUiKit.styleCombo(this)
            preferredSize = Dimension(JBUI.scale(96), JBUI.scale(28))
            addActionListener {
                val size = when (selectedIndex) {
                    0 -> 10
                    2 -> 50
                    else -> 20
                }
                settings.setUiPageSize(size)
                gridView.setPageSize(size)
                updatePager()
            }
        }
        prevPageBtn = AccountUiKit.button("上一页", JellyButtonVariant.SECONDARY, null, "回到上一页") {
            gridView.setPage(gridView.pageIndex() - 1)
            updatePager()
        }
        pageLabel = JLabel("第 1 / 1 页").apply {
            font = small
            foreground = AccountUiColors.textMuted
            border = JBUI.Borders.empty(0, 4)
        }
        nextPageBtn = AccountUiKit.button("下一页", JellyButtonVariant.SECONDARY, null, "翻到下一页") {
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
        // 记住视图：工具窗关掉再打开、重启 IDE 后仍落在同一视图
        settings.setUiViewMode(mode)
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

        val badge = AccountUiKit.glyph("CB", AccountUiColors.accentSoft, AccountUiColors.accent, size = 40).apply {
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
        val action = AccountUiKit.button("立即导入", JellyButtonVariant.PRIMARY, null, "从 auth 目录导入") { doImport() }
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

        readoutLabel = RollingReadoutLabel("CodeBuddy: —").apply {
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
        for (mode in QuotaDisplayMode.values()) {
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

    /** 底栏读数：账号名前缀保持静态，额度正文里的数字标为滚动槽位 */
    private fun readoutDisplay(active: CredentialSnapshot?, quota: QuotaSummary?): NumberDisplay {
        val b = NumberDisplayBuilder()
        b.text("CodeBuddy: ${active?.displayName ?: "未检测到账号"} · ")
        b.numbered(readoutBody(quota))
        return b.build()
    }

    /** 底栏额度正文：按当前维度从真实额度拼出来 */
    private fun readoutBody(quota: QuotaSummary?): String {
        if (quota == null) return "额度未查询"
        if (quota.error != null) return "查询失败"
        if (quota.packages.isEmpty()) return "无套餐"

        val unit = quota.unit.ifBlank { "credits" }
        return when (AccountSettings.getInstance().statusBarMode) {
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
    }

    /** 切换日志：账号视图底部的 `.log-bar`，只保留最近几行；同一条同时落盘便于回看 */
    private fun appendLog(text: String, notice: Boolean = false) {
        logLines.addLast(text)
        dev.codebuddy.account.service.OperationLog.log(text)
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

        // 数据指纹去重：同一批数据不重复重建界面。
        // 以前「刷新额度」先自己 refresh() 再发 quotaChanged 广播，一次动作会重建两遍，
        // 滚动位置、hover 状态全丢——指纹相同直接跳过第二次。
        val stamp = renderStamp(saved, active, quotaMap)
        if (stamp == lastRenderStamp) return
        lastRenderStamp = stamp

        val sorted = AccountSorter.sort(saved, sortMode, active?.accountKey) { key ->
            quotaMap[key]?.usableRemainPercent
        }
        gridView.pendingRestartKey = pendingRestartKey
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
        readoutLabel.setReadout(readoutDisplay(active, active?.let { quotaMap[it.accountKey] }))
        // 详细概览挂在底栏提示上，悬停可见，不占视觉
        statusLabel.toolTipText = buildSummary(saved, active, quotaMap)
        rangeLabel.text = buildString {
            append("${saved.size} 个账号")
            val domains = saved.map { it.domainHost.ifBlank { "—" } }.distinct().size
            if (saved.isNotEmpty()) append(" · $domains 个认证域")
            if (pendingRestartKey != null) append(" · 待重启生效")
        }

        applyViewState()
        updatePager()
        syncSelectionState()
    }

    /** 渲染指纹：账号 / 额度 / 排序 / 视图 / 色调 / 待重启标记 任一变化才需要重建 */
    private fun renderStamp(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotaMap: Map<String, QuotaSummary>,
    ): String {
        val accounts = saved.joinToString(";") {
            "${it.accountKey}:${it.alias}:${it.displayName}:${it.expiresAt}"
        }
        val quotas = quotaMap.entries
            .sortedBy { it.key }
            .joinToString(";") { (key, q) ->
                "$key:${q.fetchedAt}:${q.error}:${q.lastError}:${q.totalRemain}"
            }
        return listOf(
            accounts,
            active?.accountKey ?: "",
            quotas,
            sortMode.name,
            viewMode,
            AccountUiColors.theme.name,
            settings.statusBarMode.name,
            pendingRestartKey ?: "",
        ).joinToString("|")
    }

    /** 分页控件只在账号视图出现；其它视图用各自的标题行 */
    private fun applyViewState() {
        applyToolbarScope()
        if (!::pagerPanel.isInitialized) return
        pagerPanel.isVisible = viewMode == VIEW_LIST
    }

    /** 工具栏整条只在账号视图出现，看板 / 额度明细不显示 */
    private fun applyToolbarScope() {
        if (!::toolDrawer.isInitialized) return
        val accountsView = viewMode == VIEW_LIST
        if (toolDrawer.isVisible == accountsView) return
        toolDrawer.isVisible = accountsView
        // 收起 / 展开会改变标题栏下方的可用高度，需要让外层重新布局
        toolDrawer.parent?.revalidate()
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
        val picked = gridView.selectedSnapshots()
        selectionHint.text = when {
            picked.isEmpty() -> "已选中：—"
            picked.size == 1 -> "已选中：${picked[0].displayName}"
            else -> "已选中 ${picked.size} 项"
        }
        if (::bulkButton.isInitialized) bulkButton.isEnabled = picked.isNotEmpty()
    }

    /** 批量操作菜单：只对勾选的账号生效，不再需要「先切到账号视图再找按钮」 */
    private fun showBulkMenu(anchor: JComponent) {
        val picked = gridView.selectedSnapshots()
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit): JMenuItem =
            JMenuItem(text).apply {
                isEnabled = picked.isNotEmpty()
                addActionListener { action() }
            }.also { menu.add(it) }

        val count = picked.size
        item("刷新额度（$count 个）") { refreshQuotas(picked) }
        item("导出凭据（$count 个）") { doExport() }
        item("同步历史到当前账号") { doSyncHistoryToCurrent() }
        menu.addSeparator()
        item("删除（$count 个）") { doDelete() }
        menu.addSeparator()
        item("清空勾选") { gridView.clearSelection() }
        menu.show(anchor, 0, anchor.height)
    }

    /**
     * 工具栏动作的目标账号：只看勾选，不做任何隐式回退。
     *
     * 以前「切换 / 删除 / 刷新额度」在没有勾选时会自动作用于激活账号或第一张卡，
     * 用户看不到到底操作了谁，属于危险默认。
     */
    private fun selectionTargets(actionLabel: String): List<CredentialSnapshot>? {
        val picked = gridView.selectedSnapshots()
        if (picked.isEmpty()) {
            statusLabel.text = "请先在「账号」视图勾选账号，再执行「$actionLabel」"
            return null
        }
        return picked
    }

    /** 动作闸门：同一动作执行期间禁止再次进入（连点会让切换重复写凭据） */
    private fun beginAction(id: String): Boolean {
        if (!inFlight.add(id)) {
            statusLabel.text = "上一次操作还在进行中，请稍候…"
            return false
        }
        updateBusyUi()
        return true
    }

    private fun endAction(id: String) {
        inFlight.remove(id)
        updateBusyUi()
    }

    /** 忙碌时把动作按钮整体禁用，避免"点了没反应再点一次" */
    private fun updateBusyUi() {
        val busy = inFlight.isNotEmpty()
        actionButtons.values.forEach { it.isEnabled = !busy }
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

    /** 添加账号：OAuth 授权 / Token·JSON / 本地导入三入口 */
    private fun doAddAccount() {
        AddAccountDialog.show(project) { count ->
            if (count <= 0) return@show
            refresh()
            notifySuccess("添加成功", "已添加/更新 $count 个账号，点「刷新额度」可拉取资源包数据。")
        }
    }

    private fun doImport() {
        if (!beginAction("import")) return
        statusLabel.text = "正在扫描 auth 目录…"
        ApplicationManager.getApplication().executeOnPooledThread {
            // 兜住异常：否则下面的 invokeLater 不执行，动作闸门会永久锁住按钮
            val count = runCatching { library.importFromAuthDir() }.getOrDefault(-1)
            ApplicationManager.getApplication().invokeLater {
                endAction("import")
                refresh()
                when {
                    count < 0 -> notify("导入失败", "读取 auth 目录时出错，请查看 IDE 日志。", NotificationType.ERROR)
                    count > 0 -> notify("导入完成", "从 auth 目录导入/更新了 $count 个账号。")
                    else -> notify("没有新账号", "auth 目录下未发现新的可导入凭据。", NotificationType.INFORMATION)
                }
            }
        }
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

        if (!beginAction("syncCurrent")) return
        statusLabel.text = "正在把历史会话同步到 ${target.displayName}…"

        val copyMessages = settings.copyMessagesOnSync
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = library.syncHistoryIndexes(target.uid, copyMessages = copyMessages)
            ApplicationManager.getApplication().invokeLater {
                HistoryReader.clearCache()
                endAction("syncCurrent")
                refresh()
                if (r.error != null) {
                    notify("历史同步失败", r.error, NotificationType.ERROR)
                } else {
                    notifySuccess(
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
        val picked = selectionTargets("切换") ?: return
        if (picked.size > 1) {
            statusLabel.text = "切换一次只能针对一个账号，请只勾选一个"
            return
        }
        switchToWithMode(picked[0])
    }

    /**
     * 切换前确认：切换是全库最高影响的动作（写凭据 + 可能要重启 IDE），
     * 以前唯一没有确认框。可在 ⋯ 菜单里关掉。
     */
    private fun confirmSwitch(target: CredentialSnapshot): Boolean {
        if (!settings.confirmBeforeSwitch) return true
        val active = library.currentActive()
        if (active?.accountKey == target.accountKey) {
            statusLabel.text = "「${target.displayName}」已经是当前激活账号"
            return false
        }
        val hot = settings.hotSwitchEnabled && CodeBuddyBridge.isAvailable()
        val message = buildString {
            append("当前账号：${active?.displayName ?: "未检测到"}\n")
            append("切换目标：${target.displayName}（${target.domainHost.ifBlank { "—" }}）\n")
            append("生效方式：${if (hot) "热切换，无需重启 IDE" else "写入凭据后需重启 IDE 生效"}\n")
            if (settings.autoSyncHistoryOnSwitch) append("历史会话：切换前会合并到目标账号\n")
            append("\n确定切换？（可在「⋯ → 切换前确认」关闭此提示）")
        }
        return Messages.showYesNoDialog(
            project,
            message,
            "切换账号",
            "切换",
            "取消",
            Messages.getQuestionIcon()
        ) == Messages.YES
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

    /** 卡片右键菜单：与卡片按钮同源，但不占卡片空间 */
    private fun showCardMenu(card: AccountGridCard, x: Int, y: Int) {
        val menu = JPopupMenu()
        fun item(text: String, action: () -> Unit): JMenuItem =
            JMenuItem(text).apply { addActionListener { action() } }.also { menu.add(it) }

        item("切换到此账号") { switchToWithMode(card.snapshot) }
        item("额度明细弹窗") { QuotaDetailDialog.show(project) }
        item("刷新该账号额度") { refreshQuotas(listOf(card.snapshot)) }
        menu.addSeparator()
        item(if (card.checked) "取消勾选" else "勾选该账号") { gridView.toggleChecked(card) }
        item("重命名") { doEditAlias(card.snapshot) }
        item("导出凭据…") { doExportSingle(card.snapshot) }
        menu.addSeparator()
        item("删除") { doDelete(card.snapshot) }
        menu.show(card, x, y)
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
        // 共享额度池：所有账号的可用细项按到期时间摊平成一条使用顺序
        val pool = QuotaScheduler.mergeShares(
            QuotaScheduler.pool(saved, library.quotaCache.loadAll(), exclude = active?.accountKey)
        )
        val ok = Messages.showYesNoDialog(
            project,
            "额度共享池（跨账号，优先消耗离到期最近的额度）：\n\n" +
                (if (pool.isEmpty()) "暂无可共享的额度" else QuotaScheduler.describePool(pool, 5)) + "\n\n" +
                "推荐切换：${best.snapshot.displayName}（${best.snapshot.domainHost}）\n" +
                "理由：${best.reason()}\n" +
                "切换方式：${if (hot) "热切换，不重启 IDE" else "写入凭据后需重启 IDE"}\n\n确定切换？",
            "智能调度",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        // 这里已经确认过一次，不再叠第二个确认框
        switchToWithMode(best.snapshot, skipConfirm = true)
    }

    /**
     * 按设置决定「热切换」或「常规切换（需重启 IDE）」。
     *
     * 历史合并 + 凭据写入都是磁盘 IO（可能到秒级），整条链放后台任务跑：
     * 以前全在 EDT 上同步执行，界面冻结且没有进度提示，
     * 用户只会"点了没反应"然后重复点击。
     */
    private fun switchToWithMode(target: CredentialSnapshot, skipConfirm: Boolean = false) {
        if (!skipConfirm && !confirmSwitch(target)) return

        // 「是否同步历史」的追问必须在 EDT 上先问清楚，再进后台
        val sync = settings.autoSyncHistoryOnSwitch || Messages.showYesNoDialog(
            project,
            "是否同时把其他账号的历史会话索引同步到 ${target.displayName}？\n（保证切号后侧边栏能看到全部历史对话）",
            "切换账号",
            "同步",
            "跳过",
            Messages.getQuestionIcon()
        ) == Messages.YES

        if (!beginAction("switch")) return
        statusLabel.text = "正在切换到 ${target.displayName}…"
        val hot = settings.hotSwitchEnabled && CodeBuddyBridge.isAvailable()

        object : Task.Backgroundable(project, "切换账号：${target.displayName}", true) {
            private var ok = false
            private var message = ""
            private var historyAdded = 0
            private var historyCopied = 0
            private var historyError: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    if (sync) {
                        indicator.text = "正在合并历史会话…"
                        val r = library.syncHistoryIndexes(target.uid, copyMessages = settings.copyMessagesOnSync)
                        HistoryReader.clearCache()
                        historyAdded = r.conversationsAdded
                        historyCopied = r.conversationsCopied
                        historyError = r.error
                    }
                    indicator.text = "正在写入凭据…"
                    if (hot) {
                        val r = library.hotSwitchTo(target.accountKey, project)
                        ok = r.success
                        message = r.message ?: ""
                        // 热切成功 = 已生效；失败说明凭据写了但内核没重载
                        pendingRestartKey = if (r.success) null else target.accountKey
                    } else {
                        // 乐观标记：switchTo 内部会广播 accountsChanged，
                        // 先把「待重启」写进去，那次刷新就能同时把卡片标出来
                        pendingRestartKey = target.accountKey
                        when (val r = library.switchTo(target.accountKey)) {
                            is SwitchResult.Success -> ok = true
                            is SwitchResult.Failure -> {
                                message = r.message
                                pendingRestartKey = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    message = e.message ?: "未知错误"
                    pendingRestartKey = null
                }
            }

            override fun onSuccess() {
                if (sync && historyError == null && !hot && historyAdded > 0) {
                    // 会话迁移完成：桌宠反馈（以前成功时静默，容易被误认为没同步）
                    dev.codebuddy.account.pet.CbEventCenter.onHistoryMigrated(
                        target.displayName, historyAdded, historyCopied,
                    )
                }
                val historyMsg = when {
                    !sync -> ""
                    historyError != null -> "\n（历史索引同步失败：$historyError）"
                    else -> "\n已同步历史会话：新增 $historyAdded 个会话，复制消息目录 $historyCopied 个。"
                }

                when {
                    hot && ok -> notifySuccess(
                        "已热切换到 ${target.displayName}",
                        message.ifBlank { "CodeBuddy 已重新加载新账号，无需重启 IDE。" }
                    )

                    hot -> notify(
                        "热切换未完成",
                        "${message.ifBlank { "未知原因" }}\n\n凭据已写入，重启 IDE 后生效。",
                        NotificationType.WARNING
                    )

                    ok -> {
                        appendLog("已写入 secret-storage.xml")
                        appendLog("已备份 secret-storage.xml.bak-<时间戳>")
                        appendLog("已切换到 ${target.displayName}，重启 IDE 后生效", notice = true)
                        notifySuccess(
                            "切换成功",
                            "已切换到 ${target.displayName}。$historyMsg\n\n请重启 IDE 使 CodeBuddy 插件重新加载凭据。"
                        )
                    }

                    else -> notify("切换失败", message.ifBlank { "未知错误" }, NotificationType.ERROR)
                }
            }

            override fun onFinished() {
                endAction("switch")
                refresh()
            }
        }.queue()
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
        if (!beginAction("syncAll")) return
        statusLabel.text = "正在批量同步 ${saved.size} 个账号的历史会话…"

        object : Task.Backgroundable(project, "批量同步历史会话", true) {
            private var added = 0
            private var copied = 0
            private val failed = mutableListOf<String>()

            override fun run(indicator: ProgressIndicator) {
                for ((index, s) in saved.withIndex()) {
                    if (indicator.isCanceled) return
                    indicator.fraction = (index + 1).toDouble() / saved.size
                    indicator.text = "正在同步 ${s.displayName}（${index + 1}/${saved.size}）"
                    val r = library.syncHistoryIndexes(s.uid, copyMessages = copyMessages)
                    if (r.error != null) failed += "${s.displayName}：${r.error}"
                    else {
                        added += r.conversationsAdded
                        copied += r.conversationsCopied
                    }
                }
            }

            override fun onSuccess() {
                if (failed.isEmpty()) {
                    notifySuccess(
                        "批量同步完成",
                        "账号 ${saved.size} 个：新增会话 $added 个，复制消息目录 $copied 个。\n" +
                            "若侧边栏未刷新，请重启 IDE。"
                    )
                } else {
                    notify(
                        "批量同步部分失败",
                        "成功 ${saved.size - failed.size} 个，失败 ${failed.size} 个：\n" +
                            failed.take(8).joinToString("\n") { "· $it" } +
                            if (failed.size > 8) "\n…另有 ${failed.size - 8} 个" else "",
                        NotificationType.WARNING
                    )
                }
            }

            override fun onFinished() {
                HistoryReader.clearCache()
                endAction("syncAll")
                refresh()
            }
        }.queue()
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
            "回滚",
            "取消",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return
        if (!beginAction("rollback")) return
        statusLabel.text = "正在回滚凭据…"

        object : Task.Backgroundable(project, "回滚上一次切换", false) {
            private var result: SwitchResult? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = library.rollbackLastSwitch()
            }

            override fun onSuccess() {
                when (val r = result) {
                    is SwitchResult.Success -> {
                        appendLog("已恢复上一次切换前的凭据")
                        appendLog("已回滚，重启 IDE 后生效", notice = true)
                        pendingRestartKey = null
                        notify("回滚成功", "已恢复上一次切换前的凭据。请重启 IDE 生效。")
                    }

                    is SwitchResult.Failure -> notify("回滚失败", r.message, NotificationType.ERROR)
                    null -> notify("回滚失败", "后台任务未返回结果，请查看 IDE 日志。", NotificationType.ERROR)
                }
            }

            override fun onFinished() {
                endAction("rollback")
                refresh()
            }
        }.queue()
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
                notifySuccess(
                    "已退出登录",
                    "已清除 CodeBuddy 登录凭据。请重启 IDE，CodeBuddy 会回到未登录状态，之后可重新登录。"
                )
            }
            is SwitchResult.Failure -> notify("操作失败", r.message, NotificationType.WARNING)
        }
    }

    /**
     * 强制刷新 CodeBuddy：逐个重启全部打开项目窗口的 CodeBuddy Node 内核并重新读取凭据
     * （等价于各窗口 CodeBuddy 设置里的「Reset Plugin Status」，不重启 IDE）。
     *
     * CodeBuddy 的内核是 per-project 的，一次只重启当前窗口会漏掉其它项目窗口，
     * 因此这里遍历 [ProjectManager.getOpenProjects] 串行逐个重启：前一个重建完成再起下一个，
     * 避免多个 Node 进程同时起停把 IDE 卡住。
     *
     * 用于侧边栏卡死、登录态 / 额度与账号库不同步而重启 IDE 又太重的场景。
     */
    private fun doForceRefreshCodeBuddy() {
        if (!CodeBuddyBridge.isAvailable()) {
            notify(
                "未检测到 CodeBuddy",
                "当前 IDE 未安装 CodeBuddy 插件（或版本不兼容），无法刷新。",
                NotificationType.WARNING
            )
            return
        }
        val targets = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        if (targets.isEmpty()) {
            statusLabel.text = "没有已打开的项目窗口可刷新"
            return
        }

        val ok = Messages.showYesNoDialog(
            project,
            "将逐个重启 ${targets.size} 个项目窗口的 CodeBuddy 内核（不重启 IDE）。\n\n" +
                "• 每个窗口的 CodeBuddy Node 进程依次重建，完成一个再起下一个\n" +
                "• 正在进行的对话请求会被中断，会话历史不受影响\n" +
                "• 窗口越多耗时越长，过程中可继续使用 IDE\n\n确定刷新？",
            "强制刷新 CodeBuddy",
            "刷新",
            "取消",
            Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return
        if (!beginAction("forceRefresh")) return
        statusLabel.text = "正在逐个重启 ${targets.size} 个窗口的 CodeBuddy 内核…"

        object : Task.Backgroundable(project, "重启 CodeBuddy 内核", true) {
            private val failed = mutableListOf<String>()
            private var done = 0

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                for ((index, p) in targets.withIndex()) {
                    if (indicator.isCanceled) return
                    indicator.fraction = (index + 1).toDouble() / targets.size
                    indicator.text = "正在重启 ${p.name}（${index + 1}/${targets.size}）"
                    if (CodeBuddyBridge.restartAgent(p)) done++ else failed += p.name
                    // 相邻窗口之间留一点间隔，让上一个 Node 进程彻底退出
                    if (index < targets.lastIndex) {
                        runCatching { Thread.sleep(RESTART_GAP_MS) }
                    }
                }
            }

            override fun onSuccess() {
                if (done > 0) {
                    appendLog("已逐个重启 $done 个窗口的 CodeBuddy 内核", notice = true)
                }
                when {
                    failed.isEmpty() -> notifySuccess(
                        "CodeBuddy 已刷新",
                        "已逐个重启 ${targets.size} 个窗口的 CodeBuddy 内核（IDE 未重启），侧边栏与额度显示应已恢复同步。"
                    )

                    done == 0 -> notify(
                        "刷新失败",
                        "全部窗口均未触发重启（可能是 CodeBuddy 版本变更导致重置动作缺失），请重启 IDE 后再试。\n\n" +
                            failed.joinToString("\n") { "· $it" },
                        NotificationType.ERROR
                    )

                    else -> notify(
                        "部分窗口刷新失败",
                        "成功 $done 个，失败 ${failed.size} 个：\n" +
                            failed.joinToString("\n") { "· $it" },
                        NotificationType.WARNING
                    )
                }
            }

            override fun onCancel() {
                notify(
                    "已中止刷新",
                    "已重启 $done / ${targets.size} 个窗口的 CodeBuddy 内核，剩余窗口未处理。",
                    NotificationType.WARNING
                )
            }

            override fun onFinished() {
                endAction("forceRefresh")
                if (project.isDisposed) return
                CodeBuddyStatusBarWidget.invalidateCache()
                refresh()
            }
        }.queue()
    }

    private fun doRefreshQuota() {
        val picked = gridView.selectedSnapshots()
        val targets = when {
            picked.isNotEmpty() -> picked
            // 「未选中时刷新全部」现在真的生效了：以前 selectedCard() 永不返回 null，
            // 这个设置形同虚设，未勾选时永远只刷激活账号
            settings.refreshAllWhenNoSelection -> library.listSaved()
            else -> emptyList()
        }
        if (targets.isEmpty()) {
            statusLabel.text = "请先勾选账号（或在设置中开启「未选中时刷新全部」）"
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
        if (!beginAction("refresh")) return
        statusLabel.text = "正在查询 ${targets.size} 个账号的额度…"

        ApplicationManager.getApplication().executeOnPooledThread {
            // 并发刷新（HTTP 出口仍受全局信号量限制），失败逐条带原因
            val keys = targets.map { it.accountKey }
            val result = runCatching { library.refreshQuotas(keys) }.getOrElse { e ->
                dev.codebuddy.account.service.RefreshBatchResult(
                    0,
                    keys.map { it to (e.message ?: "未知错误") },
                )
            }
            val nameOf = targets.associate { it.accountKey to it.displayName }
            ApplicationManager.getApplication().invokeLater {
                endAction("refresh")
                // 只发广播：本面板的 refresh 由事件驱动，且指纹去重后不会重建两遍
                AccountEvents.fireQuotaChanged()
                refresh()
                val detail = if (result.failures.isEmpty()) {
                    "成功 ${result.ok} 个。"
                } else {
                    "成功 ${result.ok} 个，失败 ${result.failures.size} 个：\n" +
                        result.failures.take(8).joinToString("\n") {
                            "· ${nameOf[it.first] ?: it.first}：${it.second}"
                        } +
                        if (result.failures.size > 8) "\n…另有 ${result.failures.size - 8} 个" else ""
                }
                notify(
                    "额度刷新完成",
                    detail,
                    if (result.failures.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
                )
            }
        }
    }

    /** @param target 为 null 时作用于勾选的账号（只支持一个） */
    private fun doEditAlias(target: CredentialSnapshot? = null) {
        val snapshot = target ?: run {
            val picked = selectionTargets("重命名") ?: return
            if (picked.size > 1) {
                statusLabel.text = "重命名一次只能针对一个账号，请只勾选一个"
                return
            }
            picked[0]
        }
        val input = Messages.showInputDialog(
            project,
            "为该账号设置别名（留空则显示原昵称）：",
            "重命名账号",
            Messages.getQuestionIcon(),
            snapshot.alias,
            null
        ) ?: return
        if (library.setAlias(snapshot.accountKey, input)) {
            statusLabel.text = if (input.isBlank()) "已清除「${snapshot.displayName}」的别名" else "已重命名为「$input」"
            refresh()
        } else {
            notify("重命名失败", "请查看 IDE 日志。", NotificationType.ERROR)
        }
    }

    /** @param target 为 null 时作用于勾选的账号（支持批量） */
    private fun doDelete(target: CredentialSnapshot? = null) {
        val targets = target?.let { listOf(it) } ?: selectionTargets("删除") ?: return
        val activeKey = library.currentActive()?.accountKey
        val deletable = targets.filterNot { it.accountKey == activeKey }
        if (deletable.isEmpty()) {
            notify("无法删除", "不能删除当前激活账号。", NotificationType.WARNING)
            return
        }

        val names = deletable.take(10).joinToString("\n") {
            "· ${it.displayName}（${it.domainHost.ifBlank { "—" }}）"
        }
        val more = if (deletable.size > 10) "\n…另有 ${deletable.size - 10} 个" else ""
        val skipped = if (deletable.size < targets.size) "\n\n（当前激活账号不能删除，已自动跳过）" else ""
        val ok = Messages.showYesNoDialog(
            project,
            "确定从账号库移除 ${deletable.size} 个账号？\n" +
                "（不影响 IDE 当前凭据；快照会移到 codebuddy-accounts/trash，可手工找回）\n\n" +
                names + more + skipped,
            "删除账号",
            "删除",
            "取消",
            Messages.getWarningIcon()
        ) == Messages.YES
        if (!ok) return
        if (!beginAction("delete")) return

        statusLabel.text = "正在删除 ${deletable.size} 个账号…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val failed = mutableListOf<CredentialSnapshot>()
            for (s in deletable) {
                if (library.delete(s.accountKey)) library.quotaCache.remove(s.accountKey) else failed += s
            }
            ApplicationManager.getApplication().invokeLater {
                endAction("delete")
                gridView.clearSelection()
                refresh()
                if (failed.isEmpty()) {
                    notifySuccess("已删除", "已从账号库移除 ${deletable.size} 个账号。")
                } else {
                    notify(
                        "部分删除失败",
                        "失败 ${failed.size} 个：\n" + failed.take(8).joinToString("\n") { "· ${it.displayName}" },
                        NotificationType.ERROR
                    )
                }
            }
        }
    }

    /** 导出前的明文凭据确认（导出是"把全部 token 写到磁盘"的动作） */
    private fun confirmPlainTextExport(count: Int): Boolean = Messages.showYesNoDialog(
        project,
        "将把 $count 个账号的完整凭据（含 accessToken / refreshToken）以明文 JSON 导出。\n\n" +
            "导出文件等同于账号本身，请存放在安全位置，不要提交到任何代码仓库或云同步目录。\n\n确定继续？",
        "导出账号凭据",
        "继续导出",
        "取消",
        Messages.getWarningIcon()
    ) == Messages.YES

    /** 导出单个账号的凭据快照 */
    private fun doExportSingle(snapshot: CredentialSnapshot) {
        if (!confirmPlainTextExport(1)) return
        val descriptor = FileSaverDescriptor("导出账号凭据", "选择保存位置", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as java.nio.file.Path?, "codebuddy-${snapshot.uid.take(8)}.json")
            ?: return
        val path = wrapper.file.toPath()
        // 序列化 + 写盘都是重 IO，不放在 EDT 上
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Files.writeString(path, library.exportJson(setOf(snapshot.accountKey)))
                ApplicationManager.getApplication().invokeLater {
                    notifySuccess(
                        "导出成功",
                        "已导出 ${snapshot.displayName} 到：${path.toAbsolutePath()}\n\n注意：文件包含完整凭据，请妥善保管。"
                    )
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    notify("导出失败", e.message ?: "未知错误", NotificationType.ERROR)
                }
            }
        }
    }

    private fun doExport() {
        val picked = gridView.selectedSnapshots()
        val count = if (picked.isEmpty()) library.listSaved().size else picked.size
        if (count == 0) {
            notify("没有可导出的账号", "账号库为空，请先导入账号。", NotificationType.WARNING)
            return
        }
        if (!confirmPlainTextExport(count)) return

        val descriptor = FileSaverDescriptor("导出账号库", "选择保存位置", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as java.nio.file.Path?, "codebuddy-accounts.json") ?: return
        val path = wrapper.file.toPath()
        val keys = if (picked.isEmpty()) null else picked.map { it.accountKey }.toSet()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Files.writeString(path, library.exportJson(keys))
                ApplicationManager.getApplication().invokeLater {
                    notifySuccess(
                        "导出成功",
                        "已导出 $count 个账号到：${path.toAbsolutePath()}\n\n注意：文件包含完整凭据，请妥善保管。"
                    )
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    notify("导出失败", e.message ?: "未知错误", NotificationType.ERROR)
                }
            }
        }
    }

    private fun doImportLibrary() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val file = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project)
            .firstOrNull() ?: return

        val text = try {
            Files.readString(Paths.get(file.path))
        } catch (e: Exception) {
            notify("导入失败", "读取文件失败：${e.message}", NotificationType.ERROR)
            return
        }
        val preview = library.previewImportJson(text)
        if (preview == null) {
            notify("导入失败", "文件格式不正确。", NotificationType.ERROR)
            return
        }
        if (preview.total == 0) {
            notify("导入失败", "文件中没有可识别的账号凭据。", NotificationType.WARNING)
            return
        }

        // 覆盖式合并前必须让用户看到影响面
        val ok = Messages.showYesNoDialog(
            project,
            "文件中有 ${preview.total} 个账号：\n" +
                "· 新增 ${preview.added} 个\n" +
                "· 覆盖已有 ${preview.overwritten} 个（以文件中的凭据为准）\n\n确定导入？",
            "导入账号库",
            "导入",
            "取消",
            if (preview.overwritten > 0) Messages.getWarningIcon() else Messages.getQuestionIcon()
        ) == Messages.YES
        if (!ok) return

        if (!beginAction("importLibrary")) return
        statusLabel.text = "正在导入 ${preview.total} 个账号…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val count = library.importJson(text)
            ApplicationManager.getApplication().invokeLater {
                endAction("importLibrary")
                refresh()
                if (count >= 0) {
                    notify("导入完成", "从文件导入了 $count 个账号（其中覆盖 ${preview.overwritten} 个）。")
                } else {
                    notify("导入失败", "文件格式不正确。", NotificationType.ERROR)
                }
            }
        }
    }

    private fun doOpenSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AccountSettingsConfigurable::class.java)
        // 设置可能改了状态栏显隐、刷新间隔
        refresh()
    }

    /**
     * 统一提示出口：优先由桌宠承载，桌宠不可用时退回 IDE 气泡通知。
     * 见 [dev.codebuddy.account.pet.PetNotifier]。
     */
    private fun notify(title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        dev.codebuddy.account.service.OperationLog.log("[$title] $content")
        dev.codebuddy.account.pet.PetNotifier.notify(title, content, type, project)
    }

    /** 操作成功的反馈（桌宠播「成功」动作） */
    private fun notifySuccess(title: String, content: String) {
        dev.codebuddy.account.service.OperationLog.log("[$title] $content")
        dev.codebuddy.account.pet.PetNotifier.success(title, content, project)
    }

    /** 打开操作记录文件（IDE 编辑器内查看，可搜索历史失败原因） */
    private fun openOperationLog() {
        if (!java.nio.file.Files.isRegularFile(dev.codebuddy.account.service.OperationLog.file)) {
            notify("没有操作记录", "还没有产生操作记录。", NotificationType.INFORMATION)
            return
        }
        val virtual = com.intellij.openapi.vfs.VfsUtil.findFileByIoFile(
            dev.codebuddy.account.service.OperationLog.file.toFile(),
            true,
        )
        if (virtual == null) {
            notify("打开失败", "找不到操作记录文件：${dev.codebuddy.account.service.OperationLog.file}", NotificationType.WARNING)
            return
        }
        // openFile(VirtualFile, boolean) 已废弃，改用显式的 OpenFileDescriptor 打开文本编辑器
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, virtual), true)
    }

    private companion object {
        /** CardLayout 的四个视图名 */
        const val VIEW_DASHBOARD = "dashboard"
        const val VIEW_LIST = "list"
        const val VIEW_QUOTA = "quota"
        const val VIEW_EMPTY = "empty"

        /** 切换日志最多保留的行数（对应 `.log-bar` 的一小段历史） */
        const val MAX_LOG_LINES = 4

        /** 逐个重启 CodeBuddy 内核时，相邻窗口之间的间隔（让上一个 Node 进程退出干净） */
        const val RESTART_GAP_MS = 400L
    }
}

/**
 * 底栏额度读数：数值下降时缓动递减，进入视口触发一次，滚出视口不重置。
 *
 * 固定 preferredSize 宽度，避免逐帧改文本时 FlowLayout 抖动。
 */
private class RollingReadoutLabel(text: String) : JLabel(text) {

    private val rolling = RollingReadout(decreaseOnly = true) { rendered ->
        this@RollingReadoutLabel.text = rendered
    }

    private val trigger = ViewportTrigger(this) { rolling.setVisible(it) }

    private var minWidth = 0

    fun setReadout(display: NumberDisplay) {
        val fm = getFontMetrics(font)
        minWidth = maxOf(minWidth, fm.stringWidth(display.text))
        rolling.offer(display)
        trigger.check()
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(maxOf(base.width, minWidth), base.height)
    }
}
