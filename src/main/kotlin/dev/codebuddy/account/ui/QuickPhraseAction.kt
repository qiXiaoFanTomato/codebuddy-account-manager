package dev.codebuddy.account.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.codebuddy.account.pet.QuickPhraseDialog

/**
 * Tools 菜单入口：桌宠关掉、看板没打开时也能管理快捷短语。
 *
 * 与看板卡片、桌宠右键菜单共用 [QuickPhraseDialog]，不各自维护一份短语数据。
 */
class QuickPhraseAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        QuickPhraseDialog.show(e.project?.name)
    }
}
