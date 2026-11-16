package dev.codebuddy.account.service

import com.intellij.openapi.application.PathManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 操作记录：把面板动作（切换 / 删除 / 刷新 / 导入导出 / 失败原因）追加落盘。
 *
 * 之前所有反馈只有桌宠气泡，弹完就没了；出错想回看只能翻 idea.log。
 * 文件位置：<config>/codebuddy-accounts/operations.log
 */
object OperationLog {

    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 超过该大小就裁剪，避免无限增长 */
    private const val MAX_BYTES = 512L * 1024L

    /** 裁剪后保留的行数 */
    private const val KEEP_LINES = 800

    val file: Path by lazy {
        PathManager.getConfigDir().resolve("codebuddy-accounts").resolve("operations.log")
    }

    /** 追加一条记录（失败静默：日志不能反过来影响主流程） */
    fun log(text: String) {
        try {
            Files.createDirectories(file.parent)
            val line = "[${LocalDateTime.now().format(FMT)}] $text\n"
            Files.writeString(
                file,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            trimIfNeeded()
        } catch (_: Exception) {
        }
    }

    /** 读最近若干行（用于界面展示） */
    fun tail(maxLines: Int = 200): String {
        return try {
            if (!Files.isRegularFile(file)) return ""
            val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
            lines.takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    private fun trimIfNeeded() {
        if (Files.size(file) <= MAX_BYTES) return
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
        Files.writeString(
            file,
            lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"),
            StandardCharsets.UTF_8,
        )
    }
}
