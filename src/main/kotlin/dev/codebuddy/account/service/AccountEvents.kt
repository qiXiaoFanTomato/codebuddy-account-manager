package dev.codebuddy.account.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.Topic

/**
 * 账号数据变更事件（application 级广播）。
 *
 * IDEA 每个项目窗口各有自己的工具窗口面板实例，账号库是 application service。
 * 一个窗口里导入 / 切号 / 刷新额度后，其他窗口必须收到通知才会刷新，
 * 否则会出现「三个项目显示状态不同步」。
 */
interface AccountEvents {

    /** 账号库或当前凭据发生变化（导入、删除、别名、切号、回滚、登出） */
    fun accountsChanged()

    /** 配额数据更新（手动 / 自动刷新） */
    fun quotaChanged()

    companion object {

        val TOPIC: Topic<AccountEvents> =
            Topic.create("CodeBuddy account data changed", AccountEvents::class.java)

        /** 账号库变化广播 */
        fun fireAccountsChanged() {
            try {
                ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).accountsChanged()
            } catch (_: Throwable) {
                // 广播失败不影响主流程
            }
        }

        /** 配额变化广播 */
        fun fireQuotaChanged() {
            try {
                ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).quotaChanged()
            } catch (_: Throwable) {
            }
        }
    }
}
