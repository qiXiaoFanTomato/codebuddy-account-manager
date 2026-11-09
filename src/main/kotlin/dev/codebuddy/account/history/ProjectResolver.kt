package dev.codebuddy.account.history

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * workspace hash -> 可读项目名。
 *
 * CodeBuddy 的历史目录用 `history/<workspaceHash>` 归档会话，该 hash 实测为
 * `MD5(<小写盘符 + 反斜杠形式的项目绝对路径>)` 的十六进制小写串。
 * IntelliJ 的 `options/recentProjects.xml` 里保存了打开过的项目路径清单，
 * 因此可以先按同一口径算 MD5 精确反查项目名与目录，命中时无需读取任何消息文件。
 *
 * 清单外的 workspace（旧项目、其它端创建等）仍走启发式：从会话消息里的绝对路径
 * 采样，逐层下探求公共前缀并剥掉源码目录段。解析失败时退回 hash 前 8 位。
 * 解析成功的结果按 workspace 缓存，避免重复读盘。
 */
object ProjectResolver {

    private val log = logger<ProjectResolver>()

    /** 项目标识：[name] 用于展示，[path] 为解析出的项目目录（未解析出时为 null） */
    data class Project(val name: String, val path: String? = null)

    private val cache = ConcurrentHashMap<String, Project>()

    /** 项目路径清单（workspace hash -> 项目），按文件 mtime 失效 */
    @Volatile
    private var knownProjects: Map<String, Project>? = null

    @Volatile
    private var knownStamp = Long.MIN_VALUE

    /** recentProjects.xml 中 `<entry key="项目路径">` 的 key */
    private val entryKey = Regex("""<entry\s+key="([^"]+)""")

    private val hex = "0123456789abcdef".toCharArray()

    /** 每个 workspace 最多采样几个会话 */
    private const val SAMPLE_CONVERSATIONS = 2

    /** 单个会话最多读取几个消息文件 */
    private const val MAX_FILES_PER_CONV = 12

    /** 单个 workspace 的读取预算 */
    private const val MAX_BYTES_PER_WS = 3L * 1024 * 1024

    private const val MAX_PATHS = 400

    /** 两个以上反斜杠折叠成一个（消息正文里的 JSON 被多层转义） */
    private val multiBackslash = Regex("""\\{2,}""")

    /** JSON 转义序列，先抹掉以免路径尾部粘上 n / t */
    private val jsonEscape = Regex("""\\+[ntr"]""")

    /**
     * Windows 绝对路径。
     *
     * 消息正文是多层转义的 JSON，路径后面常常直接粘着 `" , pattern : ..."` 之类的参数，
     * 这里先按宽字符集匹配，再用 [trimNoise] 把 JSON 参数截掉。
     */
    private val absPath = Regex("""[A-Za-z]:\\[^"'<>|*?\r\n\t]{2,180}""")

    /** 判定项目根的磁盘标记（命中任一即可） */
    private val markers = listOf(
        ".git", "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
        "settings.gradle", "settings.gradle.kts", "go.mod", "Cargo.toml",
        "requirements.txt", "pyproject.toml", "README.md", ".idea",
    )

    /** 与项目无关的系统目录 */
    private val noiseKeywords = listOf(
        "\\AppData\\", "\\Program Files", "\\ProgramData\\", "\\Windows\\", "\\Temp\\",
        "\\.codebuddy\\", "\\.gradle\\", "\\.idea\\", "\\node_modules\\", "\\.git\\",
        "\\JetBrains\\", "\\Java\\", "\\Maven\\", "\\m2\\",
    )

    /** 出现在项目目录下面的源码 / 产物目录名，用于把公共前缀收回到项目根 */
    private val sourceSegments = setOf(
        "src", "app", "lib", "libs", "main", "test", "tests", "kotlin", "java", "resources",
        "components", "pages", "views", "view", "api", "apis", "router", "routes", "store",
        "static", "assets", "public", "docs", "config", "utils", "util", "common", "service",
        "services", "controller", "controllers", "mapper", "entity", "dto", "vo", "model",
        "models", "middleware", "plugins", "modules", "packages", "dist", "build", "target",
        "out", "bin", "scripts", "db", "sql", "logs", "types",
    )

