package dev.codebuddy.account.pet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.history.ProjectResolver
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors



/**
 * 由 hook 带上来的 `session_id` 反查「项目 + 对话标题」。
 *
 * 为什么不用 `cwd`：实测宿主给的 `cwd` 是 IDE 进程工作目录（`C:\Users\<用户>`），
 * 不是项目路径，拿它算项目名会退化成「最近活跃项目」，多开时容易对错号。
 *
 * 数据来源是 CodeBuddy 自己的会话索引：
 * `%LOCALAPPDATA%\CodeBuddyExtension\Data\<uid>\{JetBrains|CodeBuddyIDE}\<uid>\history\<workspace>\index.json`
 * —— 顶层 `conversations[].id` 就是 `session_id`，`name` 是标题，父目录名是 workspace hash。
 * 项目名再用日志文件名 `<项目名>__<workspaceHash>.log` 翻译回来。
 *
 * 全部只读索引，命中缓存 [CACHE_MS]、未命中只缓存 [MISS_MS]（新会话索引有落盘延迟，
 * 错一次不能跟着错几分钟）；查不到时再按 `transcript_path` 认一次 workspace，仍拿不到就返回 null。
 */
object SessionContext {

    private val log = logger<SessionContext>()

    private const val CACHE_MS = 5 * 60_000L

    /**
     * 查不到时的短缓存。
     *
     * 新建的对话要等宿主把会话写进 index.json，这段时间内按 sessionId 一定查不到；
     * 如果也缓存 5 分钟，这条对话的前几分钟每次确认都会显示成兜底文案。
     */
    private const val MISS_MS = 15_000L

    /** 一次扫描最多看这么多个 index.json（按最近修改排序，最新的会话一定在里面） */
    private const val MAX_INDEX_FILES = 16

    /**
     * index.json 相对 Data 根的最大深度。
     *
     * 实测布局是 `Data/<uid>/{JetBrains|CodeBuddyIDE}/<uid>/history/<workspace>/index.json`，
     * index.json 落在第 6 层；旧版布局少一层。取 8 留出余量，
     * 层数具体是几不影响正确性——下面还有「父目录必须是 history」的硬判定。
     */
    private const val MAX_INDEX_DEPTH = 8

    /** hook 的 `transcript_path` 里的 `history\<workspace>\` 段 */
    private val TRANSCRIPT_HISTORY = Regex("""[\\/]history[\\/]([0-9a-fA-F]{32})(?:[\\/]|$)""")

    /** 一条会话的落点 */
    data class Resolved(
        val sessionId: String,
        val project: String,
        val workspace: String,
        val title: String,
    ) {
        /** 直接给提示 / 气泡用的一句话位置 */
        fun whereText(): String {
            val name = project.ifBlank { "未知项目" }
            return if (title.isBlank()) name else "$name · $title"
        }
    }

    private val cache = HashMap<String, Pair<Long, Resolved?>>()

    /**
     * 按 sessionId 查；查不到返回 null（索引有落盘延迟，新会话前几次可能查不到）。
     *
     * @param transcriptPath hook 带上来的转写文件路径，只在索引查不到时用来认 workspace；
     *        标题留给调用方按 workspace 现读——会话自己的 index.json 只有消息列表，没有标题
     */
    fun of(sessionId: String, transcriptPath: String = ""): Resolved? {
        val id = sessionId.trim()
        if (id.isBlank()) return null
        val now = System.currentTimeMillis()
        synchronized(cache) {
            val hit = cache[id]
            if (hit != null) {
                val ttl = if (hit.second == null) MISS_MS else CACHE_MS
                if (now - hit.first < ttl) return hit.second
            }
        }
        val resolved = try {
            scan(id) ?: fromTranscript(transcriptPath, id)
        } catch (e: Exception) {
            log.info("按会话查项目失败: ${e.message}")
            null
        }
        synchronized(cache) {
            cache[id] = now to resolved
            if (cache.size > 64) cache.entries.removeIf { now - it.value.first > CACHE_MS }
        }
        return resolved
    }

    /**
     * 提示里的位置文案：sessionId 优先，查不到退回 `cwd` 猜测。
     *
     * 两者都拿不到时返回空串，调用方自行决定要不要显示这一行。
     */
    fun whereText(sessionId: String, cwd: String): String {
        of(sessionId)?.whereText()?.takeIf { it.isNotBlank() }?.let { return it }
        val (project, workspace) = CbEventCenter.resolveWhere(cwd)
        return CbEventCenter.whereText(project, workspace)
    }

