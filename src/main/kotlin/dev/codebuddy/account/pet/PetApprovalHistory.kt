package dev.codebuddy.account.pet

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit



/**
 * 代点记录：逐次一行 jsonl，可跨重启查询。
 *
 * 与 [PetAuditLog] 的分工：
 * - [PetAuditLog] 是「判定证据」——记录点的是哪张卡、走的哪条通道，排查误点用；
 * - 这里是「给人看的账」——项目、对话、命令、**执行结果**，看板上按时间与项目查。
 *
 * 结果由 PostToolUse hook 回传，按「同项目 + 5 分钟内最近一条待回填记录」匹配：
 * jsonl 只追加不改写，命中的结果单独写一行 `kind=result`，查询时按 id 合并。
 *
 * 文件位置：`~/.codebuddy/codebuddy-pet/approval-history.jsonl`，超 2MB 滚 `.1`，只留一代。
 */
object PetApprovalHistory {

    private val log = logger<PetApprovalHistory>()

    private const val FILE_NAME = "approval-history.jsonl"

    /** 单文件上限：比审计宽松，够查最近几周 */
    private const val MAX_BYTES = 2L * 1024L * 1024L

    /** 结果回填的匹配窗口：命令跑完到 hook 回传通常只有几秒 */
    private const val MATCH_WINDOW_MS = 5 * 60_000L

    private const val MAX_WHAT = 400
    private const val MAX_RESULT = 600

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** 一条记录（查询结果） */
    data class Entry(
        val id: Long,
        val at: Long,
        val project: String,
        val title: String,
        val tool: String,
        val what: String,
        val auto: Boolean,
        val danger: Boolean,
        val source: String,
        /** ok / fail / pending / unknown */
        val result: String,
        val resultText: String,
    ) {
        val resultLabel: String
            get() = when (result) {
                "ok" -> "成功"
                "fail" -> "失败"
                "pending" -> "待回填"
                else -> "未记录"
            }
    }

    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, "codebuddy-pet-history").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    /** 待回填的记录：项目 -> (id, 时间戳)，只在内存里做一次桥接 */
    private val pending = HashMap<String, MutableList<Pair<Long, Long>>>()

    fun file(): Path = CodeBuddyHookInstaller.dataDir().resolve(FILE_NAME)

    /**
     * 记一条代点 / 待确认记录。
     *
     * @param auto 是否真的替主人点了
     * @param result 初始结果，通常 pending（等 PostToolUse 回填）
     * @return 记录 id，供结果回填匹配
     */
    fun record(
        project: String,
        title: String,
        tool: String,
        what: String,
        auto: Boolean,
        danger: Boolean,
        source: String,
        result: String = "pending",
    ): Long {
        val id = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val entry = JsonObject().apply {
            addProperty("kind", "event")
            addProperty("id", id)
            addProperty("ts", now)
            addProperty("project", project.take(60))
            addProperty("title", title.take(80))
            addProperty("tool", tool.take(40))
            addProperty("what", what.take(MAX_WHAT))
            addProperty("auto", auto)
            addProperty("danger", danger)
            addProperty("source", source.take(20))
            addProperty("result", result)
        }
        // 项目名解析不出来时（空串）也要登记，否则 PostToolUse 回填永远匹配不到，
        // 记录会一直停在「待回填」；空串与空串配对，口径与「同项目」一致
        if (result == "pending") {
            synchronized(pending) {
                pending.getOrPut(project) { ArrayList() }.add(id to now)
                // 只留最近 20 条待回填，避免长期无结果的项目堆积
                val list = pending.getValue(project)
                while (list.size > 20) list.removeAt(0)
            }
        }
        append(gson.toJson(entry))
        return id
    }

    /**
     * PostToolUse 回传结果：按项目找最近一条待回填记录写入结果行。
     *
     * @return 命中的记录 id；没命中返回 0（例如这条命令不是代点放行的，不需要记账）
     */
    fun attachResult(project: String, ok: Boolean?, text: String): Long {
        val now = System.currentTimeMillis()
        val hit = synchronized(pending) {
            val list = pending[project] ?: return 0L
            while (list.isNotEmpty() && now - list.first().second > MATCH_WINDOW_MS) list.removeAt(0)
            val last = list.lastOrNull() ?: return 0L
            list.removeAt(list.size - 1)
            last.first
        }
        val entry = JsonObject().apply {
            addProperty("kind", "result")
            addProperty("id", hit)
            addProperty("ts", now)
            addProperty("result", when (ok) {
                true -> "ok"
                false -> "fail"
                null -> "unknown"
            })
            addProperty("resultText", text.take(MAX_RESULT))
        }
        append(gson.toJson(entry))
        return hit
    }

    /**
     * 按时间区间与项目查询。
     *
     * @param from 起始毫秒（含）
     * @param to 结束毫秒（含）
     * @param project 空串表示全部项目
     * @return 按时间倒序，最多 [limit] 条
     */
    fun query(from: Long, to: Long, project: String, limit: Int = 200): List<Entry> {
        val events = LinkedHashMap<Long, JsonObject>()
        val results = HashMap<Long, JsonObject>()
        for (file in listOf(file(), file().resolveSibling("$FILE_NAME.1"))) {
            if (!Files.exists(file)) continue
            val lines = try {
                Files.readAllLines(file, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                log.info("读取代点记录失败: ${e.message}")
                continue
            }
            for (line in lines) {
                val obj = try {
                    JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
                } catch (_: Exception) {
                    null
                } ?: continue
                val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
                if (obj.str("kind") == "result") {
                    results[id] = obj
                } else {
                    events[id] = obj
                }
            }
        }
        val wanted = project.trim()
        return events.values
            .asSequence()
            .map { obj ->
                val id = obj.get("id")?.asLong ?: 0L
                val at = obj.get("ts")?.asLong ?: 0L
                val res = results[id]
                Entry(
                    id = id,
                    at = at,
                    project = obj.str("project"),
                    title = obj.str("title"),
                    tool = obj.str("tool"),
                    what = obj.str("what"),
                    auto = obj.bool("auto"),
                    danger = obj.bool("danger"),
                    source = obj.str("source"),
                    result = res?.str("result")?.ifBlank { obj.str("result") } ?: obj.str("result"),
                    resultText = res?.str("resultText").orEmpty(),
                )
            }
            .filter { it.at in from..to }
            .filter { wanted.isBlank() || it.project.equals(wanted, ignoreCase = true) }
            .sortedByDescending { it.at }
            .take(limit)
            .toList()
    }

    /** 记录里出现过的项目名（去重，按最近出现排序），供筛选下拉 */
    fun projects(limit: Int = 30): List<String> {
        val now = System.currentTimeMillis()
        return query(0L, now, "", limit = 500)
            .map { it.project }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
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
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            } catch (e: Exception) {
                log.info("代点记录写入失败: ${e.message}")
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    } catch (_: Exception) {
        false
    }
}
