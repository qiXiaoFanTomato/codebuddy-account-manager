package dev.codebuddy.account.usage

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings

/**
 * 模型积分限额监控（对应 CodeBuddy 官方对特定模型的积分上限，如 DeepSeek 用满 200 积分后不可用）。
 *
 * 数据来源与消耗明细一致：/billing/meter/get-user-request-usage，
 * 按周期（今天 / 本月）拉取全部记录，筛出命中关键字的模型并累计积分。
 *
 * 说明：插件无法拦截 CodeBuddy 客户端的模型调用，
 * 这里只负责「统计 + 提前预警 + 展示剩余额度」，达标后提醒用户切换账号。
 */
object ModelQuotaTracker {

    private val log = logger<ModelQuotaTracker>()

    /** 缓存有效期（周期统计需翻页拉取，默认 10 分钟） */
    private const val STALE_MS = 10 * 60_000L

    /** 单页条数 */
    private const val PAGE_SIZE = UsageService.MAX_PAGE_SIZE

    /** 单账号最多翻页数 */
    private const val MAX_PAGES = 20

    @Volatile
    private var state = ModelQuotaState()

    private val lock = Any()
    private var loading = false
    private val waiters = mutableListOf<() -> Unit>()

    fun state(): ModelQuotaState = state

    /**
     * 确保限额统计数据可用。
     *
     * 缓存有效时不请求接口、也不回调 onLoaded（避免界面重建递归）；
     * 功能关闭时只更新配置快照。
     */
    fun ensureFresh(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        val settings = AccountSettings.getInstance()
        val enabled = settings.modelQuotaEnabled
        val rules = settings.modelQuotaRules
        val period = settings.modelQuotaPeriod
        val (start, end) = period.range()
        val configKey = buildConfigKey(enabled, rules, period)

        synchronized(lock) {
            if (!enabled) {
                state = ModelQuotaState(
                    enabled = false,
                    period = period,
                    startTime = start,
                    endTime = end,
                )
                return
            }
            val fresh = !force && state.loaded && state.configKey == configKey &&
                System.currentTimeMillis() - state.fetchedAt < STALE_MS
            if (fresh) return
            if (onLoaded != null) waiters.add(onLoaded)
            if (loading) return
            loading = true
            state = state.copy(
                enabled = true,
                period = period,
                startTime = start,
                endTime = end,
                loading = true,
                configKey = configKey,
            )
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                load(rules, period, start, end, configKey)
            } catch (t: Throwable) {
                log.warn("模型限额统计失败", t)
                synchronized(lock) {
                    state = state.copy(loading = false, error = t.message ?: t.javaClass.simpleName)
                    loading = false
                }
                notifyWaiters()
            }
        }
    }

    /** 后台重新统计（供定时任务与手动刷新调用；force=false 时尊重缓存） */
    fun refreshAsync(force: Boolean = false) {
        ensureFresh(force)
    }

    /** 配置指纹：规则 + 周期，用于判断缓存是否失效 */
    private fun buildConfigKey(
        enabled: Boolean,
        rules: List<ModelQuotaRule>,
        period: ModelQuotaPeriod,
    ): String {
        val rulePart = rules.joinToString(",") { "${it.keyword.trim()}:${it.limit}" }
        return "$enabled|$period|$rulePart"
    }

    private fun load(
        rules: List<ModelQuotaRule>,
        period: ModelQuotaPeriod,
        start: String,
        end: String,
        configKey: String,
    ) {
        val library = AccountLibrary.getInstance()
        val saved = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        // 只拉一次接口，各条规则复用同一份记录
        val pages = ArrayList<UsagePage>(saved.size)
        var truncated = false
        var firstError: String? = null
        for (s in saved) {
            val page = try {
                library.fetchUsageAll(s.accountKey, start, end, MAX_PAGES)
            } catch (t: Throwable) {
                log.warn("读取周期消耗失败: ${s.accountKey}", t)
                UsagePage(error = t.message ?: t.javaClass.simpleName)
            }
            if (page.error != null) {
                if (firstError == null) firstError = "${s.displayName}：${page.error}"
            } else if (page.truncated) {
                truncated = true
            }
            pages.add(page)
        }

        val ruleStates = rules.map { rule ->
            val perAccount = saved.mapIndexed { index, s ->
                val page = pages[index]
                if (page.error != null) {
                    RuleAccountUsage(
                        accountKey = s.accountKey,
                        accountName = s.displayName,
                        used = 0.0,
                        requests = 0,
                        error = page.error,
                    )
                } else {
                    val matched = page.records.filter { ModelQuotaMatcher.matches(it.model, rule.keyword) }
                    RuleAccountUsage(
                        accountKey = s.accountKey,
                        accountName = s.displayName,
                        used = matched.sumOf { it.credit },
                        requests = matched.size,
                        models = matched.map { it.model }.filter { it.isNotBlank() }.distinct(),
                    )
                }
            }
            ModelQuotaRuleState(
                keyword = rule.keyword.trim(),
                limit = rule.limit,
                used = perAccount.sumOf { it.used },
                requests = perAccount.sumOf { it.requests },
                accounts = perAccount,
            )
        }

        val next = ModelQuotaState(
            enabled = true,
            rules = ruleStates,
            period = period,
            startTime = start,
            endTime = end,
            loading = false,
            loaded = true,
            error = firstError,
            truncated = truncated,
            fetchedAt = System.currentTimeMillis(),
            configKey = configKey,
        )

        synchronized(lock) {
            state = next
            loading = false
        }

        checkAlerts(next)
        notifyWaiters()
        // 广播给界面（看板 / 面板）刷新限额展示
        dev.codebuddy.account.service.AccountEvents.fireQuotaChanged()
    }

    /**
     * 限额提醒（每条规则独立判断）：
     *  - 达标（累计 ≥ 限额）：汇总该规则下超限账号发一条通知，提醒切换账号；
     *  - 接近上限（剩余 ≤ 阈值）：仅对当前激活账号提醒一次。
     * 同一周期内每个「规则 + 提醒点」只发一次，恢复后自动重置。
     */
    private fun checkAlerts(state: ModelQuotaState) {
        val settings = AccountSettings.getInstance()
        if (!settings.modelQuotaEnabled) return

        val active = AccountLibrary.getInstance().currentActive()?.accountKey
        val warnPercent = settings.modelQuotaWarnPercent

        for (rule in state.rules) {
            if (rule.limit <= 0) continue

            val exceedKey = "exceed:${state.period.name}:${rule.keyword}"
            val exceeded = rule.exceededAccounts
            if (exceeded.isNotEmpty()) {
                if (settings.markModelQuotaAlerted(exceedKey)) {
                    val detail = exceeded.joinToString("；") { "${it.accountName} ${formatCredit(it.used)}" }
                    notify(
                        "${rule.keyword} 已达积分限额",
                        "${state.period.label}内 $detail（限额 ${formatCredit(rule.limit)} 积分）。\n" +
                            "CodeBuddy 侧将不再允许使用该模型，建议切换到未超限的账号。",
                        NotificationType.WARNING
                    )
                }
            } else {
                settings.clearModelQuotaAlert(exceedKey)
            }

            for (acc in rule.accounts) {
                if (acc.error != null) continue
                val key = "warn:${state.period.name}:${rule.keyword}:${acc.accountKey}"
                val remain = (rule.limit - acc.used).coerceAtLeast(0.0)
                val near = acc.used > 0 && (remain / rule.limit * 100.0) <= warnPercent
                if (near && acc.accountKey == active) {
                    if (settings.markModelQuotaAlerted(key)) {
                        notify(
                            "${rule.keyword} 积分接近限额",
                            "${acc.accountName} ${state.period.label}已用 ${formatCredit(acc.used)} / " +
                                "${formatCredit(rule.limit)} 积分，剩余 ${formatCredit(remain)}。",
                            NotificationType.INFORMATION
                        )
                    }
                } else {
                    settings.clearModelQuotaAlert(key)
                }
            }
        }
    }

    private fun notifyWaiters() {
        val pending: List<() -> Unit>
        synchronized(lock) {
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { callback ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    callback()
                } catch (t: Throwable) {
                    log.warn("模型限额回调失败", t)
                }
            }, ModalityState.any())
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeBuddy Account Manager")
                .createNotification(title, content, type)
                .notify(null)
        } catch (_: Exception) {
        }
    }
}
