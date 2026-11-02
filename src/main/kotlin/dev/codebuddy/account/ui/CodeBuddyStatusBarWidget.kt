package dev.codebuddy.account.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import dev.codebuddy.account.quota.QuotaDisplayMode
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import java.awt.Component
import java.awt.event.MouseEvent

/** 状态栏工厂 */
class CodeBuddyStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = CodeBuddyStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = "CodeBuddy 账号"

    override fun isAvailable(project: Project): Boolean =
        AccountSettings.getInstance().showStatusBarWidget

    override fun createWidget(project: Project): StatusBarWidget =
        CodeBuddyStatusBarWidget(project)
}

/**
 * 状态栏展示当前激活账号与剩余额度。
 *
 * 数据读取做了 3 秒节流缓存：状态栏会频繁重绘，
 * 避免每次绘制都去读凭据文件与配额缓存。
 */
class CodeBuddyStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {}

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        // 兜底：若动态移除 widget 失败（内部 API 变动），至少让内容为空
        if (!AccountSettings.getInstance().showStatusBarWidget) return ""

        val snapshot = snapshot() ?: return "CodeBuddy: 未登录"
        val quota = quotaOf(snapshot.accountKey)
        if (quota == null || quota.error != null) return "CodeBuddy: ${snapshot.displayName}"

        val settings = AccountSettings.getInstance()
        val body = when (settings.statusBarMode) {
            QuotaDisplayMode.TOTAL_PERCENT ->
                "${"%.0f".format(quota.usableRemainPercent)}%"

            QuotaDisplayMode.REMAIN ->
                "${QuotaPackage.fmt(quota.usableRemain)}/${QuotaPackage.fmt(quota.usableSize)}"

            QuotaDisplayMode.GROUP_DETAIL ->
                quota.detailText(2)

            QuotaDisplayMode.LOWEST_PACKAGE ->
                quota.tightest?.let { "${it.displayGroup.take(8)} ${"%.0f".format(it.remainPercent)}%" }
                    ?: quota.summaryText()

            QuotaDisplayMode.PACKAGE -> {
                val group = quota.groupByKeyword(settings.statusBarPackage)
                if (group == null) "未匹配到「${settings.statusBarPackage}」"
                else "${group.name.take(10)} ${QuotaPackage.fmt(group.remain)}/${QuotaPackage.fmt(group.size)}"
            }
        }
        return "CodeBuddy: ${snapshot.displayName} · $body"
    }

    override fun getTooltipText(): String {
        val snapshot = snapshot() ?: return "CodeBuddy 未登录，点击打开账号管理器"
        val sb = StringBuilder()
        sb.append("${snapshot.displayName}（${snapshot.domainHost}）\n")
        sb.append(
            "Token：${
                when {
                    snapshot.isRefreshTokenExpired -> "已过期"
                    snapshot.isAccessTokenExpired -> "access token 已过期"
                    else -> "有效"
                }
            }\n"
        )
        val quota = quotaOf(snapshot.accountKey)
        if (quota != null && quota.error == null) {
            sb.append("额度：${quota.summaryText()}（${"%.0f".format(quota.usableRemainPercent)}%）\n")
            sb.append("细项：${quota.packages.size} 条，其中仍有效 ${quota.usablePackages.size} 条\n")

            // 分组明细（总的 + 细的）
            for (group in quota.groups().take(4)) {
                sb.append("· ${group.name}：${group.usageText()}（${group.packages.size} 条）\n")
            }

            quota.nextExpiring?.let {
                sb.append("最近到期：${it.displayGroup}（${QuotaTime.remainingText(it.expireMillis)}）\n")
            }
        }
        sb.append("点击打开账号管理器")
        return "<html>${sb.toString().trimEnd().replace("\n", "<br/>")}</html>"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer {
        ToolWindowManager.getInstance(project)
            .getToolWindow("CodeBuddy Accounts")
            ?.show()
    }

    // ---------------------------------------------------------------
    // 数据（3 秒节流）
    // ---------------------------------------------------------------

    private fun snapshot(): dev.codebuddy.account.model.CredentialSnapshot? {
        refreshIfStale()
        return cachedSnapshot
    }

    private fun quotaOf(accountKey: String): dev.codebuddy.account.quota.QuotaSummary? {
        refreshIfStale()
        return cachedQuotas[accountKey]
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastLoadedAt < CACHE_TTL_MS) return
        synchronized(LOCK) {
            if (System.currentTimeMillis() - lastLoadedAt < CACHE_TTL_MS) return
            try {
                cachedSnapshot = AccountLibrary.getInstance().currentActive()
                cachedQuotas = AccountLibrary.getInstance().quotaCache.loadAll()
            } catch (_: Exception) {
                // 读取失败保持上一次的缓存
            } finally {
                lastLoadedAt = System.currentTimeMillis()
            }
        }
    }

    companion object {
        const val WIDGET_ID = "CodeBuddyAccountWidget"

        private const val CACHE_TTL_MS = 3000L
        private val LOCK = Any()

        @Volatile private var cachedSnapshot: dev.codebuddy.account.model.CredentialSnapshot? = null
        @Volatile private var cachedQuotas: Map<String, dev.codebuddy.account.quota.QuotaSummary> = emptyMap()
        @Volatile private var lastLoadedAt: Long = 0L

        /** 清空缓存，使下一次读取立即反映最新凭据 / 配额 */
        fun invalidateCache() {
            lastLoadedAt = 0L
        }
    }
}
