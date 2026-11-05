package dev.codebuddy.account.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.codebuddy.account.credential.SecretStorage
import dev.codebuddy.account.hotswap.CodeBuddyBridge
import dev.codebuddy.account.model.CredentialSnapshot
import java.nio.file.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台服务：
 *  1. 按设置间隔刷新所有账号的配额（默认 15 分钟，0 表示关闭）
 *  2. 监听 IDEA 版 CodeBuddy 的凭据文件 secret-storage.xml，
 *     用户通过官方入口登录/切换后自动归档到账号库
 *  3. 启动时检查 token 过期并提醒
 */
@Service(Service.Level.APP)
class AutoRefreshService : Disposable {

    private val log = logger<AutoRefreshService>()

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-account-auto").apply { isDaemon = true }
        }

    private val refreshing = AtomicBoolean(false)
    /** 上次自动调度时间，避免短时间内反复切号 */
    private val lastAutoSwitchAt = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var quotaTask: ScheduledFuture<*>? = null
    @Volatile private var watchService: WatchService? = null
    @Volatile private var watchThread: Thread? = null

    /** 已处理过的内容 hash，避免重复导入 */
    private val lastSeen = mutableMapOf<String, Int>()

    /** 启动：按设置排程配额刷新 + 检查 token + 监听凭据文件 */
    fun startFromSettings() {
        val settings = AccountSettings.getInstance()
        reschedule(settings.autoRefreshMinutes.toLong())
        if (settings.checkTokenOnStartup) checkTokensAsync()
        startWatching()
    }

    /** 按新间隔重排配额刷新任务；[intervalMinutes] <= 0 表示关闭 */
    fun reschedule(intervalMinutes: Long) {
        quotaTask?.cancel(false)
        quotaTask = null
        if (intervalMinutes <= 0) {
            log.info("配额自动刷新已关闭")
            return
        }

        quotaTask = executor.scheduleWithFixedDelay({
            try {
                if (!refreshing.compareAndSet(false, true)) return@scheduleWithFixedDelay
                val library = AccountLibrary.getInstance()
                val saved = library.listSaved()
                if (saved.isEmpty()) return@scheduleWithFixedDelay
                var ok = 0
                var fail = 0
                // 注意：刷新缓存以 accountKey 为 key，不能传裸 uid
                for (s in saved) {
                    val summary = library.refreshQuota(s.accountKey)
                    if (summary == null || summary.error != null) fail++ else ok++
                }
                log.info("自动刷新配额完成：成功 $ok 个，失败 $fail 个")
                AccountEvents.fireQuotaChanged()
                autoSwitchIfNeeded()
                // 模型积分限额统计（如 DeepSeek 200 积分限制），用于达标提醒
                if (AccountSettings.getInstance().modelQuotaEnabled) {
                    dev.codebuddy.account.usage.ModelQuotaTracker.refreshAsync()
                }
            } catch (e: Exception) {
                log.warn("自动刷新配额失败", e)
            } finally {
                refreshing.set(false)
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES)
        log.info("配额自动刷新已排程：每 $intervalMinutes 分钟")
    }

    /** 启动时检查 token 过期情况（后台线程，汇总成一条通知） */
    fun checkTokensAsync() {
        executor.execute {
            try {
                val saved = AccountLibrary.getInstance().listSaved()
                if (saved.isEmpty()) return@execute
                val expired = saved.filter { it.isRefreshTokenExpired || it.isAccessTokenExpired }
                if (expired.isEmpty()) return@execute
                notify(
                    "CodeBuddy 账号 token 异常",
                    "共 ${expired.size} 个账号 token 过期或即将失效：\n" + describe(expired),
                    NotificationType.WARNING
                )
            } catch (e: Exception) {
                log.warn("检查 token 过期失败", e)
            }
        }
    }

    /**
     * 自动调度：当前账号额度低于阈值时，按「快过期 → 额度富余」热切到最优账号。
     * 仅在热切换可用（CodeBuddy 插件桥接成功）时执行，10 分钟内不重复触发。
     */
    private fun autoSwitchIfNeeded() {
        val settings = AccountSettings.getInstance()
        if (!settings.autoSwitchEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastAutoSwitchAt.get() < AUTO_SWITCH_COOLDOWN_MS) return

        val library = AccountLibrary.getInstance()
        val active = library.currentActive() ?: return
        val quotas = library.quotaCache.loadAll()
        val mine = quotas[active.accountKey]?.takeIf { it.error == null } ?: return
        if (mine.usableRemainPercent >= settings.autoSwitchThresholdPercent) return

        val best = QuotaScheduler.rank(library.listSaved(), quotas, exclude = active.accountKey).firstOrNull()
        if (best == null) {
            notify(
                "额度不足且无可切换账号",
                "${active.displayName} 剩余 ${"%.0f".format(mine.usableRemainPercent)}%，" +
                    "其他账号额度未知或已耗尽。",
                NotificationType.WARNING
            )
            return
        }

        if (!CodeBuddyBridge.isAvailable()) {
            notify(
                "额度不足，建议手动切换",
                "${active.displayName} 剩余 ${"%.0f".format(mine.usableRemainPercent)}%。\n" +
                    "推荐 ${best.snapshot.displayName}（${best.reason()}）。\n" +
                    "热切换不可用，重启 IDE 后才会生效，可在面板用「智能调度」手动切换。",
                NotificationType.WARNING
            )
            return
        }

        lastAutoSwitchAt.set(now)
        val result = library.hotSwitchTo(best.snapshot.accountKey)
        if (result.success) {
            notify(
                "已自动调度账号",
                "${active.displayName} 剩余 ${"%.0f".format(mine.usableRemainPercent)}%，" +
                    "已切到 ${best.snapshot.displayName}（${best.reason()}），无需重启 IDE。",
                NotificationType.INFORMATION
            )
        } else {
            notify(
                "自动调度失败",
                (result.message ?: "未知原因") + "\n可在面板用「智能调度」手动切换。",
                NotificationType.WARNING
            )
        }
    }

    private fun describe(list: List<CredentialSnapshot>): String =
        list.take(6).joinToString("\n") { s ->
            val state = if (s.isRefreshTokenExpired) "refresh token 过期，需重新登录"
            else "access token 过期，可能需刷新"
            "· ${s.displayName}（${s.domainHost}）：$state"
        } + if (list.size > 6) "\n… 等 ${list.size} 个" else ""

    private fun notify(title: String, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeBuddy Account Manager")
                .createNotification(title, content, type)
                .notify(null)
        } catch (_: Exception) { }
    }

    private fun startWatching() {
        if (watchService != null) return
        val target = SecretStorage.file() ?: run {
            log.info("secret-storage.xml 不存在，跳过监听")
            return
        }
        val dir = target.parent ?: return
        try {
            val ws = FileSystems.getDefault().newWatchService()
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            watchService = ws

            val t = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    val key = try {
                        ws.take()
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: ClosedWatchServiceException) {
                        break
                    }
                    for (event in key.pollEvents()) {
                        val ctx = event.context() as? Path ?: continue
                        if (ctx.fileName?.toString() != "secret-storage.xml") continue
                        executor.schedule({ tryImport() }, 1500, TimeUnit.MILLISECONDS)
                    }
                    key.reset()
                }
            }, "codebuddy-secret-watcher").apply { isDaemon = true }
            t.start()
            watchThread = t
            log.info("已开始监听凭据文件: $target")
        } catch (e: Exception) {
            log.warn("启动凭据监听失败", e)
        }
    }

    private fun tryImport() {
        try {
            val snapshot = SecretStorage.readCurrent() ?: return
            val hash = snapshot.rawContent.hashCode()
            val key = snapshot.accountKey
            if (lastSeen[key] == hash) return

            val library = AccountLibrary.getInstance()
            val existing = library.listSaved().firstOrNull { it.accountKey == snapshot.accountKey }
            if (existing != null && existing.rawContent == snapshot.rawContent) {
                lastSeen[key] = hash
                return
            }
            if (library.importSnapshot(snapshot)) {
                lastSeen[key] = hash
                log.info("检测到账号变更并已归档: ${snapshot.nickname} (${snapshot.uid})")
            }
        } catch (e: Exception) {
            log.warn("自动导入失败", e)
        }
    }

    override fun dispose() {
        try { quotaTask?.cancel(true) } catch (_: Exception) { }
        try { watchService?.close() } catch (_: Exception) { }
        watchThread?.interrupt()
        executor.shutdownNow()
    }

    /** 智能调度当前状态（看板卡片展示用） */
    data class AutoSwitchState(
        /** 设置里的「自动调度」开关 */
        val enabled: Boolean,
        /** 热切换桥接是否可用（CodeBuddy 插件已安装且类可解析） */
        val hotSwitchAvailable: Boolean,
        val thresholdPercent: Int,
        /** 上次自动调度时间戳，0 表示从未调度过 */
        val lastSwitchAt: Long,
        /** 是否处于调度冷却期 */
        val coolingDown: Boolean,
        val remainingCooldownMs: Long,
    )

    /** 读取智能调度当前状态，供界面展示（纯查询，无副作用） */
    fun autoSwitchState(): AutoSwitchState {
        val s = AccountSettings.getInstance()
        val last = lastAutoSwitchAt.get()
        val remain = AUTO_SWITCH_COOLDOWN_MS - (System.currentTimeMillis() - last)
        return AutoSwitchState(
            enabled = s.autoSwitchEnabled,
            hotSwitchAvailable = CodeBuddyBridge.isAvailable(),
            thresholdPercent = s.autoSwitchThresholdPercent,
            lastSwitchAt = last,
            coolingDown = remain in 1 until AUTO_SWITCH_COOLDOWN_MS,
            remainingCooldownMs = remain.coerceAtLeast(0L),
        )
    }

    companion object {
        private const val AUTO_SWITCH_COOLDOWN_MS = 10 * 60 * 1000L

        fun getInstance(): AutoRefreshService =
            ApplicationManager.getApplication().getService(AutoRefreshService::class.java)
    }
}
