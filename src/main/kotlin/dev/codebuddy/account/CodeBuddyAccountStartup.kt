package dev.codebuddy.account

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AutoRefreshService

/**
 * 项目打开时启动后台服务。
 */
class CodeBuddyAccountStartup : ProjectActivity {

    private val log = logger<CodeBuddyAccountStartup>()

    override suspend fun execute(project: Project) {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return

        try {
            // 0. 检查上次切换是否被 IDEA 覆盖
            checkPendingSwitch()

            // 首次启动：把已有账号静默导入一次
            val library = AccountLibrary.getInstance()
            if (library.listSaved().isEmpty()) {
                val n = library.importFromAuthDir()
                log.info("首次启动，自动导入 $n 个账号")
            }

            // 启动自动刷新 + token 检查 + 目录监听（幂等）
            app.getService(AutoRefreshService::class.java).startFromSettings()
        } catch (e: Exception) {
            log.warn("CodeBuddy 账号服务启动失败", e)
        }
    }

    /**
     * 如果存在未生效的切换，检查并补写。
     *
     * 场景：用户切换账号后直接重启 IDE，IDEA 退出时用内存旧状态
     * 覆盖了 secret-storage.xml。这里在启动时发现 uid 不符，补写一次，
     * 并提示用户再重启一次。
     */
    private fun checkPendingSwitch() {
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
                notify("登出已补做", "上次强制登出被 IDE 覆盖，已重新清除。请再重启一次 IDE。")
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
            notify("账号切换已补写", "上次切换到 ${pending.nickname} 被 IDE 覆盖，已重新写入。请再重启一次 IDE 生效。")
            log.info("已补写待生效切换: ${pending.uid}")
        } else {
            log.warn("补写待生效切换失败: ${result.exceptionOrNull()?.message}")
        }
    }

    private fun notify(title: String, content: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeBuddy Account Manager")
            .createNotification(title, content, com.intellij.notification.NotificationType.WARNING)
            .notify(null)
    }
}
