package dev.codebuddy.account.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件通用设置。
 *
 * 与 [QuotaAlertSettings] 分开存储：后者是额度告警专用状态（含已提醒 uid 列表）。
 */
@Service(Service.Level.APP)
@State(name = "CodeBuddyAccountSettings", storages = [Storage("codebuddy-account-settings.xml")])
class AccountSettings : PersistentStateComponent<AccountSettings.State> {

    data class State(
        /** 配额自动刷新间隔（分钟），0 表示关闭 */
        var autoRefreshMinutes: Int = 15,
        /** 切换账号时默认勾选「同步历史」 */
        var autoSyncHistoryOnSwitch: Boolean = true,
        /** 同步历史时一并复制缺失的消息目录 */
        var copyMessagesOnSync: Boolean = true,
        /** 在状态栏显示当前账号与剩余额度 */
        var showStatusBarWidget: Boolean = true,
        /** 状态栏额度展示维度（QuotaDisplayMode.name） */
        var statusBarMode: String = "TOTAL_PERCENT",
        /** 「指定套餐」模式下用于匹配分组名的关键字 */
        var statusBarPackage: String = "",
        /** 启动时检查各账号 token 是否过期并提醒 */
        var checkTokenOnStartup: Boolean = true,
        /** 手动刷新时，若无选中卡片则刷新全部账号 */
        var refreshAllWhenNoSelection: Boolean = true,
        /** 切号时尝试热切换（不重启 IDE，依赖 CodeBuddy 插件内部桥接） */
        var hotSwitchEnabled: Boolean = false,
        /** 当前账号额度低于阈值时自动调度到其他账号（需热切换可用） */
        var autoSwitchEnabled: Boolean = false,
        /** 自动调度的触发阈值（剩余百分比） */
        var autoSwitchThresholdPercent: Int = 10,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // 兼容旧版本缺字段：取值收敛到合法范围
        myState.autoRefreshMinutes = myState.autoRefreshMinutes.coerceIn(0, 240)
    }

    val autoRefreshMinutes: Int get() = myState.autoRefreshMinutes
    val autoSyncHistoryOnSwitch: Boolean get() = myState.autoSyncHistoryOnSwitch
    val copyMessagesOnSync: Boolean get() = myState.copyMessagesOnSync
    val showStatusBarWidget: Boolean get() = myState.showStatusBarWidget
    val statusBarMode: dev.codebuddy.account.quota.QuotaDisplayMode
        get() = dev.codebuddy.account.quota.QuotaDisplayMode.of(myState.statusBarMode)
    val statusBarPackage: String get() = myState.statusBarPackage
    val checkTokenOnStartup: Boolean get() = myState.checkTokenOnStartup
    val refreshAllWhenNoSelection: Boolean get() = myState.refreshAllWhenNoSelection
    val hotSwitchEnabled: Boolean get() = myState.hotSwitchEnabled
    val autoSwitchEnabled: Boolean get() = myState.autoSwitchEnabled
    val autoSwitchThresholdPercent: Int get() = myState.autoSwitchThresholdPercent

    companion object {
        fun getInstance(): AccountSettings =
            ApplicationManager.getApplication().getService(AccountSettings::class.java)
    }
}
