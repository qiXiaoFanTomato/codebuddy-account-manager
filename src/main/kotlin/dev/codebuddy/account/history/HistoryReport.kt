package dev.codebuddy.account.history

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 日报 / 周报的统计维度。
 *
 * 维度全部来自本地真实数据：
 *  - 项目：会话所属 workspace（history/<workspaceHash>），名称由 [ProjectResolver] 推断
 *  - 账号：会话所属账号（Data/<uid>）
 *  - 日期 / 时段：会话最后一条消息时间
 *  - 会话类型：index.json 的 type（如 craft），缺失时记为「未标注」
 */
enum class ReportDimension(val label: String) {
    PROJECT("按项目"),
    ACCOUNT("按账号"),
    DAY("按日期"),
    HOUR("按时段"),
    TYPE("按会话类型"),
    ;

    companion object {
        /** 单日报告不需要「按日期」维度（只有一天） */
        fun forRange(days: Int): List<ReportDimension> =
            if (days <= 1) entries.filter { it != DAY } else entries
    }
}

/** 报告明细行：一条历史会话 */
data class ReportEntry(
    val id: String,
    val name: String,
    val account: String,
    val type: String,
    val lastAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val messages: Int,
    /** 所属项目名（展示用） */
    val project: String = "未标注项目",
    /** 所属项目目录（未解析出时为 null） */
    val projectPath: String? = null,
) {
    /** 统计锚点：优先最后消息时间，退回创建时间 */
    val anchor: LocalDateTime? get() = lastAt ?: createdAt
}

/** 一个项目的汇总（日报 / 周报按项目分组用） */
data class ProjectGroup(
    val project: String,
    val path: String?,
    val entries: List<ReportEntry>,
) {
    val conversations: Int get() = entries.size
    val messages: Int get() = entries.sumOf { it.messages }
    val lastAt: LocalDateTime? get() = entries.mapNotNull { it.anchor }.maxOrNull()
}

/** 维度聚合结果 */
data class ReportBucket(
    val label: String,
    val conversations: Int,
    val messages: Int,
) {
    fun detail(): String = "$conversations 个会话 · $messages 条消息"
}

