package dev.codebuddy.account.history

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.credential.CredentialPaths

/**
 * 历史会话活动缓存（跨账号）。
 *
 * 首页「今日对话」卡片与日报 / 周报窗口共用一次扫描结果：
 * 只读各账号的 index.json（[HistoryReader] 内部按 mtime 缓存），不读消息正文，
 * 因此扫描开销可控；[STALE_MS] 内复用结果。
 */
object HistoryActivity {

    private val log = logger<HistoryActivity>()

    /** 缓存有效期 */
    private const val STALE_MS = 5 * 60_000L

    data class State(
        val conversations: List<HistoryReader.Conversation> = emptyList(),
        val scannedAt: Long = 0L,
        val loading: Boolean = false,
        val error: String? = null,
    ) {
        val loaded: Boolean get() = scannedAt > 0L
    }

    @Volatile
    private var state = State()

    private val lock = Any()
    private var loading = false
    private val waiters = mutableListOf<() -> Unit>()

    fun state(): State = state

    /** 本地是否已经扫描过（可以直接展示，即使已过期） */
    fun hasData(): Boolean = state.loaded

    /**
     * 确保会话列表可用；缓存有效时不发请求也不回调。
     * 扫描完成（成功或失败）后回调 onLoaded，回调在 EDT 上执行。
     */
    fun ensureFresh(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        synchronized(lock) {
            val fresh = !force && state.loaded &&
                System.currentTimeMillis() - state.scannedAt < STALE_MS
            if (fresh) return
            if (onLoaded != null) waiters.add(onLoaded)
            if (loading) return
            loading = true
            state = state.copy(loading = true, error = null)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            var list: List<HistoryReader.Conversation> = emptyList()
            var error: String? = null
            val dataRoot = CredentialPaths.dataRoot()
            if (dataRoot == null) {
                error = "未找到 CodeBuddyExtension/Data 目录"
            } else {
                try {
                    list = HistoryReader.listAllConversations(dataRoot)
                } catch (t: Throwable) {
                    log.warn("扫描历史会话失败", t)
                    error = t.message ?: t.javaClass.simpleName
                }
            }

            val next = State(
                conversations = list,
                scannedAt = System.currentTimeMillis(),
                loading = false,
                error = error,
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
                        log.warn("历史会话回调失败", t)
                    }
                }, ModalityState.any())
            }
        }
    }
}