    /** 代点记录用：项目名，优先 sessionId，其次 cwd */
    fun projectOf(sessionId: String, cwd: String): String {
        of(sessionId)?.project?.takeIf { it.isNotBlank() }?.let { return it }
        return CbEventCenter.resolveWhere(cwd).first
    }

    // ------------------------------------------------------------------

    private fun scan(sessionId: String): Resolved? {
        val root = dataRoot() ?: return null
        val indexes = try {
            Files.walk(root, MAX_INDEX_DEPTH).use { stream ->
                stream.filter { it.fileName?.toString() == "index.json" }
                    // 只认 history\<workspace>\index.json 这一层
                    .filter { it.parent?.parent?.fileName?.toString() == "history" }
                    .sorted(compareByDescending { lastModified(it) })
                    .limit(MAX_INDEX_FILES.toLong())
                    .collect(Collectors.toList())
            }
        } catch (e: Exception) {
            log.info("扫描会话索引失败: ${e.message}")
            return null
        }
        for (index in indexes) {
            val text = try {
                Files.readString(index, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            // 大文件先做一次字符串剪枝，避免每个 index 都完整解析
            if (!text.contains(sessionId)) continue
            val rootObj = try {
                JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            } ?: continue
            val conversations = rootObj.getAsJsonArray("conversations") ?: continue
            for (element in conversations) {
                val conv = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = conv.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (id != sessionId) continue
                val workspace = index.parent?.fileName?.toString().orEmpty()
                val title = conv.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim()
                return Resolved(
                    sessionId = sessionId,
                    project = projectNameOf(workspace),
                    workspace = workspace,
                    title = title,
                )
            }
        }
        return null
    }

    /**
     * 索引还没落盘时从 `transcript_path` 认 workspace。
     *
     * 路径里能认出的只有 `history\<workspaceHash>\<sessionId>\` 这一层，
     * 认不出就返回 null——宁可只显示项目名，也不按路径末段瞎猜项目。
     */
    private fun fromTranscript(path: String, sessionId: String): Resolved? {
        val raw = path.trim()
        if (raw.isBlank()) return null
        val workspace = TRANSCRIPT_HISTORY.find(raw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return null
        return Resolved(
            sessionId = sessionId,
            project = projectNameOf(workspace),
            workspace = workspace,
            title = "",
        )
    }

    /** workspace hash -> 项目名：先运行态、再 recentProjects.xml、最后按日志文件名翻译 */
    private fun projectNameOf(workspace: String): String {
        if (workspace.isBlank()) return ""
        val name = fromRuntime(workspace) ?: fromRecentProjects(workspace) ?: fromLogName(workspace) ?: ""
        // 账号名不是项目名：宿主给的 cwd 就是 `C:\Users\<用户>`，末段拿来当项目会把账本写脏
        val user = System.getProperty("user.name").orEmpty()
        return if (user.isNotBlank() && name.equals(user, ignoreCase = true)) "" else name
    }

    /** 运行态里就有项目名，最快 */
    private fun fromRuntime(workspace: String): String? = try {
        CbEventCenter.activeProjects()
            .firstOrNull { it.workspace == workspace }
            ?.project?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** 按 workspace hash 反查 recentProjects.xml：不依赖当天的日志是否还在 */
    private fun fromRecentProjects(workspace: String): String? = try {
        ProjectResolver.nameOf(workspace)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** 日志文件名固定是 `<项目名>__<hash>.log` */
    private fun fromLogName(workspace: String): String? {
        val local = System.getenv("LOCALAPPDATA") ?: return null
        val logs = Paths.get(local, "CodeBuddyExtension", "Logs", "JetBrains")
        if (!Files.isDirectory(logs)) return null
        return try {
            Files.walk(logs, 3).use { stream ->
                val hit = stream.filter { it.fileName?.toString()?.contains(workspace) == true }
                    .sorted(compareByDescending { lastModified(it) })
                    .findFirst()
                if (!hit.isPresent) return@use null
                hit.get().fileName.toString().substringBefore("__").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dataRoot(): Path? {
        val local = System.getenv("LOCALAPPDATA") ?: return null
        val root = Paths.get(local, "CodeBuddyExtension", "Data")
        return if (Files.isDirectory(root)) root else null
    }

    private fun lastModified(path: Path): Long = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (_: Exception) {
        0L
    }
}
