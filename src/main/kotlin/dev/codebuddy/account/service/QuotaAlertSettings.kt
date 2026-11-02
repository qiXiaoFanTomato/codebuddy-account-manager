package dev.codebuddy.account.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 配额告警设置。
 */
@Service(Service.Level.APP)
@State(name = "CodeBuddyAccountQuotaAlert", storages = [Storage("codebuddy-account-alert.xml")])
class QuotaAlertSettings : PersistentStateComponent<QuotaAlertSettings.State> {

    data class State(
        var enabled: Boolean = true,
        /** 剩余百分比低于该值时告警 */
        var thresholdPercent: Int = 20,
        /** 每个账号同一周期内只提醒一次，记录已提醒的 uid */
        var alertedUids: MutableList<String> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun setEnabled(enabled: Boolean) { myState.enabled = enabled }

    fun setThreshold(percent: Int) { myState.thresholdPercent = percent.coerceIn(1, 100) }

    /** 返回 true 表示需要提醒（且标记为已提醒） */
    fun shouldAlert(uid: String, remainPercent: Double): Boolean {
        if (!myState.enabled) return false
        if (remainPercent >= myState.thresholdPercent) return false
        if (uid in myState.alertedUids) return false
        myState.alertedUids.add(uid)
        return true
    }

    /** 配额恢复后清除提醒标记 */
    fun clearAlert(uid: String) {
        myState.alertedUids.remove(uid)
    }

    companion object {
        fun getInstance(): QuotaAlertSettings =
            ApplicationManager.getApplication().getService(QuotaAlertSettings::class.java)
    }
}
