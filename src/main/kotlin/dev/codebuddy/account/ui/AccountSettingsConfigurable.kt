package dev.codebuddy.account.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.MultiInstanceCoordinator
import dev.codebuddy.account.service.QuotaAlertSettings
import dev.codebuddy.account.usage.ModelQuotaPeriod
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/**
 * 设置页：Settings → Tools → CodeBuddy 账号管理器。
 */
class AccountSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null

    private val intervalMinSpinner = JSpinner(SpinnerNumberModel(15, 0, 240, 1))
    private val intervalMaxSpinner = JSpinner(SpinnerNumberModel(15, 0, 240, 1))
    /** 刷新间隔区间：最小值 ~ 最大值（分钟） */
    private val intervalPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        add(intervalMinSpinner)
        add(JBLabel("~"))
        add(intervalMaxSpinner)
    }
    private val alertCheck = JCheckBox("剩余额度低于阈值时提醒")
    private val thresholdSpinner = JSpinner(SpinnerNumberModel(20, 1, 100, 1))
    private val autoSyncCheck = JCheckBox("切换账号时默认同步历史会话索引")
    private val copyMessageCheck = JCheckBox("同步时复制目标账号缺失的消息目录（推荐）")
    private val refreshAllCheck = JCheckBox("「刷新额度」未选中账号时刷新全部账号")
    private val hotSwitchCheck = JCheckBox("切号时尝试热切换（不重启 IDE，实验特性）")
    private val autoSwitchCheck = JCheckBox("当前账号额度低于阈值时自动调度到其他账号")
    private val autoSwitchThresholdSpinner = JSpinner(SpinnerNumberModel(10, 1, 100, 1))
    private val statusBarCheck = JCheckBox("在状态栏显示当前账号与剩余额度（立即生效）")
    private val throughputCheck = JCheckBox("在状态栏显示模型输出速度（tok/s，立即生效）")
    private val tokenCheck = JCheckBox("启动时检查 token 过期并提醒")
    private val multiInstanceCheck = JCheckBox("多开 IDE 时仅由主实例自动刷新（其余实例复用共享配额结果）")
    private val leaderStateLabel = JBLabel()
    private val modeCombo = JComboBox(QuotaDisplayMode.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? QuotaDisplayMode)?.label ?: "", index, isSelected, cellHasFocus
            )
        }
    }
    private val packageField = JTextField(16)
    private val checkinCheck = JCheckBox("启用「今日签到」（实验特性：接口非官方，失效时可关闭）")
    private val checkinSubmitField = JTextField(28)
    private val growthCheck = JCheckBox("启用「成长中心任务」（实验特性：接口非官方，失效时可关闭）")
    private val growthTasksField = JTextField(28)
    private val growthAcceptField = JTextField(28)
    private val growthClaimField = JTextField(28)
    private val growthEnterpriseField = JTextField(16)
    private val growthAutoActionsCheck = JCheckBox("「一键完成」执行客户端行为上报类任务（事件上报，不消耗积分）")
    private val modelQuotaCheck = JCheckBox("监控模型积分限额（达标后提醒切号，如 DeepSeek）")
    private val modelQuotaRulesEditor = ModelQuotaRulesEditor()
    private val modelQuotaPeriodCombo = JComboBox(ModelQuotaPeriod.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? ModelQuotaPeriod)?.label ?: "", index, isSelected, cellHasFocus
            )
        }
    }
    private val modelQuotaWarnSpinner = JSpinner(SpinnerNumberModel(20, 0, 100, 1))
    private val petCheck = JCheckBox("显示桌面宠物（多开 IDE 时全机只保留一个；外观与交互在右键菜单「桌宠设置」）")
    private var usageRefreshTimer: javax.swing.Timer? = null

    /** 界面色调（立即生效，与工具窗右上角的调色盘按钮是同一份状态） */
    private val themeCombo = JComboBox(AccentTheme.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val theme = value as? AccentTheme
                if (theme != null) {
                    icon = ThemeSwatchIcon(
                        com.intellij.ui.JBColor(theme.palette.accentLight, theme.palette.accentDark),
                        com.intellij.ui.JBColor(theme.palette.sheenLight, theme.palette.sheenDark),
                    )
                    text = theme.displayName
                }
                return comp
            }
        }
        selectedItem = AccountUiColors.theme
        addActionListener {
            val theme = selectedItem as? AccentTheme ?: return@addActionListener
            if (theme == AccountUiColors.theme) return@addActionListener
            AccountUiColors.applyTheme(theme)
            AccountUiColors.persistTheme(theme)
            UiThemeEvents.fire(theme)
        }
    }

    override fun getDisplayName(): String = "CodeBuddy 账号管理器"

    /** 默认焦点给复选框，避免方向键误改「立即生效」的界面色调 */
    override fun getPreferredFocusedComponent(): JComponent = statusBarCheck

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addComponent(section("界面与显示") {
                addLabeledComponent("界面色调（立即生效）：", themeCombo)
                addComponent(statusBarCheck)
                addComponent(throughputCheck)
                addLabeledComponent("状态栏额度维度：", modeCombo)
                addLabeledComponent("指定套餐（分组名包含该文字）：", packageField)
                addComponent(petCheck)
            })
            .addComponent(section("额度刷新与提醒") {
                addLabeledComponent("配额自动刷新间隔（分钟，最小 ~ 最大，0 表示关闭）：", intervalPanel)
                addComponent(alertCheck)
                addLabeledComponent("低额度提醒阈值（%）：", thresholdSpinner)
                addComponent(tokenCheck)
                addComponent(multiInstanceCheck)
                addComponent(leaderStateLabel)
            })
            .addComponent(section("切号与智能调度") {
                addComponent(autoSyncCheck)
                addComponent(copyMessageCheck)
                addComponent(refreshAllCheck)
                addComponent(hotSwitchCheck)
                addComponent(autoSwitchCheck)
                addLabeledComponent("自动调度触发阈值（剩余 %）：", autoSwitchThresholdSpinner)
            })
            .addComponent(section("模型积分限额") {
                addComponent(modelQuotaCheck)
                addComponent(JBLabel("模型限额规则（可添加多个模型，每个模型一条独立限额）：").apply {
                    foreground = AccountUiColors.textMuted
                })
                addComponent(JBScrollPane(modelQuotaRulesEditor).apply {
                    border = JBUI.Borders.empty(2, 0)
                    preferredSize = Dimension(JBUI.scale(620), JBUI.scale(170))
                })
                addLabeledComponent("统计周期：", modelQuotaPeriodCombo)
                addLabeledComponent("接近限额预警阈值（剩余 %）：", modelQuotaWarnSpinner)
            })
            .addComponent(section("今日签到（实验特性）") {
                addComponent(checkinCheck)
                addLabeledComponent("执行签到路径：", checkinSubmitField)
            })
            .addComponent(section("成长中心任务（实验特性）") {
                addComponent(growthCheck)
                addLabeledComponent("成长任务列表路径：", growthTasksField)
                addLabeledComponent("成长任务接取路径：", growthAcceptField)
                addLabeledComponent("成长任务领奖路径（%s 为任务 code）：", growthClaimField)
                addLabeledComponent("企业 ID（可选，个人账号留空）：", growthEnterpriseField)
                addComponent(growthAutoActionsCheck)
            })
            .panel

        // 分组后内容较长，交给滚动容器，避免设置对话框把底部选项裁掉
        val scroll = JBScrollPane(panel).apply {
            border = null
            viewportBorder = null
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(480))
        }
        val wrapper = JPanel(BorderLayout())
        wrapper.border = JBUI.Borders.empty(12)
        wrapper.add(scroll, BorderLayout.CENTER)
        mainPanel = wrapper
        reset()
        return wrapper
    }

    /** 生成一节设置：IDE 原生标题分隔线 + 内容整体缩进 */
    private fun section(title: String, body: FormBuilder.() -> Unit): JPanel {
        val content = FormBuilder.createFormBuilder().apply(body).panel
        content.border = BorderFactory.createEmptyBorder(0, JBUI.scale(20), 0, 0)
        return JPanel(BorderLayout()).apply {
            add(TitledSeparator(title), BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    override fun isModified(): Boolean {
        val s = AccountSettings.getInstance()
        val alert = QuotaAlertSettings.getInstance()
        val pet = dev.codebuddy.account.pet.PetSettings.getInstance()
        return intValue(intervalMinSpinner) != s.autoRefreshMinutes ||
            intValue(intervalMaxSpinner) != s.autoRefreshMinutesMax ||
            multiInstanceCheck.isSelected != s.state.multiInstanceLeaderOnly ||
            alertCheck.isSelected != alert.state.enabled ||
            intValue(thresholdSpinner) != alert.state.thresholdPercent ||
            autoSyncCheck.isSelected != s.autoSyncHistoryOnSwitch ||
            copyMessageCheck.isSelected != s.copyMessagesOnSync ||
            refreshAllCheck.isSelected != s.refreshAllWhenNoSelection ||
            hotSwitchCheck.isSelected != s.hotSwitchEnabled ||
            autoSwitchCheck.isSelected != s.autoSwitchEnabled ||
            intValue(autoSwitchThresholdSpinner) != s.autoSwitchThresholdPercent ||
            statusBarCheck.isSelected != s.showStatusBarWidget ||
            throughputCheck.isSelected != s.showStatusBarThroughput ||
            tokenCheck.isSelected != s.checkTokenOnStartup ||
            (modeCombo.selectedItem as? QuotaDisplayMode) != s.statusBarMode ||
            packageField.text.trim() != s.statusBarPackage ||
            checkinCheck.isSelected != s.checkinEnabled ||
            checkinSubmitField.text.trim() != s.checkinSubmitPath ||
            growthCheck.isSelected != s.growthEnabled ||
            growthTasksField.text.trim() != s.growthTasksPath ||
            growthAcceptField.text.trim() != s.growthAcceptPath ||
            growthClaimField.text.trim() != s.growthClaimPath ||
            growthEnterpriseField.text.trim() != s.growthEnterpriseId ||
            growthAutoActionsCheck.isSelected != s.growthAutoActionsEnabled ||
            modelQuotaCheck.isSelected != s.modelQuotaEnabled ||
            modelQuotaRulesEditor.rules() != s.modelQuotaRules ||
            (modelQuotaPeriodCombo.selectedItem as? ModelQuotaPeriod) != s.modelQuotaPeriod ||
            intValue(modelQuotaWarnSpinner) != s.modelQuotaWarnPercent ||
            petCheck.isSelected != pet.enabled
    }

    override fun apply() {
        val s = AccountSettings.getInstance()
        val alert = QuotaAlertSettings.getInstance()

        // 改签到开关 / 接口路径后，旧的状态快照必须立刻失效再按新配置重读
        val checkinChanged = s.checkinEnabled != checkinCheck.isSelected ||
            s.checkinSubmitPath != checkinSubmitField.text.trim()

        val intervalMin = intValue(intervalMinSpinner).coerceIn(0, 240)
        val intervalMax = intValue(intervalMaxSpinner).coerceIn(intervalMin, 240)
        s.state.autoRefreshMinutes = intervalMin
        s.state.autoRefreshMinutesMax = intervalMax
        s.state.multiInstanceLeaderOnly = multiInstanceCheck.isSelected
        s.state.autoSyncHistoryOnSwitch = autoSyncCheck.isSelected
        s.state.copyMessagesOnSync = copyMessageCheck.isSelected
        s.state.refreshAllWhenNoSelection = refreshAllCheck.isSelected
        s.state.hotSwitchEnabled = hotSwitchCheck.isSelected
        s.state.autoSwitchEnabled = autoSwitchCheck.isSelected
        s.state.autoSwitchThresholdPercent = intValue(autoSwitchThresholdSpinner).coerceIn(1, 100)
        s.state.showStatusBarWidget = statusBarCheck.isSelected
        // 关掉速度读数时释放「仅统计」日志监控；桌宠在跑的全量监控不受影响
        val throughputWasOn = s.showStatusBarThroughput
        s.state.showStatusBarThroughput = throughputCheck.isSelected
        if (throughputWasOn && !throughputCheck.isSelected) {
            dev.codebuddy.account.pet.CodeBuddyWatcher.getInstance().stopMetricsOnly()
        }
        s.state.checkTokenOnStartup = tokenCheck.isSelected
        s.state.statusBarMode = ((modeCombo.selectedItem as? QuotaDisplayMode) ?: QuotaDisplayMode.TOTAL_PERCENT).name
        s.state.statusBarPackage = packageField.text.trim()
        s.state.checkinEnabled = checkinCheck.isSelected
        s.state.checkinSubmitPath = checkinSubmitField.text.trim()
        s.state.growthEnabled = growthCheck.isSelected
        s.state.growthTasksPath = growthTasksField.text.trim()
        s.state.growthAcceptPath = growthAcceptField.text.trim()
        s.state.growthClaimPath = growthClaimField.text.trim()
        s.state.growthEnterpriseId = growthEnterpriseField.text.trim()
        s.state.growthAutoActionsEnabled = growthAutoActionsCheck.isSelected
        s.state.modelQuotaEnabled = modelQuotaCheck.isSelected
        s.setModelQuotaRules(modelQuotaRulesEditor.rules())
        s.state.modelQuotaPeriod =
            ((modelQuotaPeriodCombo.selectedItem as? ModelQuotaPeriod) ?: ModelQuotaPeriod.MONTH).name
        s.state.modelQuotaWarnPercent = intValue(modelQuotaWarnSpinner).coerceIn(0, 100)

        // 桌宠总开关：立即生效（关闭时释放单例锁，其他 IDE 实例可接管）
        val pet = dev.codebuddy.account.pet.PetSettings.getInstance()
        pet.enabled = petCheck.isSelected
        dev.codebuddy.account.pet.PetService.getInstance().applySettings()

        alert.setEnabled(alertCheck.isSelected)
        alert.setThreshold(intValue(thresholdSpinner))

        // 区间变化后立即按新值重排任务
        dev.codebuddy.account.service.AutoRefreshService.getInstance()
            .reschedule(intervalMin.toLong(), intervalMax.toLong())

        // 状态栏显隐 / 展示维度立即生效（重新评估 factory.isAvailable 并重建 widget）
        CodeBuddyStatusBarWidget.invalidateCache()
        StatusBarSupport.refreshAll()

        // 模型限额配置变更后立即重新统计
        refreshModelQuota()

        if (checkinChanged) {
            CheckinTracker.invalidate()
            AccountEvents.fireQuotaChanged()
        }
    }

    override fun reset() {
        val s = AccountSettings.getInstance()
        val alert = QuotaAlertSettings.getInstance()
        intervalMinSpinner.value = s.autoRefreshMinutes
        intervalMaxSpinner.value = s.autoRefreshMinutesMax
        multiInstanceCheck.isSelected = s.state.multiInstanceLeaderOnly
        refreshLeaderLabel()
        alertCheck.isSelected = alert.state.enabled
        thresholdSpinner.value = alert.state.thresholdPercent.coerceIn(1, 100)
        autoSyncCheck.isSelected = s.autoSyncHistoryOnSwitch
        copyMessageCheck.isSelected = s.copyMessagesOnSync
        refreshAllCheck.isSelected = s.refreshAllWhenNoSelection
        hotSwitchCheck.isSelected = s.hotSwitchEnabled
        autoSwitchCheck.isSelected = s.autoSwitchEnabled
        autoSwitchThresholdSpinner.value = s.autoSwitchThresholdPercent.coerceIn(1, 100)
        statusBarCheck.isSelected = s.showStatusBarWidget
        throughputCheck.isSelected = s.showStatusBarThroughput
        tokenCheck.isSelected = s.checkTokenOnStartup
        modeCombo.selectedItem = s.statusBarMode
        packageField.text = s.statusBarPackage
        checkinCheck.isSelected = s.checkinEnabled
        checkinSubmitField.text = s.checkinSubmitPath
        growthCheck.isSelected = s.growthEnabled
        growthTasksField.text = s.growthTasksPath
        growthAcceptField.text = s.growthAcceptPath
        growthClaimField.text = s.growthClaimPath
        growthEnterpriseField.text = s.growthEnterpriseId
        growthAutoActionsCheck.isSelected = s.growthAutoActionsEnabled
        modelQuotaCheck.isSelected = s.modelQuotaEnabled
        modelQuotaRulesEditor.setRules(s.modelQuotaRules)
        modelQuotaPeriodCombo.selectedItem = s.modelQuotaPeriod
        modelQuotaWarnSpinner.value = s.modelQuotaWarnPercent.coerceIn(0, 100)

        val pet = dev.codebuddy.account.pet.PetSettings.getInstance()
        petCheck.isSelected = pet.enabled
    }

    /** 只读展示当前实例的主实例角色与接口调用计数 */
    private fun refreshLeaderLabel() {
        val state = MultiInstanceCoordinator.leaderState()
        val role = if (state.isLeader) "主实例" else "从实例（主实例 PID ${state.ownerPid}）"
        leaderStateLabel.text = "当前 IDE 实例：$role，${MultiInstanceCoordinator.instanceTag()}"
        val stats = MultiInstanceCoordinator.requestStats().entries.joinToString("、") { "${it.key}=${it.value}" }
        leaderStateLabel.toolTipText = "本进程接口调用次数：${stats.ifBlank { "暂无" }}"
    }

    /** 设置变更后立即重新统计模型限额 */
    private fun refreshModelQuota() {
        dev.codebuddy.account.usage.ModelQuotaTracker.refreshAsync()
        // 统计完成后刷新规则表格里的「已用 / 剩余 / 状态」只读列
        usageRefreshTimer?.stop()
        var ticks = 0
        usageRefreshTimer = javax.swing.Timer(3000) { e ->
            modelQuotaRulesEditor.refreshUsage()
            ticks++
            if (ticks >= 10) (e.source as javax.swing.Timer).stop()
        }.also {
            it.isRepeats = true
            it.start()
        }
    }

    private fun intValue(spinner: JSpinner): Int = (spinner.value as? Number)?.toInt() ?: 0

    override fun disposeUIResources() {
        usageRefreshTimer?.stop()
        usageRefreshTimer = null
        super.disposeUIResources()
    }
}
