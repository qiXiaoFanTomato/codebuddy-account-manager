package dev.codebuddy.account.usage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.service.AccountLibrary
import java.time.LocalDate

/**
 * 今日消耗聚合（跨账号），供首页看板与消耗明细入口复用。
 *
 * 数据来源仍是接口 /billing/meter/get-user-request-usage（一次拉一天）。
 * 缓存 [STALE_MS] 内不再重复请求，避免首页每次重建都打接口；
 * 后台线程拉取完成后通过 [ensureFresh] 的 onLoaded 回调通知界面重建。
 */
object UsageTodayTracker {

    private val log = logger<UsageTodayTracker>()

    /** 缓存有效期 */
    private const val STALE_MS = 3 * 60_000L

    /** 单个账号一次拉取的记录上限 */
    private const val PAGE_SIZE = UsageService.MAX_PAGE_SIZE

    /** 界面提示里展示的单账号记录上限 */
    const val MAX_ROWS_SHOWN = UsageService.MAX_PAGE_SIZE

    data class State(
        val date: LocalDate = LocalDate.now(),
        /** accountKey -> 该账号今日的消耗结果（失败也保留，用于展示失败原因） */
        val pages: Map<String, UsagePage> = emptyMap(),
        val fetchedAt: Long = 0L,
        val loading: Boolean = false,
        /** 第一个账号的失败原因，成功为 null */
        val error: String? = null,
    ) {
        /** 成功账号的今日记录 */
        val records: List<UsageRecord>
            get() = pages.values.flatMap { page -> if (page.error == null) page.records else emptyList() }

        val totalCredit: Double get() = records.sumOf { it.credit }
        val totalRequests: Int get() = records.size

        /** 成功拿到数据的账号数 */
        val accountCount: Int get() = pages.count { it.value.error == null }

        /** 是否已经查询过（无论成功失败） */
        val loaded: Boolean get() = fetchedAt > 0L

        /** 是否因单次上限被截断 */
        val truncated: Boolean get() = pages.values.any { it.error == null && it.truncated }

        fun byModel(): List<ModelUsage> = UsageStats.byModel(records)

        /** 按请求时间倒序取前 N 条 */
        fun latest(limit: Int): List<UsageRecord> =
            records.sortedByDescending { it.requestTime }.take(limit)

        /** 带账号 key 的最近 N 条（首页卡片要显示账号名） */
        fun latestWithAccount(limit: Int): List<Pair<String, UsageRecord>> =
            pages.entries
                .flatMap { (key, page) ->
                    if (page.error != null) emptyList() else page.records.map { key to it }
                }
                .sortedByDescending { it.second.requestTime }
                .take(limit)
    }

    @Volatile
    private var state = State()

    private val lock = Any()
    private var loading = false
    private val waiters = mutableListOf<() -> Unit>()

    fun state(): State = state

    /**
     * 确保今日数据可用。
     *
     * 缓存仍然有效时不触发任何请求，也**不会**回调 onLoaded，
     * 因此可以安全地在界面 update() 里调用而不会互相递归。
     */
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
            val library = AccountLibrary.getInstance()
            val pages = LinkedHashMap<String, UsagePage>()
            var firstError: String? = null

            val saved = try {
                library.listSaved()
            } catch (t: Throwable) {
                log.warn("读取账号库失败", t)
                emptyList()
            }

            for (s in saved) {
                val page = try {
                    library.fetchUsage(s.accountKey, "$today 00:00:00", "$today 23:59:59", PAGE_SIZE)
                } catch (t: Throwable) {
                    log.warn("读取今日消耗失败: ${s.accountKey}", t)
                    UsagePage(error = t.message ?: t.javaClass.simpleName)
                }
                pages[s.accountKey] = page
                if (page.error != null && firstError == null) {
                    firstError = "${s.displayName}：${page.error}"
                }
            }

            val next = State(
                date = today,
                pages = pages,
                fetchedAt = System.currentTimeMillis(),
                loading = false,
                error = firstError,
            )
            val pending: List<() -> Unit>
            synchronized(lock) {
                state = next
                loading = false
                pending = waiters.toList()
                waiters.clear()
            }
            pending.forEach { callback ->
                ApplicationManager.getApplication().invokeLater({
                    try {
                        callback()
                    } catch (t: Throwable) {
                        log.warn("今日消耗回调失败", t)
                    }
                }, ModalityState.any())
            }
        }
    }
}
