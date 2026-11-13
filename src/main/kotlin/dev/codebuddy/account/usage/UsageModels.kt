package dev.codebuddy.account.usage

import java.time.LocalDate
import java.util.Locale

/**
 * 一条请求消耗记录。
 *
 * 字段直接对应接口 /billing/meter/get-user-request-usage 的 data.data 数组元素：
 *  requestId / credit / model / client / requestTime / input / inputTrunc / appType / subscribeType
 *
 * 注意：接口没有会话（conversation / session）ID，只能看到「一次请求」，
 * 因此无法把消耗精确归属到某个历史对话。
 */
data class UsageRecord(
    val requestId: String,
    /** 本次请求消耗的积分 */
    val credit: Double,
    /** 模型名，如 deepseek-v4.1-flash */
    val model: String,
    /** 使用端，如 JetBrainsIntelliJIdea */
    val client: String,
    /** 请求时间，如 2026-11-02 14:40:00 */
    val requestTime: String,
    /** 完整输入提示词 */
    val prompt: String,
    /** 截断后的输入提示词 */
    val promptTrunc: String,
    /** 产品标识，如 codebuddy */
    val appType: String,
    /** 订阅类型，如 free */
    val subscribeType: String,
    /** agent 用途（接口字段，通常为空） */
    val agentPurpose: String = "",
) {
    /** 产品展示名 */
    val productLabel: String
        get() = when (appType.lowercase(Locale.ROOT)) {
            "codebuddy" -> "CodeBuddy"
            "workbuddy" -> "WorkBuddy"
            else -> appType.ifBlank { "-" }
        }

    /** 列表中展示的请求文本（优先截断版） */
    val displayPrompt: String get() = promptTrunc.ifBlank { prompt }

    /** 积分展示文本 */
    fun creditText(): String = formatCredit(credit)
}

/** 一次请求消耗查询结果 */
data class UsagePage(
    val records: List<UsageRecord> = emptyList(),
    /** 接口返回的该时间段总条数（可能大于 records.size，表示只拉了最近一页） */
    val total: Int = records.size,
    /** 查询失败时的错误信息；成功为 null */
    val error: String? = null,
) {
    val failed: Boolean get() = error != null

    /** 是否因为单次拉取上限而只拿到部分数据 */
    val truncated: Boolean get() = error == null && total > records.size
}

/** 按模型聚合后的消耗 */
data class ModelUsage(
    val model: String,
    val credit: Double,
    val count: Int,
) {
    fun text(): String = "${model.ifBlank { "-" }} ${formatCredit(credit)}（$count 次）"
}

/** 按天聚合后的用量（用量统计页的折线点） */
data class DailyUsage(
    /** yyyy-MM-dd */
    val date: LocalDate,
    val credit: Double,
    val count: Int,
)

/** 消耗统计（仅基于已加载的记录） */
object UsageStats {

    fun totalCredit(records: List<UsageRecord>): Double = records.sumOf { it.credit }

    /** 按模型聚合，积分降序 */
    fun byModel(records: List<UsageRecord>): List<ModelUsage> =
        records.groupBy { it.model }
            .map { (model, list) -> ModelUsage(model, list.sumOf { it.credit }, list.size) }
            .sortedByDescending { it.credit }

    /**
     * 按天聚合，[from]..[to] 之间每天都有点（没有记录的天补 0），
     * 用于用量统计页的「每日趋势」折线，避免中间缺天把折线拉歪。
     *
     * 请求时间格式为 `yyyy-MM-dd HH:mm:ss`，取前 10 位解析；解析失败的记录跳过。
     */
    fun byDay(records: List<UsageRecord>, from: LocalDate, to: LocalDate): List<DailyUsage> {
        if (to.isBefore(from)) return emptyList()
        val creditByDay = HashMap<LocalDate, Double>()
        val countByDay = HashMap<LocalDate, Int>()
        for (r in records) {
            val day = parseDay(r.requestTime) ?: continue
            if (day.isBefore(from) || day.isAfter(to)) continue
            creditByDay[day] = (creditByDay[day] ?: 0.0) + r.credit
            countByDay[day] = (countByDay[day] ?: 0) + 1
        }
        val days = mutableListOf<DailyUsage>()
        var day = from
        while (!day.isAfter(to)) {
            days.add(DailyUsage(day, creditByDay[day] ?: 0.0, countByDay[day] ?: 0))
            day = day.plusDays(1)
        }
        return days
    }

    private fun parseDay(time: String): LocalDate? {
        if (time.length < 10) return null
        return try {
            LocalDate.parse(time.substring(0, 10))
        } catch (_: Exception) {
            null
        }
    }
}

/** 积分格式化：整数直接展示，小数保留两位并去掉多余的 0 */
fun formatCredit(v: Double): String {
    if (!v.isFinite()) return "-"
    if (v == v.toLong().toDouble()) return v.toLong().toString()
    val s = String.format(Locale.ROOT, "%.2f", v)
    return s.trimEnd('0').trimEnd('.')
}

/** 统计页用的千分位数值：10,020.62（与网页用量统计一致） */
fun formatCreditThousands(v: Double): String =
    if (!v.isFinite()) "-" else String.format(Locale.ROOT, "%,.2f", v)
