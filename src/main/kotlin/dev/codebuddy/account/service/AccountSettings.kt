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
        /** 配额自动刷新间隔下限（分钟），0 表示关闭 */
        var autoRefreshMinutes: Int = 15,
        /** 配额自动刷新间隔上限（分钟），与下限相等时为固定间隔 */
        var autoRefreshMinutesMax: Int = 15,
        /** 多开 IDE 时仅由主实例执行自动刷新，其余实例复用共享配额结果 */
        var multiInstanceLeaderOnly: Boolean = true,
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
        /** 启用「今日签到」（实验特性，接口非官方，可关闭） */
        var checkinEnabled: Boolean = true,
        /** 执行签到接口路径（以 / 开头） */
        var checkinSubmitPath: String = dev.codebuddy.account.checkin.CheckinService.DEFAULT_SUBMIT_PATH,
        /** 本地签到记录所属日期（yyyy-MM-dd），跨天自动失效 */
        var checkinDoneDate: String = "",
        /** 该日期下已签到的 accountKey 列表 */
        var checkinDoneKeys: MutableList<String> = mutableListOf(),
        /** 启用「成长中心任务」（实验特性，接口非官方，可关闭） */
        var growthEnabled: Boolean = true,
        /** 成长任务列表查询路径（以 / 开头） */
        var growthTasksPath: String = dev.codebuddy.account.growth.GrowthService.DEFAULT_TASKS_PATH,
        /** 成长任务批量接取路径（以 / 开头） */
        var growthAcceptPath: String = dev.codebuddy.account.growth.GrowthService.DEFAULT_ACCEPT_PATH,
        /** 成长任务领奖路径模板（%s 为任务 code） */
        var growthClaimPath: String = dev.codebuddy.account.growth.GrowthService.DEFAULT_CLAIM_PATH,
        /** 企业账号 ID（可选，作为 X-Enterprise-Id 请求头，个人账号留空） */
        var growthEnterpriseId: String = "",
        /** 「一键完成」是否执行客户端行为上报类任务（仅事件上报，不消耗积分） */
        var growthAutoActionsEnabled: Boolean = true,
        /** 启用模型积分限额监控（如 DeepSeek 用满限额后不可用） */
        var modelQuotaEnabled: Boolean = true,
        /** 旧版单规则配置（关键字），仅用于迁移到 [modelQuotaRules] */
        var modelQuotaKeyword: String = "deepseek",
        /** 旧版单规则配置（限额积分），仅用于迁移到 [modelQuotaRules] */
        var modelQuotaLimit: Double = 200.0,
        /** 模型限额规则列表：每个模型一条独立限额 */
        var modelQuotaRules: MutableList<dev.codebuddy.account.usage.ModelQuotaRule> = mutableListOf(),
        /** 统计周期（ModelQuotaPeriod.name：TODAY / MONTH） */
        var modelQuotaPeriod: String = "MONTH",
        /** 剩余百分比低于该值时预警（仅当前账号） */
        var modelQuotaWarnPercent: Int = 20,
        /** 已提醒记录（去重键） */
        var modelQuotaAlertedKeys: MutableList<String> = mutableListOf(),
        /** 看板已隐藏的卡片（DashboardCardId.name），可从「管理卡片」恢复 */
        var dashboardHiddenCards: MutableList<String> = mutableListOf(),
        /** 看板卡片顺序（DashboardCardId.name），缺省按枚举声明顺序 */
        var dashboardCardOrder: MutableList<String> = mutableListOf(),
        /** 看板统一的卡片尺寸档位（DashboardCardSize.name），所有卡片共用 */
        var dashboardCardSize: String = "MEDIUM_2X2",
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // 兼容旧版本缺字段：取值收敛到合法范围
        myState.autoRefreshMinutes = myState.autoRefreshMinutes.coerceIn(0, 240)
        myState.autoRefreshMinutesMax = myState.autoRefreshMinutesMax.coerceIn(myState.autoRefreshMinutes, 240)
        myState.modelQuotaLimit = myState.modelQuotaLimit.coerceIn(0.0, 1_000_000.0)
        myState.modelQuotaWarnPercent = myState.modelQuotaWarnPercent.coerceIn(0, 100)

        // 旧版只有「关键字 + 单一限额」，迁移为第一条规则
        if (myState.modelQuotaRules.isEmpty() && myState.modelQuotaKeyword.isNotBlank()) {
            myState.modelQuotaRules = mutableListOf(
                dev.codebuddy.account.usage.ModelQuotaRule(
                    myState.modelQuotaKeyword.trim(),
                    myState.modelQuotaLimit,
                )
            )
        }
        myState.modelQuotaRules = myState.modelQuotaRules
            .filter { it.keyword.isNotBlank() }
            .map {
                dev.codebuddy.account.usage.ModelQuotaRule(
                    it.keyword.trim(),
                    it.limit.coerceIn(0.0, 1_000_000.0),
                )
            }
            .toMutableList()
    }

    /**
     * 记录一次模型限额提醒；返回 true 表示本次应当提醒（此前未提醒过）。
     */
    fun markModelQuotaAlerted(key: String): Boolean {
        if (key in myState.modelQuotaAlertedKeys) return false
        myState.modelQuotaAlertedKeys.add(key)
        return true
    }

    /** 清除模型限额提醒标记（额度恢复后重置） */
    fun clearModelQuotaAlert(key: String) {
        myState.modelQuotaAlertedKeys.remove(key)
    }

    val autoRefreshMinutes: Int get() = myState.autoRefreshMinutes
    val autoRefreshMinutesMax: Int get() = myState.autoRefreshMinutesMax
    val autoSyncHistoryOnSwitch: Boolean get() = myState.autoSyncHistoryOnSwitch
    val copyMessagesOnSync: Boolean get() = myState.copyMessagesOnSync
    val showStatusBarWidget: Boolean get() = myState.showStatusBarWidget
    val statusBarMode: dev.codebuddy.account.quota.QuotaDisplayMode
        get() = dev.codebuddy.account.quota.QuotaDisplayMode.of(myState.statusBarMode)

    /** 设置状态栏额度维度（面板底部读数的下拉菜单直接改这里） */
    fun setStatusBarMode(mode: dev.codebuddy.account.quota.QuotaDisplayMode) {
        myState.statusBarMode = mode.name
    }
    val statusBarPackage: String get() = myState.statusBarPackage
    val checkTokenOnStartup: Boolean get() = myState.checkTokenOnStartup
    val refreshAllWhenNoSelection: Boolean get() = myState.refreshAllWhenNoSelection
    val hotSwitchEnabled: Boolean get() = myState.hotSwitchEnabled
    val autoSwitchEnabled: Boolean get() = myState.autoSwitchEnabled
    val autoSwitchThresholdPercent: Int get() = myState.autoSwitchThresholdPercent
    val checkinEnabled: Boolean get() = myState.checkinEnabled
    val checkinSubmitPath: String
        get() = myState.checkinSubmitPath
            .ifBlank { dev.codebuddy.account.checkin.CheckinService.DEFAULT_SUBMIT_PATH }
    val growthEnabled: Boolean get() = myState.growthEnabled

    /** 今日是否已签到：本地记录与传入日期不一致时视为未签 */
    fun isCheckinDoneToday(accountKey: String, today: String): Boolean = synchronized(this) {
        myState.checkinDoneDate == today && accountKey in myState.checkinDoneKeys
    }

    /** 记录今日已签到；日期变化时清空旧记录 */
    fun markCheckinDoneToday(accountKey: String, today: String) {
        synchronized(this) {
            if (myState.checkinDoneDate != today) {
                myState.checkinDoneDate = today
                myState.checkinDoneKeys = mutableListOf()
            }
            if (accountKey !in myState.checkinDoneKeys) myState.checkinDoneKeys.add(accountKey)
        }
    }
    val growthTasksPath: String
        get() = myState.growthTasksPath
            .ifBlank { dev.codebuddy.account.growth.GrowthService.DEFAULT_TASKS_PATH }
    val growthAcceptPath: String
        get() = myState.growthAcceptPath
            .ifBlank { dev.codebuddy.account.growth.GrowthService.DEFAULT_ACCEPT_PATH }
    val growthClaimPath: String
        get() = myState.growthClaimPath
            .ifBlank { dev.codebuddy.account.growth.GrowthService.DEFAULT_CLAIM_PATH }
    val growthEnterpriseId: String get() = myState.growthEnterpriseId.trim()
    val growthAutoActionsEnabled: Boolean get() = myState.growthAutoActionsEnabled
    val modelQuotaEnabled: Boolean get() = myState.modelQuotaEnabled
    val modelQuotaKeyword: String get() = myState.modelQuotaKeyword
    val modelQuotaLimit: Double get() = myState.modelQuotaLimit
    val modelQuotaPeriod: dev.codebuddy.account.usage.ModelQuotaPeriod
        get() = dev.codebuddy.account.usage.ModelQuotaPeriod.of(myState.modelQuotaPeriod)
    val modelQuotaWarnPercent: Int get() = myState.modelQuotaWarnPercent

    /** 模型限额规则列表（每个模型一条独立限额） */
    val modelQuotaRules: List<dev.codebuddy.account.usage.ModelQuotaRule>
        get() = myState.modelQuotaRules

    /** 覆盖模型限额规则列表 */
    fun setModelQuotaRules(rules: List<dev.codebuddy.account.usage.ModelQuotaRule>) {
        myState.modelQuotaRules = rules.map { it.copy() }.toMutableList()
    }

    // ---------------------------------------------------------------
    // 看板卡片布局：隐藏 / 顺序 / 尺寸
    // ---------------------------------------------------------------

    /** 已隐藏的看板卡片名集合 */
    val hiddenDashboardCards: Set<String>
        get() = myState.dashboardHiddenCards.toSet()

    /** 显示 / 隐藏某张看板卡片 */
    fun setDashboardCardVisible(cardId: String, visible: Boolean) {
        val hidden = myState.dashboardHiddenCards
        if (visible) hidden.remove(cardId) else if (cardId !in hidden) hidden.add(cardId)
    }

    /** 卡片顺序（卡片名列表，空表示按默认顺序） */
    val dashboardCardOrder: List<String>
        get() = myState.dashboardCardOrder.toList()

    /** 记录拖拽后的卡片顺序 */
    fun setDashboardCardOrder(order: List<String>) {
        myState.dashboardCardOrder = order.toMutableList()
    }

    /** 看板统一的卡片尺寸档位名（DashboardCardSize.name） */
    val dashboardCardSize: String
        get() = myState.dashboardCardSize

    /** 记录统一的卡片尺寸档位 */
    fun setDashboardCardSize(sizeName: String) {
        if (sizeName.isBlank()) return
        myState.dashboardCardSize = sizeName
    }

    /** 清空看板布局覆盖，恢复默认（隐藏的卡片全部回来，顺序与尺寸回默认） */
    fun resetDashboardLayout() {
        myState.dashboardHiddenCards = mutableListOf()
        myState.dashboardCardOrder = mutableListOf()
        myState.dashboardCardSize = "MEDIUM_2X2"
    }

    companion object {
        fun getInstance(): AccountSettings =
            ApplicationManager.getApplication().getService(AccountSettings::class.java)
    }
}