/** 一次日报 / 周报 */
data class HistoryReport(
    val title: String,
    val rangeLabel: String,
    val entries: List<ReportEntry>,
) {
    val conversationCount: Int get() = entries.size
    val totalMessages: Int get() = entries.sumOf { it.messages }
    val accountCount: Int get() = entries.map { it.account }.distinct().size
    val activeDays: Int get() = entries.mapNotNull { it.anchor?.toLocalDate() }.distinct().size

    /** 涉及的活跃项目（按会话数降序） */
    val projectGroups: List<ProjectGroup> get() = entries
        .groupBy { it.project }
        .map { (name, rows) -> ProjectGroup(name, rows.firstNotNullOfOrNull { it.projectPath }, rows) }
        .sortedWith(compareByDescending<ProjectGroup> { it.conversations }.thenByDescending { it.messages })

    val projectCount: Int get() = entries.map { it.project }.distinct().size

    /** 按维度聚合（日期 / 时段按时间升序，其余按会话数降序） */
    fun buckets(dimension: ReportDimension): List<ReportBucket> {
        val list = entries.groupBy { keyOf(it, dimension) }
            .map { (label, rows) -> ReportBucket(label, rows.size, rows.sumOf { it.messages }) }
        return when (dimension) {
            ReportDimension.DAY, ReportDimension.HOUR -> list.sortedBy { it.label }
            else -> list.sortedWith(
                compareByDescending<ReportBucket> { it.conversations }.thenByDescending { it.messages }
            )
        }
    }

    private fun keyOf(e: ReportEntry, dimension: ReportDimension): String = when (dimension) {
        ReportDimension.PROJECT -> e.project
        ReportDimension.ACCOUNT -> e.account
        ReportDimension.DAY -> e.anchor?.format(DAY_FMT) ?: "未知"
        ReportDimension.HOUR -> e.anchor?.let { String.format(Locale.ROOT, "%02d 时", it.hour) } ?: "未知"
        ReportDimension.TYPE -> e.type.ifBlank { "未标注" }
    }

    /** 某条会话在指定维度下的分组键（供界面按维度条目筛选明细） */
    fun groupKeyOf(e: ReportEntry, dimension: ReportDimension): String = keyOf(e, dimension)

    /** 导出用的 Markdown 文本（按维度分布 + 按项目分组 + 会话明细） */
    fun toMarkdown(dimension: ReportDimension): String = buildString {
        append("# $title\n\n")
        append("- 区间：$rangeLabel\n")
        append("- 会话：$conversationCount 个\n")
        append("- 消息：$totalMessages 条\n")
        append("- 活跃项目：$projectCount 个\n")
        append("- 活跃账号：$accountCount 个\n")
        append("- 活跃天数：$activeDays 天\n\n")
        append("## ${dimension.label}分布\n\n")
        for (b in buckets(dimension)) append("- ${b.label}：${b.detail()}\n")
        appendProjectSections(this@HistoryReport)
        if (entries.isNotEmpty()) {
            append("\n## 会话明细\n\n")
            for (e in entries.take(100)) {
                append("- ${e.name.ifBlank { "(未命名)" }}（${e.project} / ${e.account}）")
                append("，${e.messages} 条消息")
                e.anchor?.let { append("，最近 ${it.format(MINUTE_FMT)}") }
                append("\n")
            }
        }
    }

    private fun StringBuilder.appendProjectSections(r: HistoryReport) {
        val groups = r.projectGroups
        if (groups.isEmpty()) return
        append("\n## 按项目\n\n")
        for (g in groups) {
            append("### ${g.project}")
            append("（${g.conversations} 个会话 · ${g.messages} 条消息")
            g.lastAt?.let { append(" · 最近 ${it.format(MINUTE_FMT)}") }
            append("）\n")
            g.path?.let { append("目录：$it\n") }
            for (e in g.entries.take(12)) {
                append("- ${e.name.ifBlank { "(未命名)" }}")
                append("（${e.account}，${e.messages} 条消息")
                e.anchor?.let { append("，${it.format(MINUTE_FMT)}") }
                append("）\n")
            }
            append("\n")
        }
    }

    companion object {
        val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
        val MINUTE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}

/**
 * 报告构建器。
 *
 * 会逐个统计会话的消息文件数量，属于磁盘 IO，必须在后台线程调用；
 * 结果按「会话 id + 最后消息时间 + 消息目录」缓存，切换维度时无需重新统计。
 */
object HistoryReportBuilder {

    private val messageCountCache = ConcurrentHashMap<String, Int>()

    fun build(
        conversations: List<HistoryReader.Conversation>,
        accountLabels: Map<String, String>,
        start: LocalDate,
        end: LocalDate,
        title: String,
    ): HistoryReport {
        val zone = ZoneId.systemDefault()
        val entries = ArrayList<ReportEntry>()
        // workspace hash -> 项目名，按需读消息反推（内部有缓存）
        val projects = ProjectResolver.resolve(conversations)

        for (conv in conversations) {
            val last = parse(conv.lastMessageAt, zone)
            val created = parse(conv.createdAt, zone)
            val anchor = last ?: created ?: continue
            val day = anchor.toLocalDate()
            if (day.isBefore(start) || day.isAfter(end)) continue

            val project = projects[conv.workspace]
            entries.add(
                ReportEntry(
                    id = conv.id,
                    name = conv.name,
                    account = accountLabels[conv.sourceUid] ?: conv.sourceUid.take(8),
                    type = conv.type,
                    lastAt = last,
                    createdAt = created,
                    messages = messageCount(conv),
                    project = project?.name ?: "未标注项目",
                    projectPath = project?.path,
                )
            )
        }

        entries.sortByDescending { it.anchor?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L }
        return HistoryReport(title, rangeLabel(start, end), entries)
    }

    fun clearCache() = messageCountCache.clear()

    private fun messageCount(conv: HistoryReader.Conversation): Int {
        val key = conv.id + "@" + conv.lastMessageAt + "@" + (conv.messageDir?.toString() ?: "-")
        return messageCountCache.getOrPut(key) { HistoryReader.countMessages(conv) }
    }

    private fun rangeLabel(start: LocalDate, end: LocalDate): String =
        if (start == end) start.format(HistoryReport.DAY_FMT)
        else "${start.format(HistoryReport.DAY_FMT)} ~ ${end.format(HistoryReport.DAY_FMT)}"

    private fun parse(text: String, zone: ZoneId): LocalDateTime? {
        if (text.isBlank()) return null
        return try {
            Instant.parse(text).atZone(zone).toLocalDateTime()
        } catch (_: Exception) {
            null
        }
    }
}
