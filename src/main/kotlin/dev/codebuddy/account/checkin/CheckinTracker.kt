package dev.codebuddy.account.checkin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import java.time.LocalDate

/**
 * 今日签到的跨账号聚合（状态查询 + 一键签到）。
 *
 * 与 [dev.codebuddy.account.usage.UsageTodayTracker] 同构：
 * 单实例状态 + 后台线程执行 + EDT 回调刷新界面，缓存 [STALE_MS] 内不重复打接口。
 *
 * 只对「凭据快照里有 accessToken」的账号操作；接口错误按账号隔离，不影响其余账号。
 */
object CheckinTracker {

    private val log = logger<CheckinTracker>()

    private const val STALE_MS = 5 * 60_000L

    /** 单个账号的签到结果快照 */
    data class AccountCheckin(
        val accountKey: String,
        val displayName: String,
        val status: CheckinService.CheckinStatus? = null,
        val result: CheckinService.CheckinResult? = null,
    ) {
        /** 今日是否已签到：执行结果优先，其次状态查询 */
        val checked: Boolean
            get() = result?.success == true || result?.already == true || status?.checked == true

        val credit: Double?
            get() = result?.credit ?: status?.credit

        val failed: String?
            get() = result?.error ?: status?.error
    }

    data class State(
        val date: LocalDate = LocalDate.now(),
        val items: List<AccountCheckin> = emptyList(),
        val fetchedAt: Long = 0L,
        val loading: Boolean = false,
        val running: Boolean = false,
    ) {
        val loaded: Boolean get() = fetchedAt > 0L
        val checkedCount: Int get() = items.count { it.checked }
        val failedCount: Int get() = items.count { it.failed != null }
        val totalCredit: Double get() = items.mapNotNull { it.credit }.sum()
    }

    @Volatile
    private var state = State()

    private val lock = Any()
    private var loading = false
    private var running = false
    private val waiters = mutableListOf<() -> Unit>()

    fun state(): State = state

    /** 查询各账号今日签到状态；缓存有效时不发请求也不回调 */
    fun ensureFresh(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        val today = LocalDate.now()
        synchronized(lock) {
            val fresh = !force && state.date == today && state.loaded &&
                System.currentTimeMillis() - state.fetchedAt < STALE_MS
            if (fresh) return
            if (onLoaded != null) waiters.add(onLoaded)
            if (loading) return
            loading = true
            state = (if (state.date == today) state else State(date = today)).copy(loading = true)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val items = loadStatuses()
            publish(today, items)
        }
    }

    /**
     * 一键签到：对所有账号执行签到，完成后顺带刷新状态。
     *
     * @param onDone 在 EDT 回调，参数为本次汇总文案
     */
    fun checkinAll(onDone: ((String) -> Unit)? = null) {
        synchronized(lock) {
            if (running) return
            running = true
            state = state.copy(running = true)
        }
        val today = LocalDate.now()

        ApplicationManager.getApplication().executeOnPooledThread {
            val summary = try {
                submitAll(today)
            } catch (t: Throwable) {
                log.warn("一键签到失败", t)
                // 异常也要复位 running，否则界面会一直停在「正在执行签到…」
                finish(today, runCatching { loadStatuses() }.getOrDefault(state.items))
                "签到失败：${t.message ?: t.javaClass.simpleName}"
            }

            onDone?.let { cb ->
                ApplicationManager.getApplication().invokeLater({
                    try {
                        cb(summary)
                    } catch (t: Throwable) {
                        log.warn("签到回调失败", t)
                    }
                }, ModalityState.any())
            }
        }
    }

    /** 对所有账号执行签到并刷新状态，返回汇总文案 */
    private fun submitAll(today: LocalDate): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val saved = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        var success = 0
        var already = 0
        var failed = 0
        var credit = 0.0
        val firstErrors = ArrayList<String>()
        val tokens = library.accessTokenMap()

        for (s in saved) {
            val token = tokens[s.accountKey]
            if (token == null) {
                failed++
                firstErrors.add("${s.displayName}：快照中无 accessToken")
                continue
            }
            val r = CheckinService.submit(
                domain = s.domain,
                accessToken = token,
                uid = s.uid,
                path = settings.checkinSubmitPath,
            )
            when {
                r.error != null -> {
                    failed++
                    firstErrors.add("${s.displayName}：${r.error}")
                }

                r.already -> already++
                else -> {
                    success++
                    credit += r.credit ?: 0.0
                }
            }
        }

        // 签到后再查一次状态，让界面与服务端一致
        finish(today, loadStatuses())

        return buildString {
            append("成功 $success 个")
            if (already > 0) append("，此前已签到 $already 个")
            if (failed > 0) append("，失败 $failed 个")
            if (credit > 0) append("，共获得 ${formatCredit(credit)} credits")
            if (firstErrors.isNotEmpty()) append("（${firstErrors.take(2).joinToString("；")}）")
        }
    }

    /** 结束执行：先复位 running 再发布状态，避免界面停在「正在执行签到…」 */
    private fun finish(today: LocalDate, items: List<AccountCheckin>) {
        synchronized(lock) { running = false }
        publish(today, items)
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    private fun loadStatuses(): List<AccountCheckin> {
        val settings = AccountSettings.getInstance()
        if (!settings.checkinEnabled) return emptyList()

        val library = AccountLibrary.getInstance()
        val saved: List<CredentialSnapshot> = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        val tokens = library.accessTokenMap()
        return saved.map { s ->
            val token = tokens[s.accountKey]
            val status = if (token == null) {
                CheckinService.CheckinStatus(error = "快照中无 accessToken")
            } else {
                CheckinService.queryStatus(s.domain, token, s.uid, settings.checkinStatusPath)
            }
            AccountCheckin(s.accountKey, s.displayName, status)
        }
    }

    private fun publish(today: LocalDate, items: List<AccountCheckin>) {
        val pending: List<() -> Unit>
        synchronized(lock) {
            // running 必须在锁内读取，否则一键签到结束后状态可能仍停在执行中
            state = State(
                date = today,
                items = items,
                fetchedAt = System.currentTimeMillis(),
                loading = false,
                running = running,
            )
            loading = false
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { callback ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    callback()
                } catch (t: Throwable) {
                    log.warn("签到状态回调失败", t)
                }
            }, ModalityState.any())
        }
    }

    private fun formatCredit(v: Double): String =
        if (v >= 1000) "%,.0f".format(v) else "%.2f".format(v)
}
