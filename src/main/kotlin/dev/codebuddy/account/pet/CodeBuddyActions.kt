package dev.codebuddy.account.pet

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.codebuddy.account.hotswap.CodeBuddyBridge

/**
 * 触发 CodeBuddy 自己的动作（只动 CodeBuddy，不重启 IDE）。
 *
 * CodeBuddy 有一个「重置插件状态」（`reset_plugin_status`）动作，反编译确认它的实现是
 * `NodeProjectService.reset()`：`setAllowAutoRestart(false)` → 释放旧 `NodeRunner` →
 * 新建并拉起 Node 侧进程 → `WebviewBrowser.resetWebview()`。
 * 也就是 CodeBuddy 在 IDE 里"自己重启"了一下，IDE 进程和别的插件都不受影响。
 */
object CodeBuddyActions {

    private val log = logger<CodeBuddyActions>()

    /** 与 CodeBuddy `plugin.xml` 里注册的 action id 完全一致 */
    private const val RESET_PLUGIN_STATUS = "com.tencent.code.intel.action.ResetPluginStatusAction"

    /**
     * 重置 CodeBuddy 状态：Node 服务重启 + 聊天面板重载，**不含任何重启 IDE 的路径**。
     *
     * 先走平台公开入口 [CodeBuddyBridge.restartAgent]（`ActionUtil.invokeAction` + 完整 DataContext）：
     * 实测这条能正常执行；再退到 `createFromAnAction + update`，
     * 后者在 CodeBuddy 4.12.43685444 上会被 update 判成 disabled 而静默失败，只作后备。
     *
     * 返回 false 表示两种方式都没能把动作跑起来（id 变了 / 插件没装 / 动作不可用），
     * 调用方只做提示，不再退回重启 IDE。
     */
    fun resetNodeService(project: Project): Boolean {
        if (project.isDisposed) return false
        if (CodeBuddyBridge.restartAgent(project)) return true
        val action = ActionManager.getInstance().getAction(RESET_PLUGIN_STATUS) ?: return false
        return try {
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, ProjectContext(project))
            // 动作自己按「project 是否 disposed」决定可用性，先 update 再执行，避免拿一个 disabled 的动作硬跑
            action.update(event)
            if (!event.presentation.isEnabled) return false
            action.actionPerformed(event)
            true
        } catch (e: Throwable) {
            log.warn("触发 CodeBuddy「重置插件状态」失败", e)
            false
        }
    }

    /** 只提供 PROJECT 的最小 DataContext：动作的 update 只读这一个键 */
    private class ProjectContext(private val project: Project) : DataContext {
        override fun getData(dataId: String): Any? =
            if (CommonDataKeys.PROJECT.name == dataId) project else null
    }
}
