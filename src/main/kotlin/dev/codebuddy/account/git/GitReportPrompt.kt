package dev.codebuddy.account.git

import java.time.LocalDate

/**
 * 「用 CodeBuddy 生成」的提示词。
 *
 * 只下发区间与 git 命令，提交记录与本人身份都由 CodeBuddy 从 git log 输出里自行判定，
 * 不把大量提交与变更文件塞进剪贴板，避免粘贴内容被截断。
 */
object GitReportPrompt {

    /** 提示词里最多列出的其它项目目录数 */
    private const val MAX_DIRS = 8

    /**
     * [currentDir] 是 CodeBuddy 会话所在的当前项目，命令在该目录直接执行，不写进提示词；
     * [dirs] 里与当前项目不同的目录才列出完整命令，无需替换任何占位符。
     */
    fun build(
        kind: String,
        start: LocalDate,
        end: LocalDate,
        currentDir: String?,
        dirs: List<String>,
    ): String = buildString {
        val current = normalize(currentDir)
        val extras = dirs.map { normalize(it) }
            .filter { it.isNotBlank() && !it.equals(current, ignoreCase = true) }
            .distinct()
        val shown = extras.take(MAX_DIRS)
        val logArgs = "-c core.quotepath=false log --no-merges --encoding=UTF-8 --date=short " +
            "--since=${start}T00:00:00 --until=${end}T23:59:59 --name-status"

        append("请根据本项目的 git 提交记录生成一份$kind，区间 $start ~ $end。\n\n")
        append("请在当前项目根目录执行以下命令，只以输出为事实依据（终端不在该目录时先切换过去）：\n")
        append("   git $logArgs\n")
        if (shown.isNotEmpty()) {
            append("\n其它项目目录（各执行一次）：\n")
            shown.forEach { append("   git -C \"$it\" $logArgs\n") }
            if (extras.size > MAX_DIRS) append("   …其余 ${extras.size - MAX_DIRS} 个目录未列出\n")
        }
        append("\n口径：\n")
        append("- 只统计区间内已 commit 的记录，未提交的工作区改动不计入；--no-merges 已排除合并提交；\n")
        append("- git log 默认格式中 Author 行给出作者名与邮箱、Date 行给出日期，缩进段落是提交说明，其后文件行首字母为 A / M / D / R 状态；\n")
        append("- 输出按时间倒序，第一条即最近一条提交；本人 = 该提交的 Author，与之相同的 Author 都是本人提交，其余为其他人提交；\n")
        append("- 若最近一条提交的 Author 在区间内只出现一次，而另一 Author 的提交明显更多，则以提交数最多的 Author 为本人。\n\n")
        append("输出要求：\n")
        append("- 正文只写「本人提交」，按项目 / 模块归纳；其他人提交仅作协作背景，不写成自己的工作；\n")
        append("- 只依据提交说明与变更文件归纳，不要补写记录以外的功能；\n")
        append("- 直接输出 Markdown 正文。\n")
    }

    /** 统一斜杠与结尾分隔符，避免同一目录的不同写法重复列出 */
    private fun normalize(raw: String?): String =
        (raw ?: "").trim().replace('\\', '/').trimEnd('/')
}