    /**
     * 解析一批会话所属的项目，返回 workspace hash -> 项目。
     *
     * 优先按项目路径清单精确反查；清单外的 workspace 才读取消息文件做启发式推断，
     * 因此务必在后台线程调用。
     */
    fun resolve(conversations: List<HistoryReader.Conversation>): Map<String, Project> {
        if (conversations.isEmpty()) return emptyMap()
        val known = knownProjects()
        val out = HashMap<String, Project>()
        for ((ws, list) in conversations.groupBy { it.workspace }) {
            val cached = cache[ws]
            if (cached != null) {
                out[ws] = cached
                continue
            }
            val exact = known[ws]
            if (exact != null) {
                out[ws] = exact
                cache[ws] = exact
                continue
            }
            val project = try {
                val root = pickRoot(samplePaths(list))
                if (root != null) {
                    Project(root.substringAfterLast('\\'), root).also { cache[ws] = it }
                } else {
                    Project(fallbackName(ws), null)
                }
            } catch (e: Exception) {
                log.warn("解析工作区项目名失败: $ws", e)
                Project(fallbackName(ws), null)
            }
            out[ws] = project
        }
        return out
    }

    fun clearCache() {
        cache.clear()
        knownProjects = null
        knownStamp = Long.MIN_VALUE
    }

    // ---------------------------------------------------------------
    // 项目路径清单（精确反查）
    // ---------------------------------------------------------------

    /** 文件 mtime 变化时自动重载（新打开项目即可生效）；文件缺失或损坏时返回空表走启发式兜底 */
    private fun knownProjects(): Map<String, Project> {
        val file = knownProjectsFile()
        val stamp = file?.let {
            try { Files.getLastModifiedTime(it).toMillis() } catch (_: Exception) { -1L }
        } ?: -1L
        val cached = knownProjects
        if (cached != null && stamp == knownStamp) return cached
        return synchronized(this) {
            val again = knownProjects
            if (again != null && stamp == knownStamp) {
                again
            } else {
                loadKnownProjects(file).also {
                    knownProjects = it
                    knownStamp = stamp
                }
            }
        }
    }

    private fun knownProjectsFile(): Path? = try {
        PathManager.getConfigDir().resolve("options").resolve("recentProjects.xml")
            .takeIf { Files.isRegularFile(it) }
    } catch (_: Exception) {
        null
    }

    private fun loadKnownProjects(file: Path?): Map<String, Project> {
        if (file == null) return emptyMap()
        val text = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            log.warn("读取项目路径清单失败: $file", e)
            return emptyMap()
        }

