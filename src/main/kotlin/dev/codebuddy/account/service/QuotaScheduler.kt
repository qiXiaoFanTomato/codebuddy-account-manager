package dev.codebuddy.account.service

import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime

/**
 * 账号调度：决定「下一份额度该用哪个」。
 *
 * 排序原则（用户设定）：
 *  1. 账号之间额度共享：所有账号的额度当成一个池子，按「离到期时间最近」优先消耗
 *  2. 到期时间相同再看剩余量：剩余少的优先，避免小额度被遗忘到过期
 *  3. 最后看额度富余：剩余比例高的账号更适合接手后续请求
 *
 * 硬性过滤：token 已过期、额度未知、剩余为 0 的账号不参与。
 */
object QuotaScheduler {

    data class Candidate(
        val snapshot: CredentialSnapshot,
        val quota: QuotaSummary,
    ) {
        /** 该账号下一份该用的额度：仍有效、有剩余、最快到期的那条 */
        val nextPackage: QuotaPackage? get() = quota.nextExpiring

        /** 最近到期的可用细项时间；无到期信息排到最后 */
        val expiringSoonest: Long get() = nextPackage?.expireMillis ?: Long.MAX_VALUE

        /** 推荐理由，用于对话框与通知展示 */
        fun reason(): String {
            val pkg = nextPackage
            val head = if (pkg == null) {
                "无到期限制"
            } else {
                "${shortName(pkg)} ${QuotaTime.remainingText(pkg.expireMillis)}" +
                    "（剩 ${QuotaPackage.fmt(pkg.remain)}）"
            }
            return "$head，账号总剩余 ${QuotaPackage.fmt(quota.usableRemain)} " +
                "（${"%.0f".format(quota.usableRemainPercent)}%）"
        }
    }

    /** 共享额度池里的一份：某个账号下的某条细项 */
    data class Share(
        val snapshot: CredentialSnapshot,
        val pkg: QuotaPackage,
    )

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
                    .thenBy { it.quota.usableRemain }
                    .thenByDescending { it.quota.usableRemainPercent }
            )

    /**
     * 跨账号共享额度池：把每个账号的可用细项摊平成一条使用顺序。
     *
     * 与 [rank] 的区别是粒度：这里排到「某账号的某条额度」，
     * 因此两个账号的同名套餐也能按各自到期时间交错使用，不会因为账号排序而漏掉更急的一份。
     *
     * @param exclude 需要排除的 accountKey；null 表示整个账号库（含当前账号）
     */
    fun pool(
        accounts: List<CredentialSnapshot>,
        quotas: Map<String, QuotaSummary>,
        exclude: String? = null,
    ): List<Share> =
        accounts.asSequence()
            .filter { it.accountKey != exclude && !it.isRefreshTokenExpired }
            .mapNotNull { s ->
                val q = quotas[s.accountKey]?.takeIf { it.error == null } ?: return@mapNotNull null
                s to q
            }
            .flatMap { (s, q) ->
                q.usablePackages.asSequence()
                    .filter { it.remain > 0 }
                    .map { Share(s, it) }
            }
            .sortedWith(
                compareBy<Share> { it.pkg.expireMillis }
                    .thenBy { it.pkg.remain }
                    .thenBy { it.snapshot.displayName }
            )
            .toList()

    /**
     * 把共享池按「同账号同套餐」合并成一段，避免同一额度包的多条细项各占一行。
     *
     * 合并后剩余量相加，到期时间取最早的一条。
     */
    fun mergeShares(shares: List<Share>): List<Share> =
        shares.groupBy { it.snapshot.accountKey to it.pkg.displayGroup }
            .values
            .map { list ->
                val first = list.minByOrNull { it.pkg.expireMillis } ?: list.first()
                val remain = list.sumOf { it.pkg.remain }
                val size = list.sumOf { it.pkg.size }
                val used = size - remain
                Share(
                    first.snapshot,
                    first.pkg.copy(size = size, used = used, remain = remain),
                )
            }
            .sortedBy { it.pkg.expireMillis }

    /** 多行描述，最多 [limit] 个 */
    fun describe(candidates: List<Candidate>, limit: Int = 3): String =
        candidates.take(limit)
            .mapIndexed { i, c -> "${i + 1}. ${c.snapshot.displayName}：${c.reason()}" }
            .joinToString("\n")

    /** 共享额度池的使用顺序，最多 [limit] 条 */
    fun describePool(shares: List<Share>, limit: Int = 5): String =
        shares.take(limit)
            .mapIndexed { i, s ->
                "${i + 1}. ${s.snapshot.displayName} · ${shortName(s.pkg)} " +
                    "${QuotaTime.remainingText(s.pkg.expireMillis)}" +
                    "（剩 ${QuotaPackage.fmt(s.pkg.remain)}）"
            }
            .joinToString("\n")

    private fun shortName(pkg: QuotaPackage): String =
        pkg.displayGroup.removePrefix("CodeBuddy").ifBlank { pkg.displayGroup }
}
