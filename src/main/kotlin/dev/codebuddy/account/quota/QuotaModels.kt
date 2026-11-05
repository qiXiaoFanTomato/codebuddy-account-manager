package dev.codebuddy.account.quota

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 单条配额记录（接口 Accounts 数组里的一项 = 一个可用细项） */
data class QuotaPackage(
    /** 展示名，优先 SubProductName，退回 PackageName */
    val name: String,
    /** 总量 */
    val size: Double,
    /** 已用 */
    val used: Double,
    /** 剩余 */
    val remain: Double,
    /** 单位，如 credits */
    val unit: String,
    /** 周期开始，如 "2026-09-30 09:06:13" */
    val cycleStart: String,
    /** 周期结束 */
    val cycleEnd: String,
    /** 是否已过期（由 ExpiredTime / CycleEndTime 与当前时间比较得出） */
    val expired: Boolean,
    /** 套餐分组名（接口 PackageName），如「CodeBuddy个人体验版」 */
    val groupName: String = "",
    /** 接口 SubProductName，如「腾讯云代码助手 (IDE) - 赠送包」 */
    val subProductName: String = "",
    /** 接口 ExpiredTime，可能为空 */
    val expiredTime: String = "",
    /** 发放来源（AccountAttributes.grantSource），如 daily_checkin */
    val grantSource: String = "",
) {
    /** 展示用的分组名；缺失时回退到自身名称 */
    val displayGroup: String
        get() = groupName.ifBlank { name.ifBlank { "其他" } }

    /** 是否在周期内可用 */
    val usable: Boolean get() = !expired

    /** 单条剩余百分比 */
    val remainPercent: Double
        get() = if (size <= 0) 0.0 else (remain / size * 100.0).coerceIn(0.0, 100.0)

    /** 周期结束时间（毫秒），解析失败返回 Long.MAX_VALUE */
    val cycleEndMillis: Long get() = QuotaTime.parse(cycleEnd) ?: Long.MAX_VALUE

    /** 到期时间（毫秒），优先 ExpiredTime */
    val expireMillis: Long
        get() = QuotaTime.parse(expiredTime) ?: QuotaTime.parse(cycleEnd) ?: Long.MAX_VALUE

    /** "已用 / 总量"，如 "0 / 100" */
    fun usageText(): String = "${fmt(used)} / ${fmt(size)}"

    companion object {
        fun fmt(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format("%.2f", v)
    }
}

/** 分组摘要的一段：短名 + 剩余 + 总量（供状态栏按数值分段渲染） */
data class QuotaDetailPart(val name: String, val remain: Double, val size: Double)

/** 一个套餐分组（同一 PackageName 下的所有细项） */
data class QuotaGroup(
    val name: String,
    val packages: List<QuotaPackage>,
) {
    val size: Double get() = packages.sumOf { it.size }
    val used: Double get() = packages.sumOf { it.used }
    val remain: Double get() = packages.sumOf { it.remain }
    val usableCount: Int get() = packages.count { it.usable }
    val unit: String get() = packages.firstOrNull()?.unit ?: ""

    val remainPercent: Double
        get() = if (size <= 0) 0.0 else (remain / size * 100.0).coerceIn(0.0, 100.0)

    fun usageText(): String = "${QuotaPackage.fmt(used)} / ${QuotaPackage.fmt(size)}"
}

/** 一次配额查询的汇总结果 */
data class QuotaSummary(
    val packages: List<QuotaPackage>,
    val fetchedAt: Long = System.currentTimeMillis(),
    /** 查询失败时的错误信息；成功为 null */
    val error: String? = null,
) {
    val totalSize: Double get() = packages.sumOf { it.size }
    val totalUsed: Double get() = packages.sumOf { it.used }
    val totalRemain: Double get() = packages.sumOf { it.remain }

    /** 仅在有效期内的细项（过期包不计入可用额度） */
    val usablePackages: List<QuotaPackage> get() = packages.filter { it.usable }
    val usableSize: Double get() = usablePackages.sumOf { it.size }
    val usableUsed: Double get() = usablePackages.sumOf { it.used }
    val usableRemain: Double get() = usablePackages.sumOf { it.remain }

    /** 剩余百分比（0~100，含已过期包，用于展示总量） */
    val remainPercent: Double
        get() = if (totalSize <= 0) 0.0 else (totalRemain / totalSize * 100.0).coerceIn(0.0, 100.0)

    /** 剩余百分比（仅未过期包，用于告警与选号） */
    val usableRemainPercent: Double
        get() = if (usableSize <= 0) 0.0 else (usableRemain / usableSize * 100.0).coerceIn(0.0, 100.0)

    val unit: String get() = packages.firstOrNull()?.unit ?: ""

    /** 按套餐分组（保持接口返回顺序） */
    fun groups(onlyUsable: Boolean = false): List<QuotaGroup> {
        val source = if (onlyUsable) usablePackages else packages
        return source.groupBy { it.displayGroup }
            .map { (name, list) -> QuotaGroup(name, list) }
            .sortedByDescending { it.size }
    }

    /** 最快到期、且仍有剩余的细项 */
    val nextExpiring: QuotaPackage?
        get() = usablePackages
            .filter { it.remain > 0 && it.expireMillis != Long.MAX_VALUE }
            .minByOrNull { it.expireMillis }

    /** 剩余最少的未过期细项（用于状态栏「最紧张」维度） */
    val tightest: QuotaPackage?
        get() = usablePackages.filter { it.size > 0 }.minByOrNull { it.remainPercent }

    /** 按关键字匹配分组（用于状态栏指定套餐） */
    fun groupByKeyword(keyword: String): QuotaGroup? {
        if (keyword.isBlank()) return null
        return groups().firstOrNull { it.name.contains(keyword, ignoreCase = true) }
    }

    /** 一句人话摘要，如 "剩余 96.7 / 100 credits" */
    fun summaryText(): String {
        if (error != null) return "查询失败"
        if (packages.isEmpty()) return "无可用套餐"
        return "剩余 ${QuotaPackage.fmt(totalRemain)} / ${QuotaPackage.fmt(totalSize)} $unit"
    }

    /** 各套餐摘要的分段数据，供状态栏按数值分段渲染（与 [detailText] 同源） */
    fun detailParts(limit: Int = 2): List<QuotaDetailPart> {
        if (error != null || packages.isEmpty()) return emptyList()
        return groups().take(limit).map { QuotaDetailPart(shortGroupName(it.name), it.remain, it.size) }
    }

    /** 细分摘要，如 "体验版 0/500 · 赠送包 3496/4019" */
    fun detailText(limit: Int = 2): String {
        if (error != null || packages.isEmpty()) return summaryText()
        val parts = detailParts(limit)
        val more = if (groups().size > limit) " …" else ""
        return parts.joinToString(" · ") {
            "${it.name} ${QuotaPackage.fmt(it.remain)}/${QuotaPackage.fmt(it.size)}"
        } + more
    }

    private fun shortGroupName(name: String): String =
        name.removePrefix("CodeBuddy").ifBlank { name }.take(6)

    private fun fmt(v: Double): String = QuotaPackage.fmt(v)
}

/** 状态栏额度展示维度 */
enum class QuotaDisplayMode(val label: String) {
    /** 总剩余百分比（默认） */
    TOTAL_PERCENT("总剩余百分比"),

    /** 剩余 / 总量 */
    REMAIN("剩余 / 总量"),

    /** 各套餐摘要（只显示前两个分组） */
    GROUP_DETAIL("各套餐摘要"),

    /** 剩余比例最低的套餐 */
    LOWEST_PACKAGE("最紧张套餐"),

    /** 指定套餐（按关键字匹配分组名） */
    PACKAGE("指定套餐"),
    ;

    companion object {
        fun of(name: String?): QuotaDisplayMode =
            entries.firstOrNull { it.name == name } ?: TOTAL_PERCENT
    }
}

/** 接口时间字符串解析（"yyyy-MM-dd HH:mm:ss"，本地时区） */
object QuotaTime {

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(text: String): Long? {
        if (text.isBlank()) return null
        return try {
            LocalDateTime.parse(text.trim(), FMT)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** 剩余时间的可读描述，如 "3 天后到期" */
    fun remainingText(millis: Long): String {
        if (millis == Long.MAX_VALUE) return "长期"
        val diff = millis - System.currentTimeMillis()
        if (diff <= 0) return "已到期"
        val days = diff / 86_400_000L
        return when {
            days >= 1 -> "$days 天后到期"
            else -> "${(diff / 3_600_000L).coerceAtLeast(1)} 小时后到期"
        }
    }
}
