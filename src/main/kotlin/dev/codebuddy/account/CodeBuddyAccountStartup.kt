package dev.codebuddy.account

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AutoRefreshService
import dev.codebuddy.account.ui.tokens.AccountUiColors



/**
 * 项目打开时启动后台服务。
 *
 * 使用 `StartupActivity` 而不是 `ProjectActivity`：前者自 2021.1 起就存在，
 * 后者是 2023.1 才引入的接口，用了它插件就无法装到 2022.3 ~ 2023.1 之间的 IDE。
 *
 * 启动阶段只有主题恢复留在 EDT（保证首帧就是持久化的色调），
 * 读索引 / 首次导入 / 校验待生效切换这些文件 IO 全部挪到后台线程，
 * 起定时服务与创建桌宠窗口再回 EDT。
 */
class CodeBuddyAccountStartup : StartupActivity.DumbAware {

    private val log = logger<CodeBuddyAccountStartup>()

    override fun runActivity(project: Project) {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return

        // 0.1 恢复上次选择的界面色调（只读一个小配置文件，需要赶在首帧之前）
        try {
            AccountUiColors.loadPersistedTheme()
        } catch (e: Exception) {
            log.warn("恢复界面色调失败", e)
        }

        app.executeOnPooledThread {
            val notices = mutableListOf<Pair<String, String>>()
            try {
                // 0. 检查上次切换是否被 IDEA 覆盖
                collectPendingSwitchNotices(notices)

                // 首次启动：把已有账号静默导入一次
                val library = AccountLibrary.getInstance()
                if (library.listSaved().isEmpty()) {
                    val n = library.importFromAuthDir()
                    log.info("首次启动，自动导入 $n 个账号")
                }
            } catch (e: Exception) {
                log.warn("CodeBuddy 账号服务启动失败", e)
            }

            // 定时任务与 Swing 窗口必须在 EDT 上装配，提示也回到 EDT 再发
            app.invokeLater {
                try {
                    app.getService(AutoRefreshService::class.java).startFromSettings()
                    // 桌宠（档案柜精灵）：多开 IDE 时由跨进程锁保证全机只有一个
                    dev.codebuddy.account.pet.PetService.getInstance().startFromSettings()
                } catch (e: Exception) {
                    log.warn("启动后台服务失败", e)
                }
                notices.forEach { (title, content) -> notify(title, content) }
            }
        }
    }

    /**
     * 如果存在未生效的切换，检查并补写；需要提示的内容收进 [notices]，
     * 由调用方回到 EDT 统一弹出（后台线程不直接操作 UI）。
     *
     * 场景：用户切换账号后直接重启 IDE，IDEA 退出时用内存旧状态
     * 覆盖了 secret-storage.xml。这里在启动时发现 uid 不符，补写一次，
     * 并提示用户再重启一次。
     */
    private fun collectPendingSwitchNotices(notices: MutableList<Pair<String, String>>) {
        val pending = dev.codebuddy.account.service.PendingSwitch.load() ?: return
        val current = dev.codebuddy.account.credential.SecretStorage.readCurrent()

        if (pending.action == dev.codebuddy.account.service.PendingSwitch.ACTION_CLEAR) {
            // 上次执行的是强制登出
            if (current == null) {
                // 已生效，清理标记
                dev.codebuddy.account.service.PendingSwitch.clear()
                return
            }
            // 被 IDE 覆盖回来了，再清一次
            val r = dev.codebuddy.account.credential.SecretStorage.clearCredentials()
            if (r.isSuccess) {
                dev.codebuddy.account.service.PendingSwitch.clear()
                notices += "登出已补做" to "上次强制登出被 IDE 覆盖，已重新清除。请再重启一次 IDE。"
                log.info("已补做强制登出")
            } else {
                log.warn("补做强制登出失败: ${r.exceptionOrNull()?.message}")
            }
            return
        }

        // 切换操作
        if (current?.uid == pending.uid) {
            dev.codebuddy.account.service.PendingSwitch.clear()
            return
        }

        val result = dev.codebuddy.account.credential.SecretStorage.writeCurrent(pending.json)
        if (result.isSuccess) {
            dev.codebuddy.account.service.PendingSwitch.clear()
            notices += "账号切换已补写" to
                "上次切换到 ${pending.nickname} 被 IDE 覆盖，已重新写入。请再重启一次 IDE 生效。"
            log.info("已补写待生效切换: ${pending.uid}")
        } else {
            log.warn("补写待生效切换失败: ${result.exceptionOrNull()?.message}")
        }
    }

    /** 统一提示出口：优先由桌宠承载，不可用时退回 IDE 气泡通知 */
    private fun notify(title: String, content: String) {
        dev.codebuddy.account.pet.PetNotifier.warn(title, content, null)
    }
}
