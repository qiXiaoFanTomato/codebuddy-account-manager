package dev.codebuddy.account.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Tools 菜单入口：打开账号管理面板。
 */
class OpenPanelAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CodeBuddy Accounts") ?: return
        toolWindow.show {
            toolWindow.activate(null)
        }
    }
}
