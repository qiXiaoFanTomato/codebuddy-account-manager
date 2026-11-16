package dev.codebuddy.account.pet

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 代点审计流水（逐次一行，jsonl）。
 *
 * 与「今日代点次数」分工不同：计数只回答「今天替主人点了多少次」，
 * 这里回答「点的是哪张卡、走的哪条判定通道、是不是降级到点「本次会话内始终允许」」，
 * 是排查误点的唯一证据来源。文件位置与 hook 诊断日志同目录：
 * `~/.codebuddy/codebuddy-pet/approval-audit.jsonl`。
 *
 * 写入一律丢到后台单线程，判定路径（注入脚本回传 / hook / 日志）不因磁盘 IO 变慢。
 */
object PetAuditLog {

    private val log = logger<PetAuditLog>()

    private const val FILE_NAME = "approval-audit.jsonl"

    /** 单文件上限；超过就滚到 `.1`（只留一代，排查够用又不至于无限增长） */
    private const val MAX_BYTES = 512L * 1024L

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * 写盘专用单线程：不占平台 IO 池，空闲 30 秒回收线程。
     * 判定路径只往队列丢任务，磁盘慢也不会拖慢代点决策。
     */
    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, "codebuddy-pet-audit").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    fun file(): Path = CodeBuddyHookInstaller.dataDir().resolve(FILE_NAME)

    /**
     * 记一条审批判定。
     *
     * @param source 判定来源：`page`（页面代点）/ `hook`（hook 决策通道）/ `log`（日志兜底）
     * @param auto 本次是否真的替主人点了
     * @param kind 点中的按钮类型：`once`（允许一次）/ `session`（本次会话内始终允许）/ `allow`（宿主默认放行）
     * @param via 判定通道：`keyword` / `structured` / `default_allow` / `log`
     * @param label 命中的按钮文案原文（截断），用来核对是否点错了按钮
     * @param what 确认卡正文摘要
     */
    fun approval(
        source: String,
        auto: Boolean,
        kind: String,
        via: String,
        label: String,
        what: String,
        project: String,
        sessionId: String,
        credit: Boolean = false,
        danger: Boolean = false,
    ) {
        val entry = JsonObject().apply {
            addProperty("ts", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            addProperty("source", source)
            addProperty("auto", auto)
            addProperty("kind", kind)
            addProperty("via", via)
            addProperty("label", label.take(40))
            addProperty("what", what.take(120))
            addProperty("project", project.take(60))
            addProperty("sessionId", sessionId.take(80))
            addProperty("credit", credit)
            addProperty("danger", danger)
        }
        append(gson.toJson(entry))
    }

    private fun append(line: String) {
        executor.execute {
            try {
                val file = file()
                Files.createDirectories(file.parent)
                if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                    Files.move(
                        file,
                        file.resolveSibling("$FILE_NAME.1"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                Files.write(
                    file,
                    (line + "\n").toByteArray(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            } catch (e: Exception) {
                log.info("代点审计写入失败: ${e.message}")
            }
        }
    }
}
