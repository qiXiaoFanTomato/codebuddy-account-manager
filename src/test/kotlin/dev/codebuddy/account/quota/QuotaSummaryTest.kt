package dev.codebuddy.account.quota

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuotaSummaryTest {

    private fun pack(
        name: String = "包",
        group: String = "",
        size: Double = 100.0,
        used: Double = 0.0,
        expired: Boolean = false,
        expiredTime: String = "",
    ): QuotaPackage = QuotaPackage(
        name = name,
        size = size,
        used = used,
        remain = size - used,
        unit = "credits",
        cycleStart = "2026-11-01 00:00:00",
        cycleEnd = "2026-12-01 00:00:00",
        expired = expired,
        groupName = group,
        expiredTime = expiredTime,
    )

    @Test
    fun usableRemainPercentIgnoresExpiredPackages() {
        val summary = QuotaSummary(
            listOf(
                pack(size = 100.0, used = 100.0, expired = true),
                pack(size = 100.0, used = 25.0),
            )
        )

        assertEquals(75.0, summary.usableRemainPercent, 0.001)
        // 总量口径仍包含过期包：剩余 75 / 总量 200
        assertEquals(37.5, summary.remainPercent, 0.001)
        assertEquals(1, summary.usablePackages.size)
    }

    @Test
    fun emptyPackagesYieldZeroPercent() {
        val summary = QuotaSummary(emptyList())

        assertEquals(0.0, summary.remainPercent, 0.001)
        assertEquals(0.0, summary.usableRemainPercent, 0.001)
        assertEquals("无可用套餐", summary.summaryText())
        assertNull(summary.nextExpiring)
        assertNull(summary.tightest)
    }

    @Test
    fun groupsSortByTotalSizeDescending() {
        val summary = QuotaSummary(
            listOf(
                pack(name = "小包", group = "体验版", size = 10.0),
                pack(name = "大包", group = "赠送包", size = 900.0),
            )
        )

        assertEquals(listOf("赠送包", "体验版"), summary.groups().map { it.name })
        assertEquals(910.0, summary.totalSize, 0.001)
    }

    @Test
    fun nextExpiringSkipsPackagesWithoutRemainOrExpiry() {
        val earliest = pack(size = 100.0, used = 10.0, expiredTime = "2026-11-20 00:00:00")
        val later = pack(size = 100.0, used = 10.0, expiredTime = "2026-12-20 00:00:00")
        val exhausted = pack(size = 100.0, used = 100.0, expiredTime = "2026-11-18 00:00:00")

        val summary = QuotaSummary(listOf(later, exhausted, earliest))

        assertEquals("2026-11-20 00:00:00", summary.nextExpiring?.expiredTime)
    }

    @Test
    fun tightestPicksLowestRemainPercent() {
        val summary = QuotaSummary(
            listOf(
                pack(name = "宽", size = 100.0, used = 10.0),
                pack(name = "紧", size = 100.0, used = 95.0),
            )
        )

        assertEquals("紧", summary.tightest?.name)
    }

    @Test
    fun fmtKeepsIntegersShort() {
        assertEquals("100", QuotaPackage.fmt(100.0))
        assertEquals("12.30", QuotaPackage.fmt(12.3))
    }

    @Test
    fun remainingTextHandlesForeverAndExpired() {
        assertEquals("长期", QuotaTime.remainingText(Long.MAX_VALUE))
        assertEquals("已到期", QuotaTime.remainingText(System.currentTimeMillis() - 1_000L))
    }

    @Test
    fun parsesCycleTimeAndFallsBackOnBadInput() {
        assertNull(QuotaTime.parse(""))
        assertNull(QuotaTime.parse("not a time"))
        // 合法格式按本地时区解析出非零毫秒
        assertEquals(true, (QuotaTime.parse("2026-11-20 08:30:00") ?: 0L) > 0L)
    }
}
