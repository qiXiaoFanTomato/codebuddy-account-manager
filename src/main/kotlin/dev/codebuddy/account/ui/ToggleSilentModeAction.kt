package dev.codebuddy.account.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import dev.codebuddy.account.pet.PetSettings
import dev.codebuddy.account.pet.SilentMode

/**
 * 静默运行的一键开关（Tools 菜单 / 可在 Keymap 里绑快捷键）。
 *
 * 存在的理由：静默期间所有确认都不再弹窗，主人必须有一条**不用翻设置页**的退出路径。
 * 开启方向仍然要二次确认——快捷键误触的代价是「全放行」，不能只靠一次点击。
 */
class ToggleSilentModeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val settings = PetSettings.getInstance()
        if (SilentMode.isActive(settings)) {
            SilentMode.disable(settings, "已通过菜单 / 快捷键关闭")
            return
        }
        val minutes = SilentMode.DEFAULT_MINUTES
        val yes = Messages.showYesNoDialog(
            "<html>开启后所有确认都会直接放行，<b>不再弹桌宠气泡</b>。<br><br>" +
                "放行范围：普通命令、删除、Git 破坏、系统与磁盘、提权、脚本执行、对外分发。<br>" +
                "凭证与扣费仍要你亲手点（要放开请到「桌宠设置 → 静默运行」勾选红线）。<br>" +
                "持续时间 $minutes 分钟；到点自动关闭，重启 IDE 也会关闭。<br>" +
                "PowerShell 递归删除 / 脚本执行 / node -e 仍会被宿主拦一次，桌宠代不了。</html>",
            "确认开启静默运行",
            "确认开启",
            "取消",
            Messages.getWarningIcon(),
        )
        if (yes != Messages.YES) return
        SilentMode.enable(settings, minutes, allowRedline = false)
    }

    override fun update(e: AnActionEvent) {
        val active = try {
            SilentMode.isActive(PetSettings.getInstance())
        } catch (_: Throwable) {
            false
        }
        e.presentation.text = if (active) "关闭静默运行" else "开启静默运行"
        e.presentation.description = if (active) {
            "立刻恢复逐条确认"
        } else {
            "所有确认直接放行（有到期时间，重启 IDE 自动失效）"
        }
    }
}
