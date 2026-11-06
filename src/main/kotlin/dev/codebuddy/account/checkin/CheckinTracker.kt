package dev.codebuddy.account.checkin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import java.time.LocalDate

/**
 * 今日签到的跨账号聚合。
 *
 * 对齐 workbuddy2api-panel：上游没有「签到状态查询」接口，今日是否已签以本地按日记录
 * （AccountSettings.checkinDoneDate / checkinDoneKeys）为准，跨天自动失效；
 * 一键签到逐账号调用 daily-checkin，成功或「今天已签到」都记当日。
 *
 * 状态快照不再走网络，只有一键签到在后台线程发请求；接口错误按账号隔离。
 */
object CheckinTracker {

    private val log = logger<CheckinTracker>()

    /** 单个账号的今日签到快照 */
    data class AccountCheckin(
        val accountKey: String,
        val displayName: String,
        /** 今日已签（本地记录或本次执行结果） */
        val checked: Boolean = false,
        /** 本次一键签到的执行结果，未执行时为 null */
        val result: CheckinService.CheckinResult? = null,
    ) {
        val failed: String? get() = result?.error
    }

    data class State(
        val date: LocalDate = LocalDate.now(),
        val items: List<AccountCheckin> = emptyList(),
        val fetchedAt: Long = 0L,
        val running: Boolean = false,
    ) {
        val loaded: Boolean get() = fetchedAt > 0L
        val checkedCount: Int get() = items.count { it.checked }
        val failedCount: Int get() = items.count { it.failed != null }
    }

    @Volatile
    private var state = State()

    private val lock = Any()
    private var running = false
    private val waiters = mutableListOf<() -> Unit>()

    fun state(): State = state

    /** 从本地记录构造今日快照，不发网络请求；缓存有效时不重复重建 */
    fun ensureFresh(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        val today = LocalDate.now()
        val fresh = synchronized(lock) {
            val f = !force && state.date == today && state.loaded
            if (!f && onLoaded != null && waiters.size < 8) waiters.add(onLoaded)
            f
        }
        if (fresh) return
        publish(today, localItems(today))
    }

    /** 签到开关 / 接口路径变更后清快照，让下一次刷新立刻按新配置重读 */
    fun invalidate() {
        synchronized(lock) {
            state = state.copy(fetchedAt = 0L)
        }
    }

    /**
     * 一键签到：对所有账号执行签到，完成后重建今日快照。
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
                finish(today, localItems(today))
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

    /** 对所有账号执行签到，返回汇总文案 */
    private fun submitAll(today: LocalDate): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val saved: List<CredentialSnapshot> = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        val tokens = library.accessTokenMap()
        val results = HashMap<String, CheckinService.CheckinResult>()
        var success = 0
        var already = 0
        var failed = 0
        val firstErrors = ArrayList<String>()

        for (s in saved) {
            val token = tokens[s.accountKey]
            val r = if (token == null) {
                CheckinService.CheckinResult(error = "快照中无 accessToken")
            } else {
                CheckinService.submit(
                    domain = s.domain,
                    accessToken = token,
                    uid = s.uid,
                    path = settings.checkinSubmitPath,
                )
            }
            results[s.accountKey] = r
            when {
                r.error != null -> {
                    failed++
                    firstErrors.add("${s.displayName}：${r.error}")
                }

                r.already -> {
                    already++
                    settings.markCheckinDoneToday(s.accountKey, today.toString())
                }

                else -> {
                    success++
                    settings.markCheckinDoneToday(s.accountKey, today.toString())
                }
            }
        }

        finish(today, localItems(today, results))

        return buildString {
            append("成功 $success 个")
            if (already > 0) append("，此前已签到 $already 个")
            if (failed > 0) append("，失败 $failed 个")
            if (firstErrors.isNotEmpty()) append("（${firstErrors.take(2).joinToString("；")}）")
        }
    }

    /** 结束执行：先复位 running 再发布状态，避免界面停在「正在执行签到…」 */
    private fun finish(today: LocalDate, items: List<AccountCheckin>) {
        synchronized(lock) { running = false }
        publish(today, items)
    }

    /** 本地今日签到记录 + 本次执行结果 */
    private fun localItems(
        today: LocalDate,
        results: Map<String, CheckinService.CheckinResult> = emptyMap(),
    ): List<AccountCheckin> {
        val settings = AccountSettings.getInstance()
        if (!settings.checkinEnabled) return emptyList()

        val saved: List<CredentialSnapshot> = try {
            AccountLibrary.getInstance().listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        val day = today.toString()
        return saved.map { s ->
            val r = results[s.accountKey]
            AccountCheckin(
                accountKey = s.accountKey,
                displayName = s.displayName,
                checked = settings.isCheckinDoneToday(s.accountKey, day) ||
                    r?.success == true || r?.already == true,
                result = r,
            )
        }
    }

    private fun publish(today: LocalDate, items: List<AccountCheckin>) {
        val pending: List<() -> Unit>
        synchronized(lock) {
            state = State(
                date = today,
                items = items,
                fetchedAt = System.currentTimeMillis(),
                running = running,
            )
            pending = waiters.toList()
            waiters.clear()
        }
        dispatch(pending)
    }

    private fun dispatch(callbacks: List<() -> Unit>) {
        callbacks.forEach { callback ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    callback()
                } catch (t: Throwable) {
                    log.warn("签到状态回调失败", t)
                }
            }, ModalityState.any())
        }
    }
}
