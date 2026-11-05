package dev.codebuddy.account.usage

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 模型积分限额（对应 CodeBuddy 官方对特定模型（如 DeepSeek）的积分上限规则）。
 *
 * 官方规则示例：某模型在周期内累计消耗达到 200 积分后，该模型在客户端不再可用。
 * 插件侧无法拦截 CodeBuddy 客户端的模型调用，因此这里只做「统计 + 预警 + 展示」：
 * 统计周期内命中关键字的模型消耗积分，达到阈值时提醒用户切换账号。
 *
 * 支持多条规则，每个模型一条独立限额（例如 deepseek 200、gpt-5 100）。
 *
 * 周期口径与阈值以官方规则为准，可在设置页调整。
 */
enum class ModelQuotaPeriod(val label: String) {

    /** 自然日 */
    TODAY("今天"),

    /** 自然月 */
    MONTH("本月"),
    ;

    /** 周期起止时间（"yyyy-MM-dd HH:mm:ss"） */
    fun range(now: LocalDateTime = LocalDateTime.now()): Pair<String, String> {
        val date: LocalDate = now.toLocalDate()
        return when (this) {
            TODAY -> "$date 00:00:00" to "$date 23:59:59"
            MONTH -> {
                val first = date.withDayOfMonth(1)
                val last = date.withDayOfMonth(date.lengthOfMonth())
                "$first 00:00:00" to "$last 23:59:59"
            }
        }
    }

    companion object {
        fun of(name: String?): ModelQuotaPeriod =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MONTH
    }
}

/**
 * 一条模型限额规则（可持久化到插件设置）。
 *
 * 关键字按「模型名包含关键字」匹配（忽略大小写），
 * 因此一条规则可同时覆盖同一系列的模型（如 deepseek 覆盖 deepseek-v4.1-flash）。
 */
class ModelQuotaRule {

    /** 模型关键字 */
    var keyword: String = ""

    /** 限额积分 */
    var limit: Double = 0.0

    constructor()

    constructor(keyword: String, limit: Double) {
        this.keyword = keyword
        this.limit = limit
    }

    fun copy(): ModelQuotaRule = ModelQuotaRule(keyword, limit)

    override fun toString(): String = "$keyword=$limit"

    override fun equals(other: Any?): Boolean =
        other is ModelQuotaRule && other.keyword == keyword && other.limit == limit

    override fun hashCode(): Int = 31 * keyword.hashCode() + limit.hashCode()
}

/** 单个账号在某条规则下的消耗情况 */
data class RuleAccountUsage(
    val accountKey: String,
    val accountName: String,
    /** 命中关键字的模型累计消耗积分 */
    val used: Double,
    /** 命中关键字的请求次数 */
    val requests: Int,
    /** 命中的模型名（去重后） */
    val models: List<String> = emptyList(),
    /** 读取失败原因；成功为 null */
    val error: String? = null,
)

/** 单条规则的状态（跨账号汇总） */
data class ModelQuotaRuleState(
    /** 模型关键字 */
    val keyword: String,
    /** 限额积分 */
    val limit: Double,
    /** 全部账号累计消耗 */
    val used: Double = 0.0,
    val requests: Int = 0,
    val accounts: List<RuleAccountUsage> = emptyList(),
) {

    /** 剩余可用积分 */
    val remain: Double get() = (limit - used).coerceAtLeast(0.0)

    /** 是否已达限额 */
    val exceed: Boolean get() = limit > 0 && used >= limit

    /** 剩余百分比（0~100） */
    val remainPercent: Double
        get() = if (limit <= 0) 0.0 else ((limit - used) / limit * 100.0).coerceIn(0.0, 100.0)

    /** 已达限额的账号 */
    val exceededAccounts: List<RuleAccountUsage>
        get() = if (limit <= 0) emptyList()
        else accounts.filter { it.error == null && it.used >= limit }

    /** 命中关键字的模型名汇总 */
    val matchedModels: List<String>
        get() = accounts.flatMap { it.models }.distinct().sorted()

    /** 摘要文案，如「deepseek 本月已用 123.4 / 200 credits」 */
    fun summaryText(periodLabel: String): String =
        "${keyword.ifBlank { "目标模型" }} ${periodLabel}已用 ${formatCredit(used)} / ${formatCredit(limit)} credits"

    /** 状态文案 */
    fun statusText(): String = when {
        limit <= 0 -> "未设置限额"
        exceed -> "已达限额"
        remainPercent <= 20 -> "接近限额"
        else -> "正常"
    }
}

/** 模型限额状态（多条规则汇总） */
data class ModelQuotaState(
    /** 功能是否启用 */
    val enabled: Boolean = false,
    /** 各条规则的状态 */
    val rules: List<ModelQuotaRuleState> = emptyList(),
    val period: ModelQuotaPeriod = ModelQuotaPeriod.MONTH,
    /** 周期起止时间 */
    val startTime: String = "",
    val endTime: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** 首个失败原因；全部成功为 null */
    val error: String? = null,
    /** 是否因单页上限导致数据被截断 */
    val truncated: Boolean = false,
    /** 最近一次统计完成时间 */
    val fetchedAt: Long = 0L,
    /** 配置指纹：规则 / 周期，用于判断缓存是否失效 */
    val configKey: String = "",
) {

    /** 全部规则累计消耗 */
    val totalUsed: Double get() = rules.sumOf { it.used }

    /** 全部规则请求次数 */
    val totalRequests: Int get() = rules.sumOf { it.requests }

    /** 已达限额的规则 */
    val exceededRules: List<ModelQuotaRuleState> get() = rules.filter { it.exceed }

    /** 最紧张的规则（剩余百分比最低） */
    val tightestRule: ModelQuotaRuleState?
        get() = rules.filter { it.limit > 0 }.minByOrNull { it.remainPercent }

    /** 周期描述，如「本月」 */
    val periodLabel: String get() = period.label

    /** 摘要文案 */
    fun summaryText(): String = when {
        !enabled -> "模型限额监控未启用"
        rules.isEmpty() -> "未配置限额规则"
        else -> rules.joinToString("；") { it.summaryText(period.label) }
    }
}

/** 模型名匹配：模型名包含关键字（忽略大小写）即视为命中 */
object ModelQuotaMatcher {

    /** 拆分关键字（支持中英文逗号、分号分隔） */
    fun keywords(raw: String): List<String> =
        raw.split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 模型名是否命中关键字 */
    fun matches(model: String, keyword: String): Boolean {
        if (model.isBlank()) return false
        val list = keywords(keyword)
        if (list.isEmpty()) return false
        return list.any { model.contains(it, ignoreCase = true) }
    }
}
