package dev.codebuddy.account.service

import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime

/**
 * 账号调度：决定「下一个该用哪个账号」。
 *
 * 排序原则（用户设定）：
 *  1. 先消耗快过期的额度：仍有剩余、且最近到期时间最早的账号优先
 *  2. 再看额度富余：剩余量大、剩余比例高的优先
 *
 * 硬性过滤：token 已过期、额度未知、剩余为 0 的账号不参与。
 */
object QuotaScheduler {

    data class Candidate(
        val snapshot: CredentialSnapshot,
        val quota: QuotaSummary,
    ) {
        /** 最近到期的可用细项时间；无到期信息排到最后 */
        val expiringSoonest: Long
            get() = quota.nextExpiring?.expireMillis ?: Long.MAX_VALUE

        /** 推荐理由，用于对话框展示 */
        fun reason(): String {
            val expire = quota.nextExpiring
            val expiryText = if (expire == null) "无到期限制"
            else "${shortName(expire)} ${QuotaTime.remainingText(expire.expireMillis)}"
            return "$expiryText，剩余 ${QuotaPackage.fmt(quota.usableRemain)} " +
                "（${"%.0f".format(quota.usableRemainPercent)}%）"
        }

        private fun shortName(pkg: QuotaPackage): String =
            pkg.displayGroup.removePrefix("CodeBuddy").ifBlank { pkg.displayGroup }
    }

    /**
     * 排序候选账号。
     *
     * @param exclude 需要排除的 accountKey（通常是当前账号）
     * @param quotas  accountKey -> 配额
     */
    fun rank(
        accounts: List<CredentialSnapshot>,
        quotas: Map<String, QuotaSummary>,
        exclude: String? = null,
    ): List<Candidate> =
        accounts
            .filter { it.accountKey != exclude && !it.isRefreshTokenExpired }
            .mapNotNull { s ->
                val q = quotas[s.accountKey]?.takeIf { it.error == null } ?: return@mapNotNull null
                if (q.usableRemain <= 0) return@mapNotNull null
                Candidate(s, q)
            }
            .sortedWith(
                compareBy<Candidate> { it.expiringSoonest }
                    .thenByDescending { it.quota.usableRemain }
                    .thenByDescending { it.quota.usableRemainPercent }
            )

    /** 多行描述，最多 [limit] 个 */
    fun describe(candidates: List<Candidate>, limit: Int = 3): String =
        candidates.take(limit)
            .mapIndexed { i, c -> "${i + 1}. ${c.snapshot.displayName}：${c.reason()}" }
            .joinToString("\n")
}
