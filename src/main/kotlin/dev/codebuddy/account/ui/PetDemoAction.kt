package dev.codebuddy.account.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.codebuddy.account.pet.PetService

/**
 * 录制演示用：点一次把桌宠的主要表现按时间线依次演一遍。
 *
 * 只复用已有入口（点击反馈 / 随机动作 / 碎碎念 / 系统提示 / 额度浮字 / 强提醒 / 跑动），
 * 不修改任何用户设置；桌宠窗口未显示时不可用。
 */
class PetDemoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        PetService.getInstance().runDemo()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = PetService.getInstance().status().windowShowing
    }
}