        val out = HashMap<String, Project>()
        for (m in entryKey.findAll(text)) {
            val raw = decodeXml(m.groupValues[1]).trim()
            // 只收 Windows 绝对路径，跳过 lastProjectLocation 之类的普通选项
            if (raw.length < 3 || raw[1] != ':') continue
            val path = raw.replace('/', '\\')
            out[workspaceHash(raw)] = Project(path.substringAfterLast('\\'), path)
        }
        return out
    }

    /** history 目录名口径：路径转正斜杠为反斜杠、盘符小写，再取 UTF-8 的 MD5 十六进制小写串 */
    private fun workspaceHash(rawPath: String): String {
        val normalized = rawPath.replace('/', '\\')
        val winPath = normalized.substring(0, 1).lowercase() + normalized.substring(1)
        val digest = MessageDigest.getInstance("MD5").digest(winPath.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(hex[(b.toInt() ushr 4) and 0xF])
                append(hex[b.toInt() and 0xF])
            }
        }
    }

    private fun decodeXml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    // ---------------------------------------------------------------
    // 采样
    // ---------------------------------------------------------------

    private fun samplePaths(convs: List<HistoryReader.Conversation>): List<String> {
        val paths = LinkedHashSet<String>()
        var budget = MAX_BYTES_PER_WS
        val ordered = convs.sortedByDescending { it.lastMessageAt }.take(SAMPLE_CONVERSATIONS)

        for (conv in ordered) {
            val dir = conv.messageDir ?: continue
            val files = try {
                Files.list(dir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                        .sorted()
                        .limit(MAX_FILES_PER_CONV.toLong())
                        .toList()
                }
            } catch (_: Exception) {
                continue
            }
            for (f in files) {
                if (budget <= 0 || paths.size >= MAX_PATHS) return paths.toList()
                try {
                    val size = Files.size(f)
                    if (size > budget) continue
                    budget -= size
                    paths.addAll(extractPaths(Files.readString(f, StandardCharsets.UTF_8)))
                } catch (_: Exception) {
                    // 单条消息损坏不影响其余采样
                }
            }
        }
        return paths.toList()
    }

    /** 从消息文本中提取所有干净的项目内绝对路径 */
    private fun extractPaths(text: String): List<String> {
        val cleaned = text.replace(jsonEscape, " ").replace(multiBackslash, "\\")
        val out = ArrayList<String>()
        for (m in absPath.findAll(cleaned)) {
            val p = trimNoise(m.value)
            if (p.length < 4) continue
            val lower = p.lowercase()
            if (noiseKeywords.any { lower.contains(it.lowercase()) }) continue
            out.add(p)
            if (out.size >= 60) break
        }
        return out
    }

    /**
     * 截掉路径后面粘过来的 JSON 参数。
     *
     * 例如 `d:\Code\a\src , pattern :全文` → `d:\Code\a\src`；
     * 目录名本身带空格（如 `My Code`）不受影响。
     */
    private fun trimNoise(raw: String): String {
        var end = raw.length
        for (i in raw.indices) {
            val c = raw[i]
            when {
                c == ',' -> {
                    end = i
                    break
                }

                c == ' ' || c == '\t' -> {
                    var j = i
                    while (j < raw.length && (raw[j] == ' ' || raw[j] == '\t')) j++
                    if (j - i >= 3) {
                        end = i
                        break
                    }
                    if (j < raw.length && raw[j] in ",]};:=\'\"") {
                        end = i
                        break
                    }
                }
            }
        }
        return raw.substring(0, end).trimEnd('\\', ' ', '.', ')', ']', '}')
    }

    // ---------------------------------------------------------------
    // 项目根推导
    // ---------------------------------------------------------------

    private fun pickRoot(paths: List<String>): String? {
        val segs = paths.map { p -> p.split('\\').filter { it.isNotBlank() } }
            .filter { it.size >= 2 }
        if (segs.isEmpty()) return null

        // 逐层下探：每层只跟随覆盖多数路径的那个子目录，得到最深的项目内目录
        val deepest = deepestDominant(segs)
        if (deepest.size < 2) return null

        // 优先用磁盘上的项目标记确认项目根（package.json / pom.xml / .git …）
        toProjectRoot(deepest)?.let { return it }

        // 没有标记时退回「剥掉源码目录段」的启发式
        var seg = deepest.toMutableList()
        if (seg.size > 2 && seg.last().contains('.')) seg = seg.dropLast(1).toMutableList()
        while (seg.size > 2 && seg.last().lowercase() in sourceSegments) {
            seg = seg.dropLast(1).toMutableList()
        }
        if (seg.size < 2) return null
        if (seg.last().length <= 1) return null
        return seg.joinToString("\\")
    }

    /** 逐层跟随「多数路径共同经过」的子目录，返回该目录的段列表 */
    private fun deepestDominant(segs: List<List<String>>): List<String> {
        val prefix = ArrayList<String>()
        var pool = segs
        var level = 0
        var budget = 12
        while (budget-- > 0) {
            val usable = pool.filter { it.size > level }
            if (usable.isEmpty()) break
            val groups = usable.groupBy { it[level].lowercase() }
            val top = groups.maxByOrNull { it.value.size } ?: break
            // 覆盖不足 70% 说明这一层开始分叉，停止下探
            if (top.value.size * 10 < usable.size * 7) break
            prefix.add(top.value.first()[level])
            pool = top.value
            level++
        }
        return prefix
    }

    /** 自深向浅找第一个带项目标记的目录 */
    private fun toProjectRoot(seg: List<String>): String? {
        for (depth in seg.size downTo 2) {
            val dir = seg.subList(0, depth).joinToString("\\")
            if (hasProjectMarker(dir)) return dir
        }
        return null
    }

    private fun hasProjectMarker(dir: String): Boolean {
        val p = try {
            java.nio.file.Path.of(dir)
        } catch (_: Exception) {
            return false
        }
        if (!Files.isDirectory(p)) return false
        return markers.any {
            try {
                Files.exists(p.resolve(it))
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun fallbackName(workspace: String): String =
        if (workspace.isBlank()) "未标注项目" else "项目 ${workspace.take(8)}"
}
