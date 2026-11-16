package dev.codebuddy.account.ui

import dev.codebuddy.account.model.CredentialSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccountSorterTest {

    private fun snapshot(
        uid: String,
        domain: String = "www.codebuddy.cn",
        nickname: String = uid,
        importedAt: Long = 0L,
        expiresAt: Long = 0L,
    ): CredentialSnapshot = CredentialSnapshot(
        uid = uid,
        nickname = nickname,
        uin = "",
        type = "personal",
        phoneNumber = "",
        domain = domain,
        expiresAt = expiresAt,
        refreshExpiresAt = 0L,
        rawContent = "{}",
        sourcePath = "",
        importedAt = importedAt,
    )

    @Test
    fun activeAccountAlwaysComesFirst() {
        val a = snapshot("a", importedAt = 100)
        val b = snapshot("b", importedAt = 300)
        val c = snapshot("c", importedAt = 200)

        val sorted = AccountSorter.sort(listOf(a, b, c), AccountSortMode.DEFAULT, b.accountKey) { null }

        assertEquals(listOf("b", "c", "a"), sorted.map { it.uid })
    }

    @Test
    fun byQuotaUsesRemainAndPutsUnknownLast() {
        val low = snapshot("low")
        val high = snapshot("high")
        val unknown = snapshot("unknown")
        val remain = mapOf("low@www.codebuddy.cn" to 12.0, "high@www.codebuddy.cn" to 88.0)

        val sorted = AccountSorter.sort(
            listOf(low, high, unknown),
            AccountSortMode.BY_QUOTA,
            null,
        ) { remain[it] }

        assertEquals(listOf("high", "low", "unknown"), sorted.map { it.uid })
    }

    @Test
    fun byDomainGroupsThenNames() {
        val wb = snapshot("wb", domain = "www.workbuddy.cn", nickname = "C")
        val cb2 = snapshot("cb2", nickname = "B")
        val cb1 = snapshot("cb1", nickname = "A")

        val sorted = AccountSorter.sort(listOf(wb, cb2, cb1), AccountSortMode.BY_DOMAIN, null) { null }

        // codebuddy.cn 排在 workbuddy.cn 前，域内按展示名
        assertEquals(listOf("cb1", "cb2", "wb"), sorted.map { it.uid })
    }

    @Test
    fun groupByDomainKeepsInputOrderAndNamesUnknownDomain() {
        val blank = snapshot("blank", domain = "")
        val cb = snapshot("cb")
        val wb = snapshot("wb", domain = "www.workbuddy.cn")

        val groups = AccountSorter.groupByDomain(listOf(blank, cb, wb))

        assertEquals(listOf("未知域", "codebuddy.cn", "workbuddy.cn"), groups.map { it.first })
        assertEquals(listOf("blank"), groups[0].second.map { it.uid })
        assertEquals(listOf("cb"), groups[1].second.map { it.uid })
    }
}
