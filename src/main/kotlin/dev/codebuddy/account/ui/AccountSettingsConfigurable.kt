package dev.codebuddy.account.ui

import com.intellij.openapi.options.Configurable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.QuotaAlertSettings
import java.awt.BorderLayout
import java.awt.Component
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

    private val intervalSpinner = JSpinner(SpinnerNumberModel(15, 0, 240, 1))
    private val alertCheck = JCheckBox("剩余额度低于阈值时提醒")
    private val thresholdSpinner = JSpinner(SpinnerNumberModel(20, 1, 100, 1))
    private val autoSyncCheck = JCheckBox("切换账号时默认同步历史会话索引")
    private val copyMessageCheck = JCheckBox("同步时复制目标账号缺失的消息目录（推荐）")
    private val refreshAllCheck = JCheckBox("「刷新额度」未选中账号时刷新全部账号")
    private val hotSwitchCheck = JCheckBox("切号时尝试热切换（不重启 IDE，实验特性）")
    private val autoSwitchCheck = JCheckBox("当前账号额度低于阈值时自动调度到其他账号")
    private val autoSwitchThresholdSpinner = JSpinner(SpinnerNumberModel(10, 1, 100, 1))
    private val statusBarCheck = JCheckBox("在状态栏显示当前账号与剩余额度（立即生效）")
    private val tokenCheck = JCheckBox("启动时检查 token 过期并提醒")
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

    override fun getDisplayName(): String = "CodeBuddy 账号管理器"

    override fun getPreferredFocusedComponent(): JComponent = intervalSpinner

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("配额自动刷新间隔（分钟，0 表示关闭）：", intervalSpinner)
            .addVerticalGap(JBUI.scale(6))
            .addComponent(alertCheck)
            .addLabeledComponent("低额度提醒阈值（%）：", thresholdSpinner)
            .addVerticalGap(JBUI.scale(6))
            .addComponent(autoSyncCheck)
            .addComponent(copyMessageCheck)
            .addComponent(refreshAllCheck)
            .addVerticalGap(JBUI.scale(6))
            .addComponent(hotSwitchCheck)
            .addComponent(autoSwitchCheck)
            .addLabeledComponent("自动调度触发阈值（剩余 %）：", autoSwitchThresholdSpinner)
            .addVerticalGap(JBUI.scale(6))
            .addComponent(statusBarCheck)
            .addLabeledComponent("状态栏额度维度：", modeCombo)
            .addLabeledComponent("指定套餐（分组名包含该文字）：", packageField)
            .addComponent(tokenCheck)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val wrapper = JPanel(BorderLayout())
        wrapper.border = JBUI.Borders.empty(12)
        wrapper.add(panel, BorderLayout.NORTH)
        mainPanel = wrapper
        reset()
        return wrapper
    }

    override fun isModified(): Boolean {
        val s = AccountSettings.getInstance()
        val alert = QuotaAlertSettings.getInstance()
        return intValue(intervalSpinner) != s.autoRefreshMinutes ||
            alertCheck.isSelected != alert.state.enabled ||
            intValue(thresholdSpinner) != alert.state.thresholdPercent ||
            autoSyncCheck.isSelected != s.autoSyncHistoryOnSwitch ||
            copyMessageCheck.isSelected != s.copyMessagesOnSync ||
            refreshAllCheck.isSelected != s.refreshAllWhenNoSelection ||
            hotSwitchCheck.isSelected != s.hotSwitchEnabled ||
            autoSwitchCheck.isSelected != s.autoSwitchEnabled ||
            intValue(autoSwitchThresholdSpinner) != s.autoSwitchThresholdPercent ||
            statusBarCheck.isSelected != s.showStatusBarWidget ||
            tokenCheck.isSelected != s.checkTokenOnStartup ||
            (modeCombo.selectedItem as? QuotaDisplayMode) != s.statusBarMode ||
            packageField.text.trim() != s.statusBarPackage
    }

    override fun apply() {
        val s = AccountSettings.getInstance()
        val alert = QuotaAlertSettings.getInstance()

        val interval = intValue(intervalSpinner)
        s.state.autoRefreshMinutes = interval
        s.state.autoSyncHistoryOnSwitch = autoSyncCheck.isSelected
        s.state.copyMessagesOnSync = copyMessageCheck.isSelected
        s.state.refreshAllWhenNoSelection = refreshAllCheck.isSelected
        s.state.hotSwitchEnabled = hotSwitchCheck.isSelected
        s.state.autoSwitchEnabled = autoSwitchCheck.isSelected
        s.state.autoSwitchThresholdPercent = intValue(autoSwitchThresholdSpinner).coerceIn(1, 100)
        s.state.showStatusBarWidget = statusBarCheck.isSelected
        s.state.checkTokenOnStartup = tokenCheck.isSelected
        s.state.statusBarMode = ((modeCombo.selectedItem as? QuotaDisplayMode) ?: QuotaDisplayMode.TOTAL_PERCENT).name
        s.state.statusBarPackage = packageField.text.trim()

        alert.setEnabled(alertCheck.isSelected)
        alert.setThreshold(intValue(thresholdSpinner))

        // 间隔变化后立即按新值重排任务
        dev.codebuddy.account.service.AutoRefreshService.getInstance().reschedule(interval.toLong())

        // 状态栏显隐 / 展示维度立即生效（重新评估 factory.isAvailable 并重建 widget）
        CodeBuddyStatusBarWidget.invalidateCache()
        StatusBarSupport.refreshAll()
    }

    override fun reset() {
        val s = AccountSettings.getInstance()
        val alert = QuotaAlertSettings.getInstance()
        intervalSpinner.value = s.autoRefreshMinutes
        alertCheck.isSelected = alert.state.enabled
        thresholdSpinner.value = alert.state.thresholdPercent.coerceIn(1, 100)
        autoSyncCheck.isSelected = s.autoSyncHistoryOnSwitch
        copyMessageCheck.isSelected = s.copyMessagesOnSync
        refreshAllCheck.isSelected = s.refreshAllWhenNoSelection
        hotSwitchCheck.isSelected = s.hotSwitchEnabled
        autoSwitchCheck.isSelected = s.autoSwitchEnabled
        autoSwitchThresholdSpinner.value = s.autoSwitchThresholdPercent.coerceIn(1, 100)
        statusBarCheck.isSelected = s.showStatusBarWidget
        tokenCheck.isSelected = s.checkTokenOnStartup
        modeCombo.selectedItem = s.statusBarMode
        packageField.text = s.statusBarPackage
    }

    private fun intValue(spinner: JSpinner): Int = (spinner.value as? Number)?.toInt() ?: 0
}
