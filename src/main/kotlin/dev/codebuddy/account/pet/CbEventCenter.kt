package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.history.ActivityFeed
import dev.codebuddy.account.history.ProjectResolver
import dev.codebuddy.account.service.AccountEvents
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayDeque



/**
 * CodeBuddy 运行态事件的统一入口。
 *
 * 两个事件源在这里汇合，避免「日志认不出就整条丢失」造成的漏提示：
 *  - [CbWebObserver]：JCEF 页面里的 DOM 常驻观察（权限弹窗 / 限流 / 中断）；
 *  - [CodeBuddyWatcher]：CodeBuddy 运行日志（兜底通道）。
 *
 * 同一事件在 [CLAIM_WINDOW_MS] 内只提示一次，两个通道谁先到谁提示；
 * 模型限流记录会持久化，重启 IDE 后仍保留「预计解封时间」。
 */
object CbEventCenter {

    private val log = logger<CbEventCenter>()

    private const val STORE_KEY = "codebuddy.pet.modelLimits"

    /** 今日代点次数：日期 + 计数分开存，跨天靠日期判定归零 */
    private const val AUTO_DAY_KEY = "codebuddy.pet.autoApproveDay"
    private const val AUTO_COUNT_KEY = "codebuddy.pet.autoApproveCount"

    /** 默认放行事件在列表里的合并窗口 */
    private const val AUTO_EVENT_MERGE_MS = 60_000L

    /** 默认放行提示的最小间隔：与桌宠气泡的停留时长一致，避免连续放行时刷屏 */
    private const val AUTO_NOTICE_GAP_MS = 5_000L

    private val WHITESPACE = Regex("\\s+")
    private const val CLAIM_WINDOW_MS = 60_000L
    private const val LIMIT_KEEP_MS = 30 * 60_000L
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 一条模型限制记录；[code] 6004=频率限制，6000=用量限制，14018=额度耗尽 */
    data class ModelLimit(
        val model: String,
        val code: Int,
        val resetAt: Long,
        val at: Long,
    ) {
        val kind: String
            get() = when (code) {
                6000 -> "用量限制"
                14018 -> "额度不足"
                else -> "频率限制"
            }
        val label: String get() = if (code == 6000) "预计恢复" else "预计解封"

        /** 类型 + 解封时刻 + 剩余时间；官方没给解封时间时给出可执行建议，不编造时间 */
        fun detail(now: Long = System.currentTimeMillis()): String {
            val base = "$model $kind"
            if (resetAt <= 0L) return "$base，${advice()}"
            val clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(resetAt), ZoneId.systemDefault())
            return "$base，${label} ${clock.format(TIME_FORMAT)}（${remaining(now)}）"
        }

        private fun advice(): String = when (code) {
            6000 -> "用量已达上限，等额度周期重置后再试"
            14018 -> "套餐额度不足，建议切换账号或补充额度"
            else -> "稍等片刻再试，或换一个模型"
        }

        fun remaining(now: Long = System.currentTimeMillis()): String {
            // 没有官方解封时间时不能按 0 计算，否则会显示成「已过解封时间」
            if (resetAt <= 0L) return "时间未知"
            val millis = resetAt - now
            if (millis <= 0L) return "已过解封时间"
            val minutes = millis / 60_000L
            val hours = minutes / 60
            return when {
                hours >= 1L -> "约 $hours 小时${if (minutes % 60 > 0) " ${minutes % 60} 分钟" else ""}"
                else -> "约 ${minutes.coerceAtLeast(1L)} 分钟"
            }
        }
    }

    /**
     * 最近一步的输出吞吐。
     *
     * [durationMs] 取 CodeBuddy 日志 `Step execution metrics: duration=...` 的步耗时口径，
     * 含工具执行与等待，因此速度是**下界估计**，不是纯生成速率。
     */
    data class UsageSample(
        val model: String,
        val outputTokens: Int,
        val durationMs: Long,
        val at: Long,
        /** 产生这一步的项目（history 目录口径的 workspace hash）；状态栏按它分组 */
        val workspace: String = "",
        /** 项目展示名（日志文件名口径） */
        val project: String = "",
    ) {
        val tokensPerSecond: Double
            get() = if (durationMs <= 0L || outputTokens <= 0) 0.0 else outputTokens * 1000.0 / durationMs

        fun detail(): String {
            val seconds = String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1000.0)
            val speed = String.format(java.util.Locale.ROOT, "%.0f", tokensPerSecond)
            return "$model $outputTokens tok / ${seconds}s ≈ $speed tok/s"
        }
    }

    /**
     * 页面通道的实时输出样本。
     *
     * 页面只数得到**字符**，token 数是按 [tokensPerChar] 估算的；换算系数用每一步结束时
     * 日志里的真实 `outputTokens` 反向校准，因此读数会向真实值收敛，但仍是估算值，
     * 与 [UsageSample]（步末实测）不是同一口径，展示时要分开标注。
     */
    data class StreamSample(
        val workspace: String,
        val project: String,
        val tokensPerSecond: Double,
        val at: Long,
    )

    /**
     * 对话运行态（状态栏用）。
     *
     * 日志里没有「这一轮开始」的明确信号，只能靠步指标、确认、结束三类行推断：
     * [THINKING] 覆盖思考与工具执行，[WAITING] 表示在等主人点确认。
     */
    enum class Phase { IDLE, THINKING, WAITING }

    /**
     * 一个项目当前的活动状态。
     *
     * [workspace] 是 history 目录口径的 hash（日志文件名里的 hash 与它一致），
     * 状态栏用它把「运行态 / 速度 / 对话标题」严格对齐，不靠项目名字符串匹配。
     * [at] 是最近一次对话信号时刻，用于超时兜底与排序。
     */
    data class ProjectActivity(
        val workspace: String,
        val project: String,
        val phase: Phase,
        val at: Long,
    )

    /** 功能页展示用的最近事件 */
    data class EventRecord(
        val at: Long,
        val type: String,
        val title: String,
        val detail: String,
        val auto: Boolean,
    ) {
        val timeText: String
            get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val typeLabel: String
            get() = when (type) {
                "approval" -> "权限弹窗"
                "rateLimit" -> "模型限制"
                "interrupted" -> "会话中断"
                "phrase" -> "快捷短语"
                "growth" -> "成长活跃"
                "history" -> "会话迁移"
                else -> type
            }
    }

    private const val MAX_EVENTS = 60

    /** workspace 目录名口径：32 位十六进制 MD5 */
    private val WORKSPACE_HASH = Regex("^[0-9a-fA-F]{32}$")

    /** 最近事件（倒序，功能页直接读） */
    private val events = ArrayList<EventRecord>()

    /** 今日代点次数的落盘状态；[autoDay] 为 yyyy-MM-dd，与当天不符即视为 0 */
    @Volatile
    private var autoDay: String = ""

    @Volatile
    private var autoCount: Int = 0

    fun recentEvents(): List<EventRecord> = synchronized(events) { events.asReversed().toList() }

    fun clearEvents() {
        synchronized(events) { events.clear() }
    }

    /** 今日代点次数；跨天读到旧日期直接返回 0，不改写落盘值 */
    fun autoApprovalsToday(): Int {
        if (autoDay != LocalDate.now().toString()) return 0
        return autoCount
    }

    /**
     * 记一次「代点」（脚本自动同意 / hook 默认放行 / 日志兜底点同意）。
     *
     * 只累计今日次数，不动事件列表也不弹提示（提示由各自通道负责）；
     * hook 默认放行需要提示，走 [onHookAutoApproval]。
     */
    fun countAutoApproval() {
        val today = LocalDate.now().toString()
        synchronized(this) {
            autoCount = if (autoDay == today) autoCount + 1 else 1
            autoDay = today
            saveAutoCount()
        }
    }

    /**
     * hook 通道的默认放行：桌宠必须提示，主人才知道它刚替自己点了。
     *
     * 计数与事件分开处理：
     * - 今日代点次数每次都 +1，这是「今天一共执行了多少次」的口径；
     * - 事件列表把 1 分钟内的连续放行合并成一条，否则每次工具调用都插一条，
     *   60 条窗口会瞬间刷满，限流、中断这些真要看的事件全被挤掉。
     */
    fun onHookAutoApproval(
        tool: String,
        detail: String,
        source: String = "hook",
        danger: Boolean = false,
        project: String = "",
        workspace: String = "",
        sessionTitle: String = "",
    ) {
        val name = tool.ifBlank { "工具调用" }
        val what = detail.replace(WHITESPACE, " ").trim().take(60)
        // 逐次审计：谁放的行（hook 决策 / 日志兜底）、放的是什么工具与命令原文
        PetAuditLog.approval(
            source = source, auto = true, kind = "allow", via = source, label = name,
            what = detail, project = project, sessionId = "", danger = danger,
        )
        // 危险命令的代放不能和普通放行共用文案，主人一眼要能看出这次是「危险」级别
        val title = if (danger) "我先替主人放行了危险命令「$name」" else "我先替主人放行了「$name」"
        val ordinal = autoApprovalsToday() + 1
        // 放行提示也要说清是哪个项目、哪个对话，否则多开时根本对不上号
        val whereName = displayProject(project, workspace)
        val chatTitle = sessionTitle.ifBlank { titleOf(workspace) }
        val where = when {
            whereName.isBlank() -> ""
            chatTitle.isBlank() -> whereName
            else -> "$whereName · $chatTitle"
        }
        val parts = ArrayList<String>(3)
        if (what.isNotBlank()) parts.add(what)
        if (where.isNotBlank()) parts.add(where)
        parts.add("今日第 $ordinal 次代点")
        val content = parts.joinToString(" · ")
        // hook 通道与日志兜底可能同时认出同一次放行：去重键只取命令原文
        // （hook 报的是工具名 Bash，日志报的是「命令」），提示只播一次，次数照常累计
        // 落一条可查询的代点记录：项目 / 对话 / 命令，结果等 PostToolUse 回填
        PetApprovalHistory.record(
            project = whereName, title = chatTitle, tool = name,
            what = detail, auto = true, danger = danger, source = source,
        )
        val duplicated = !claim("autoAllow", what)
        val now = System.currentTimeMillis()
        // 再加一个最小间隔：一轮对话里连续放行时不把提示刷成走马灯，只更新次数与事件
        if (!duplicated && now - lastAutoNoticeAt >= AUTO_NOTICE_GAP_MS) {
            lastAutoNoticeAt = now
            if (danger) {
                // 危险命令代放必须让主人看见：WARN 级 + 12 秒，不吃默认的 5 秒
                PetNotifier.notify(PetNotifier.Level.WARN, title, content, durationMs = 12_000)
            } else {
                // 默认放行是高频路径，用 INFO 的默认 5 秒时长，别让提示一直占着桌宠
                PetNotifier.notify(PetNotifier.Level.INFO, title, content)
            }
        }
        countAutoApproval()
        mergeAutoEvent(title, content)
        // 登记一次「刚放过这条」：宿主若因为 Auto-run 关闭又弹了确认卡，
        // 页面兜底点掉时据此认领，不再记第二次
        rememberAutoAllow(whereName)
    }

    /**
     * 日志通道认出的「默认放行」：宿主把命令直接放过去了（`needConfirm=false`）。
     *
     * 与 [onHookAutoApproval] 同一套文案与计数，去重键也一致，
     * 因此 hook 真正接管时同一条命令不会被提示两次。
     */
    fun onLogAutoApproval(tool: String, detail: String, project: String = "", workspace: String = "") {
        if (!PetSettings.getInstance().enabled) return
        onHookAutoApproval(tool, detail, source = "log", project = project, workspace = workspace)
    }

    /**
     * 提示里的位置信息：项目 + 当前对话标题。
     *
     * 标题按 workspace 直读会话索引（[titleOf]，3 秒缓存），读不到就只显示项目名，
     * 不让提示因为索引读失败而空着。
     */
    fun whereText(project: String, workspace: String): String {
        val name = project.trim()
        if (name.isBlank()) return ""
        val title = titleOf(workspace)
        return if (title.isBlank()) name else "$name · $title"
    }

    /**
     * 某个项目的当前对话标题；读不到返回空串（索引有落盘延迟，不当作错误）。
     *
     * 走 [ActivityFeed.titleOf]：只读这一个 workspace 的索引、3 秒缓存，
     * 正在跑的对话改名后提示里能立刻跟上（全量扫描的 30 秒缓存会一直显示旧标题）。
     */
    fun titleOf(workspace: String): String {
        if (workspace.isBlank()) return ""
        return try {
            ActivityFeed.titleOf(workspace)
        } catch (_: Throwable) {
            ""
        }
    }

    /** 提示里展示的项目名：占位文案（「当前项目」）能按 hash 反查到真名就换掉 */
    private fun displayProject(project: String, workspace: String): String {
        val name = project.trim()
        if (name.isNotEmpty() && name != "当前项目") return name
        if (workspace.isBlank()) return name
        return ProjectResolver.nameOf(workspace) ?: name
    }

    /**
     * 把「路径 / 项目名 / workspace hash」统一解析成「项目名 to workspace」。
     *
     * 顺序：hash 直认 → 活动项目名精确匹配 → recentProjects.xml 反查 → 路径算 hash。
     * **匹配不到只返回原名，不再回退到最近活跃的项目**——以前那句兜底会把
     * 别的项目的名字安在当前提示上，多开时看到的「项目 + 对话」就是错的。
     * 传进来的是认不出的绝对路径时返回空串：宿主给的 cwd 实测是 IDE 进程目录
     * （`C:\Users\<用户名>` 或安装目录），把末段当项目名等于把账本写成用户名。
     *
     * @return 项目名 to workspace（workspace 为空时 [whereText] 只显示项目名）
     */
    fun resolveWhere(cwd: String): Pair<String, String> {
        val raw = cwd.trim()
        if (raw.isEmpty()) return "" to ""
        val dir = raw.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        val active = try {
            activeProjects()
        } catch (_: Throwable) {
            emptyList()
        }

        // 1. 对方直接给了 workspace hash
        if (WORKSPACE_HASH.matches(raw)) {
            val hit = active.firstOrNull { it.workspace.equals(raw, ignoreCase = true) }
            return (hit?.project ?: ProjectResolver.nameOf(raw) ?: dir) to raw
        }

        // 2. 活动项目名精确匹配（日志文件名与页面注入都是项目名）
        active.firstOrNull {
            it.project.equals(dir, ignoreCase = true) || it.project.equals(raw, ignoreCase = true)
        }?.let { return it.project to it.workspace }

        // 3. recentProjects.xml 里登记过的项目：按名字反查 hash
        ProjectResolver.workspaceOfName(dir)?.let { ws ->
            return (ProjectResolver.nameOf(ws) ?: dir) to ws
        }

        // 4. 给的是完整路径：按 history 目录口径算 hash 再认一次
        if (isAbsolutePath(raw)) {
            val ws = ProjectResolver.hashOf(raw)
            ProjectResolver.nameOf(ws)?.let { return it to ws }
        }

        // 5. 认不出：给的是名字就保留名字，不硬套别的项目；
        //    给的是路径说明它不是任何已知项目，末段往往是用户名（`C:\Users\qfang`）
        //    或 IDE 安装目录，拿来当项目名会把账本写脏
        if (isAbsolutePath(raw)) return "" to ""
        return dir to ""
    }

    /** 绝对路径（Windows 盘符 / UNC / POSIX），用来区分「给的是路径」还是「给的是项目名」 */
    private fun isAbsolutePath(raw: String): Boolean =
        (raw.length > 2 && raw[1] == ':') || raw.startsWith("/") || raw.startsWith("\\\\")

    /** 默认放行的紧凑记录：上一条也是放行就原地更新，保持事件列表不被放行记录淹没 */
    private fun mergeAutoEvent(title: String, content: String) {
        val now = System.currentTimeMillis()
        synchronized(events) {
            val last = events.lastOrNull()
            if (last != null && last.type == "approval" && last.auto && now - last.at < AUTO_EVENT_MERGE_MS) {
                events[events.size - 1] = EventRecord(now, "approval", title, content, true)
                return
            }
            events.add(EventRecord(now, "approval", title, content, true))
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    private fun record(type: String, title: String, detail: String, auto: Boolean = false) {
        synchronized(events) {
            events.add(EventRecord(System.currentTimeMillis(), type, title, detail, auto))
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
        if (auto && type == "approval") countAutoApproval()
    }

    /** 去重表：key = 事件类型|业务键 */
    private val claims = HashMap<String, Long>()

    /**
     * 刚被 hook / 日志通道「默认放行」的那几次调用，按项目排队。
     *
     * HOOK 模式下的页面兜底代点会为同一张确认卡再记一次账（hook 的 allow 被宿主的
     * `user_setting_ask` 覆盖，页面才真正点掉「运行」），两条记录的键不同去不了重。
     * 这里按「项目 + 20 秒 + 先进先出」把它们对成一次，页面那条不再重复计数、不再写账本。
     */
    private val recentAutoAllow = HashMap<String, ArrayDeque<Long>>()

    /** 页面对账窗口：命令放行到页面点到同一张卡通常几百毫秒，20 秒足够宽 */
    private const val AUTO_ALLOW_DEDUPE_MS = 20_000L

    /** 记一次默认放行，等页面通道来认领 */
    private fun rememberAutoAllow(project: String) {
        val key = project.trim()
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(recentAutoAllow) {
            val queue = recentAutoAllow.getOrPut(key) { ArrayDeque() }
            queue.addLast(now)
            // 同一项目积压到 8 条说明对账已经失配，丢最旧的，别让窗口无限长
            while (queue.size > 8) queue.removeFirst()
            trimAutoAllow(queue, now)
        }
    }

    /**
     * 页面报上来的代点是否就是刚放过的那条命令。
     *
     * @return true 表示 hook / 日志通道已经记过账，本次不再重复计数
     */
    private fun consumeAutoAllow(project: String): Boolean {
        val key = project.trim()
        if (key.isEmpty()) return false
        val now = System.currentTimeMillis()
        synchronized(recentAutoAllow) {
            val queue = recentAutoAllow[key] ?: return false
            trimAutoAllow(queue, now)
            if (queue.isEmpty()) {
                recentAutoAllow.remove(key)
                return false
            }
            queue.removeFirst()
            if (queue.isEmpty()) recentAutoAllow.remove(key)
            return true
        }
    }

    private fun trimAutoAllow(queue: ArrayDeque<Long>, now: Long) {
        while (queue.isNotEmpty() && now - queue.first() > AUTO_ALLOW_DEDUPE_MS) queue.removeFirst()
    }

    /**
     * 最近一次真正弹出确认提示的时刻：key = workspace hash（缺失时项目名）。
     *
     * 给日志通道的**状态对账**兜底用：宿主进入 `waiting_user_input` 后若这里没有更新过，
     * 说明三条通道都没认出这次确认（文案漂移、并行清状态、启动时效边界），由兜底补一条只读提醒。
     */
    private val confirmNoticed = HashMap<String, Long>()

    /** 记一次确认提示已发出：任何通道（hook 气泡 / 页面 / 日志）都要调 */
    fun markConfirmNoticed(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        synchronized(confirmNoticed) { confirmNoticed[k] = System.currentTimeMillis() }
    }

    /** 该 workspace 最近一次确认提示的时刻；没有则为 0 */
    fun confirmNoticedAt(key: String): Long =
        synchronized(confirmNoticed) { confirmNoticed[key.trim()] ?: 0L }

    /** 限流记录：key = model|code */
    private val limits = LinkedHashMap<String, ModelLimit>()

    /** 最近一步的输出吞吐（日志通道按步写入） */
    @Volatile
    private var lastUsage: UsageSample? = null

    /** workspace hash -> 该项目最近一步的吞吐；状态栏按项目并排展示时各取各的读数 */
    private val usages = HashMap<String, UsageSample>()

    /** 最近一条实时样本（页面通道）；状态栏正文与 tooltip 都用它做「正在输出」的读数 */
    @Volatile
    private var lastStream: StreamSample? = null

    /** workspace hash -> 该项目最近一条实时样本 */
    private val streams = HashMap<String, StreamSample>()

    /** 实时速率的平滑值（字符/秒）：页面窗口只有几百毫秒，不平滑会比读数本身跳得更厉害 */
    private var smoothCps = 0.0

    /** 字符 -> token 换算系数，由步末真实 outputTokens 校准；初值按中英混排的经验值给 */
    @Volatile
    private var tokensPerChar = DEFAULT_TOKENS_PER_CHAR

    /**
     * 实时样本的新鲜度窗口。
     *
     * 页面 400ms 左右上报一次；这里留到 1.5 秒，是为了容忍旧版脚本（1 秒一写、不带速率字段）
     * 与新脚本交替写 `window.name` 造成的一两次空档，不让读数闪断。
     */
    private const val STREAM_TTL_MS = 1_500L

    private const val DEFAULT_TOKENS_PER_CHAR = 0.7

    private const val MIN_TOKENS_PER_CHAR = 0.05
    private const val MAX_TOKENS_PER_CHAR = 3.0

    /** 校准所需的最小字符量：太少时一次重渲染就能把系数带飞 */
    private const val MIN_CALIBRATE_CHARS = 300

    /** 字符桶宽度与数量：250ms × 600 = 150 秒，覆盖最长的单步耗时 */
    private const val CHARS_BUCKET_MS = 250L
    private const val CHARS_BUCKET_COUNT = 600

    /** 环形字符桶：桶号 = 时刻 / 250ms，用来按时间窗累计页面报上来的字符数 */
    private val charsBucketAt = LongArray(CHARS_BUCKET_COUNT)
    private val charsBucketSum = IntArray(CHARS_BUCKET_COUNT)

    /** 吞吐刷新的节流时刻：一轮对话可能有几十步，看板不必每步重建 */
    @Volatile
    private var lastUsageFireAt = 0L

    /** workspace hash -> 运行态；状态栏按 hash 读取，项目名只用于展示 */
    private val runtimes = HashMap<String, ProjectActivity>()

    /** 进行中的有效期：一步到下一步之间可能夹着长工具执行，留足余量 */
    private const val THINKING_TTL_MS = 60_000L

    /** 等确认的有效期：主人可能放着不动很久，超时只是兜底 */
    private const val WAITING_TTL_MS = 10 * 60_000L

    /** 上一条「默认放行」提示的播放时刻，用于最小间隔节流 */
    @Volatile
    private var lastAutoNoticeAt = 0L

    init {
        loadLimits()
        loadAutoCount()
    }

    // ---------------------------------------------------------------
    // 事件入口
    // ---------------------------------------------------------------

    /** 处理一条来自 [CbWebObserver] 的 DOM 事件；解析失败静默跳过 */
    fun onWebEvent(json: String) {
        val e = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return
        } catch (_: Throwable) {
            return
        }
        try {
            when (e.str("t")) {
                "approval" -> onApproval(
                    project = e.str("project").ifBlank { "当前项目" },
                    what = e.str("what").ifBlank { "一个操作需要确认" },
                    dangerous = e.bool("danger"),
                    auto = e.bool("auto"),
                    kind = e.str("kind").ifBlank { "once" },
                    via = e.str("via").ifBlank { "keyword" },
                    label = e.str("label"),
                    sessionId = e.str("sessionId"),
                    credit = e.bool("credit"),
                    // 页面注入的 workspace（面板所属项目）比按名字猜准得多
                    workspace = e.str("ws", "workspace"),
                )

                "rateLimit" -> onRateLimit(
                    // 页面通道带结构化 modelName / modelId，缺省时才退回「当前模型」
                    model = e.str("modelName", "model", "modelId").ifBlank { "当前模型" },
                    code = e.int("code") ?: 6004,
                    resetAt = e.long("resetAt") ?: 0L,
                )

                "interrupted" -> onInterrupted(
                    project = e.str("project").ifBlank { "当前项目" },
                    reason = e.str("what").ifBlank { "回复中断" },
                    auto = e.bool("auto"),
                    blocked = e.bool("blocked"),
                )

                "phrase" -> onPhrase(e.str("status"))
            }
        } catch (t: Throwable) {
            log.warn("处理 CodeBuddy 页面事件失败", t)
        }
    }

    /**
     * 日志通道的「等确认」事件。与 DOM 通道共用去重表：
     * DOM 已经提示过的弹窗不会再从日志提示一次，反之亦然。
     */
    fun onLogApproval(project: String, what: String, dangerous: Boolean, workspace: String = "") =
        onApproval(
            project = project, what = what, dangerous = dangerous, auto = false,
            // 日志通道只认得出「有人在等确认」，认不出卡片上的按钮，审计里如实标注
            kind = "none", via = "log", label = "", sessionId = "", credit = false,
            source = "log", workspace = workspace,
        )

    /**
     * 日志通道的**状态对账兜底**：宿主已进入 `waiting_user_input`，但宽限期内没有任何通道提示过。
     *
     * 与 [onLogApproval] 的区别是它**不看任何确认文案**——只认宿主自己的会话状态机
     * （`state: running → waiting_user_input`），所以宿主改文案、并行调用清掉状态、
     * 启动时错过事件这些情况下都能兜到。只提醒、不代点，卡片挂到主人自己处理完。
     */
    fun onLogWaitingFallback(project: String, workspace: String, waitedSince: Long) {
        val ws = workspace.trim()
        val where = whereText(displayProject(project, ws), ws).ifBlank { project }
        val minutes = ((System.currentTimeMillis() - waitedSince) / 60_000L).coerceAtLeast(0)
        val waited = if (minutes >= 1) "已等约 $minutes 分钟" else "刚开始等"
        PetAuditLog.approval(
            source = "log", auto = false, kind = "fallback", via = "state",
            label = "", what = "等待确认（状态对账）", project = where, sessionId = "",
            credit = false, danger = false,
        )
        PetApprovalHistory.record(
            project = where, title = titleOf(ws), tool = "状态对账",
            what = "有确认在等，桌宠没认出具体文案", auto = false, danger = false, source = "log",
        )
        PetService.getInstance().showManualNotice(
            "有确认在等主人",
            where,
            "CodeBuddy 停在「等确认」状态（$waited），但桌宠没认出这次确认的具体内容。",
            "桌宠不会猜着替主人点，请到 CodeBuddy 面板的确认卡里自己选；处理完这张卡会自动收起。",
        )
        // 兜底本身也算「提示过了」，避免 8 秒后重复补发
        markConfirmNoticed(ws.ifBlank { project })
        record("approval", "有确认在等主人", "「$where」状态对账兜底提醒", false)
    }

    /**
     * 宿主原生确认框被同进程探测器认出（[HostConfirmWatcher]）。
     *
     * 这是最后一道兜底：连日志与 `waiting_user_input` 都没有信号时，直接看那个 Swing 框本身。
     * 只提醒、不代点——原生的「运行 / 跳过 / 拒绝」插件点不到。
     */
    fun onHostDialogConfirm(dialogText: String) {
        val active = activeProjects().firstOrNull()
        val where = if (active == null) "当前项目"
        else whereText(active.project, active.workspace).ifBlank { active.project }
        PetAuditLog.approval(
            source = "dialog", auto = false, kind = "hostDialog", via = "swing",
            label = "", what = dialogText.take(160), project = where, sessionId = "",
            credit = false, danger = true,
        )
        PetService.getInstance().showManualNotice(
            "宿主在等主人确认",
            where,
            dialogText.take(160),
            "这是 CodeBuddy 自己弹的原生框「运行 / 跳过 / 拒绝」，桌宠点不到也不会替主人点，" +
                "请直接在那个框里选；选完这张卡自动收起。",
        )
        record("approval", "宿主在等主人确认", "「$where」原生确认框", false)
    }

    /** 日志通道的异常结束事件 */
    fun onLogInterrupted(project: String, reasons: List<String>) =
        onInterrupted(project, reasons.joinToString("、").ifBlank { "回复中断" }, auto = false)

    /** 日志通道识别到的模型限流（业务码 / 解封时间可能在日志里比页面更早出现） */
    fun onLogRateLimit(model: String, code: Int, resetAt: Long) =
        onRateLimit(model, code, resetAt)

    // ---------------------------------------------------------------
    // 分类处理 + 桌宠联动
    // ---------------------------------------------------------------

    private fun onApproval(
        project: String,
        what: String,
        dangerous: Boolean,
        auto: Boolean,
        kind: String,
        via: String,
        label: String,
        sessionId: String,
        credit: Boolean,
        source: String = "page",
        workspace: String = "",
    ) {
        // workspace 优先用通道给的（页面注入 / 日志文件名解析），没有再按项目名反查
        val ws = workspace.trim().ifBlank { resolveWhere(project).second }
        // 页面没注入成功时会传「当前项目」这种占位：能按 hash 反查到真名就换掉
        val displayName = displayProject(project, ws)
        // 逐次审计先写：扣费卡也要留痕——它不代点，但排查时要能确认确实被识别过
        PetAuditLog.approval(
            source = source, auto = auto, kind = kind, via = via, label = label,
            what = what, project = displayName, sessionId = sessionId,
            credit = credit, danger = dangerous,
        )
        // hook / 日志通道刚为同一条命令记过一次代点：宿主 Auto-run 关闭时 hook 的 allow
        // 不生效，真正点掉「运行」的是页面。这是同一次，不再计数也不再写账本，否则今日代点虚高。
        if (auto && !credit && consumeAutoAllow(displayName)) {
            markConfirmNoticed(ws.ifBlank { displayName })
            return
        }
        // 可查询的代点记录：结果等 PostToolUse 回填，没回填的显示「待回填」
        PetApprovalHistory.record(
            project = displayName,
            title = titleOf(ws),
            tool = label.ifBlank { if (credit) "扣费确认" else "页面确认" },
            what = what,
            auto = auto,
            danger = dangerous,
            source = source,
        )
        val settings = PetSettings.getInstance()
        // 扣费卡（积分 / 金额 / 余额）永远不代点；要不要提示由「扣费确认提示」开关决定，
        // 并跟随「权限提醒」总开关——总开关关掉时一律静默
        if (credit) {
            if (!settings.cbCreditNoticeEnabled || !settings.cbApproveNoticeEnabled) return
        }
        // 代点是替主人做决定，必须提示并留痕，不受「提醒」开关影响；
        // 危险命令即使不代点也要提示，否则关掉「权限提醒」后会静默卡在那里没人知道
        if (!credit && !auto && !dangerous && !settings.cbApproveNoticeEnabled) return
        if (!claim(if (auto) "approvalAuto" else "approval", "$project|$what")) return
        // 已经提示过这条确认：日志通道的状态对账据此判定「有人管了」，不再补兜底提醒
        markConfirmNoticed(ws.ifBlank { displayName })
        // 等待确认的提示同样要标出项目与对话标题，多开时才认得出是哪一条
        val where = whereText(displayName, ws).ifBlank { displayName }
        // 日志通道的危险行只可能来自宿主的 [SafetyRule]：它在 hook 之外独立判定，
        // 页面注入点不到那个原生 Swing 框，所以**一律**弹常驻卡，不受「页面会超时代点」影响。
        val hostSafety = dangerous && source == "log"
        // 没有任何通道会替主人点（扣费、宿主安全规则、设置成「一直问」的普通/危险确认）：
        // 一律弹**常驻只读卡**——写明为什么不代点，一直挂到主人自己点掉或宿主动作完成。
        // 12 秒就消失的普通提示在这类场景等于没提醒，主人回来只看到命令还卡着。
        if (!auto && (credit || hostSafety || !willAutoApprove(settings, dangerous, source))) {
            val (title, reason) = manualConfirmText(credit, dangerous, source)
            // 日志通道的正文前缀是「危险命令：」，卡片标题已经说了，这里去掉避免重复
            PetService.getInstance()
                .showManualNotice(title, where, what.removePrefix("危险命令："), reason)
            record("approval", title, "「$where」$what（桌宠不代点，等主人自己点）", auto)
            return
        }
        val (level, title) = when {
            // 回落到「本次会话内始终允许」是放大权限的操作，标题必须点明，别让主人以为是普通允许
            auto && kind == "session" -> PetNotifier.Level.INFO to "我先替主人点了「本次会话内始终允许」"
            auto -> PetNotifier.Level.INFO to "我先替主人点了「运行」"
            dangerous -> PetNotifier.Level.WARN to "有危险命令在等主人点头"
            else -> PetNotifier.Level.WARN to "CodeBuddy 在等主人点头"
        }
        val tail = when {
            auto && kind == "session" -> "$what（本会话内同类操作不再询问）"
            else -> what
        }
        val content = "「$where」$tail"
        PetNotifier.notify(level, title, content, durationMs = 12_000)
        record("approval", title, content, auto)
    }

    /**
     * 这一条确认还有没有通道会替主人点（直接放行或超时补刀）。
     *
     * 口径与 [CodeBuddyWatcher] 的 tick / [CbWebObserver] 保持一致：只有页面通道真的在用、
     * 且对应策略不是「一直问」时才有人点。hook 通道的确认由气泡自己处理，不在这里重复提醒。
     */
    private fun willAutoApprove(settings: PetSettings, dangerous: Boolean, source: String): Boolean {
        if (source == "hook") return true
        // 静默：非红线一律有人代点；红线是否代点取决于主人是否勾了「放开红线」。
        // 判断不了具体类别时用 dangerous 近似（页面通道的危险卡与红线卡高度重合）。
        // 「不干预」下不算数：那种模式没有任何通道会代点。
        if (SilentMode.isActive(settings) && settings.cbApprovalMode != CbApprovalMode.NONE) {
            return !dangerous || settings.cbSilentAllowRedline
        }
        val mode = ApprovalChannel.effective(settings)
        if (mode == CbApprovalMode.PAGE) {
            return if (dangerous) {
                settings.cbApprovalDanger != CbDangerPolicy.ASK
            } else {
                settings.cbApprovalNormal != CbNormalPolicy.ASK
            }
        }
        // HOOK 模式下页面只做「普通确认」的兜底代点（宿主 Auto-run 关闭时会弹那张卡）：
        // 普通确认要求普通档位是 ALLOW，危险卡仍只有 AUTO 才会被点，没有超时补刀通道。
        if (mode != CbApprovalMode.HOOK || !CbWebObserver.isConnected()) return false
        if (settings.cbApprovalNormal != CbNormalPolicy.ALLOW) return false
        return if (dangerous) settings.cbApprovalDanger == CbDangerPolicy.AUTO else true
    }

    /** 必须主人亲手点的确认：卡片标题 + 为什么不代点 */
    private fun manualConfirmText(credit: Boolean, dangerous: Boolean, source: String): Pair<String, String> = when {
        credit -> "有扣费确认在等主人点头" to
            "这笔确认会扣积分 / 金额，桌宠从不代点；请自己核对后再点「确认」，点完这张卡自动收起。"
        // 宿主 [SafetyRule] 在 hook 之外独立判定，hook 返回 allow 也豁免不了
        dangerous && source == "log" -> "宿主安全规则在等主人确认" to
            "这类命令由 CodeBuddy 自己的安全规则拦截，桌宠点「允许」也豁免不了，所以我不替你点。" +
                "请到 CodeBuddy 的「运行 / 跳过 / 拒绝」里自己选；选完这张卡自动收起。"
        dangerous -> "有危险命令在等主人点头" to
            "当前设置下危险命令不自动代点，桌宠只提醒、不替你点；请到 CodeBuddy 里自己选，选完这张卡自动收起。"
        else -> "CodeBuddy 在等主人点确认" to
            "当前设置下这类确认不自动代点，桌宠只提醒、不替你点；请到 CodeBuddy 里自己选，选完这张卡自动收起。"
    }

    private fun onRateLimit(model: String, code: Int, resetAt: Long) {
        val settings = PetSettings.getInstance()
        val key = "$model|$code"
        val now = System.currentTimeMillis()
        synchronized(limits) {
            limits[key] = ModelLimit(model, code, resetAt, now)
            limits.entries.removeIf { (_, v) ->
                (v.resetAt in 1 until now - LIMIT_KEEP_MS) || (v.resetAt <= 0L && now - v.at > LIMIT_KEEP_MS)
            }
            saveLimits()
        }
        // 记录已落库，看板的「模型频率限制」卡片需要立刻看到新的解封时间
        AccountEvents.fireRuntimeChanged()
        if (!settings.cbLimitNoticeEnabled) return
        if (!claim("rateLimit", "$key|$resetAt")) return
        val limit = ModelLimit(model, code, resetAt, now)
        val detail = limit.detail(now)
        PetNotifier.notify(PetNotifier.Level.WARN, "模型被限流了", detail, durationMs = 12_000)
        record("rateLimit", "模型被限流了", detail)
    }

    /**
     * 快捷短语投递回执（页面侧脚本回报）。
     *
     * 正常投递成功不额外弹提示——管理窗口在投递时已经反馈过一次；
     * 只有页面里找不到输入框 / 脚本异常才补一条警告，避免主人以为已经发出去了。
     */
    private fun onPhrase(status: String) {
        val (title, detail) = when (status) {
            "sent" -> "快捷短语已发送" to "CodeBuddy 输入框已提交"
            "filled" -> "快捷短语已填入" to "等主人确认后再发送"
            "noinput" -> "没找到 CodeBuddy 输入框" to "面板可能还没加载完，稍后再试"
            else -> "快捷短语投递失败" to "页面脚本执行异常，可重开 CodeBuddy 侧边栏再试"
        }
        record("phrase", title, detail)
        if (status == "noinput" || status == "error" || status.isBlank()) {
            PetNotifier.warn(title, detail)
        }
    }

    private fun onInterrupted(project: String, reason: String, auto: Boolean, blocked: Boolean = false) {
        // 自动继续同样是替主人动作，必须提示并留痕
        if (!auto && !blocked && !PetSettings.getInstance().cbProgressNoticeEnabled) return
        val claimType = when {
            auto -> "interruptedAuto"
            blocked -> "interruptedBlocked"
            else -> "interrupted"
        }
        if (!claim(claimType, "$project|$reason")) return
        if (blocked) {
            val content = "「$project」$reason（10 分钟内已自动续跑 5 次，先停一下）"
            PetNotifier.notify(
                PetNotifier.Level.WARN,
                "连续中断，已停止自动继续",
                content,
                durationMs = 12_000,
            )
            record("interrupted", "连续中断，已停止自动继续", content)
            return
        }
        val (level, title, content) = if (auto) {
            Triple(
                PetNotifier.Level.INFO,
                "会话中断，已替主人继续",
                "「$project」$reason，已自动点「继续」",
            )
        } else {
            Triple(
                PetNotifier.Level.WARN,
                "CodeBuddy 这轮没跑顺",
                "「$project」$reason，可能需要手动点「继续」",
            )
        }
        PetNotifier.notify(level, title, content)
        record("interrupted", title, content, auto)
    }

    /** 账号间会话迁移完成：由切号 / 批量同步流程调用，走桌宠反馈 */
    fun onHistoryMigrated(target: String, added: Int, copied: Int, failed: Int = 0) {
        val body = "已把其它账号的历史会话合并到 $target：新增 $added 个，复制消息目录 $copied 个" +
            (if (failed > 0) "，失败 $failed 个" else "") + "。重启 IDE 后侧边栏可见。"
        val title = if (failed > 0) "会话迁移部分失败" else "会话已迁移"
        record("history", title, body)
        if (!PetSettings.getInstance().cbHistoryNoticeEnabled) return
        if (failed > 0) {
            PetNotifier.warn(title, body)
        } else {
            PetNotifier.success(title, body)
        }
    }

    /** 成长计划 / 活跃状态查询结果：由成长中心调用，走桌宠反馈 */
    fun onGrowthStatus(summary: String) {
        record("growth", "成长计划活跃状态", summary)
        if (!PetSettings.getInstance().cbGrowthNoticeEnabled) return
        PetNotifier.notify(PetNotifier.Level.INFO, "成长计划活跃状态", summary)
    }

    /** 一轮对话正常完成：桌宠强提醒补上项目名、对话标题与「主人」后的正文摘要 */
    fun onConversationFinished(project: String, steps: Int) {
        record("growth", "对话完成", "「$project」共 $steps 步")
        if (!PetSettings.getInstance().cbProgressNoticeEnabled) return
        ActivityFeed.invalidate()
        ApplicationManager.getApplication().executeOnPooledThread {
            // 日志早于 index.json 落盘，稍等一拍才读得到刚结束的这轮
            try {
                Thread.sleep(800L)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }
            val item = ActivityFeed.recent(limit = 1, maxConversations = 4).firstOrNull()
            val detail = if (item == null) {
                "「$project」共 $steps 步，跑完了"
            } else {
                "「${item.project}」${item.title}：${item.excerpt}"
            }
            ApplicationManager.getApplication().invokeLater {
                PetNotifier.notify(
                    PetNotifier.Level.SUCCESS,
                    "对话完成啦，回来看看",
                    detail,
                    durationMs = 12_000,
                    highlight = true,
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // 实时输出（页面通道，估算口径）
    // ---------------------------------------------------------------

    /**
     * 页面通道报来一段窗口内的新增字符数。
     *
     * @param chars 该窗口内新出现在聊天区的字符数（已由页面脚本剔除按钮/状态栏等噪声）
     * @param ms 窗口宽度
     */
    fun onStreamChars(
        workspace: String,
        project: String,
        chars: Int,
        ms: Long,
        at: Long = System.currentTimeMillis(),
    ) {
        if (ms <= 0L) return
        if (chars > 0) addChars(chars, at)
        // 没有新增字符说明这一步的输出停了：清掉平滑值，让读数按 TTL 自然过期，
        // 但不要写一条 0 tok/s 的样本（那会让状态栏闪一下 0）
        if (chars <= 0) {
            smoothCps = 0.0
            return
        }
        val cps = chars * 1000.0 / ms
        smoothCps = if (smoothCps <= 0.0) cps else smoothCps * 0.55 + cps * 0.45
        val key = workspace.trim()
        val sample = StreamSample(
            workspace = key,
            project = project.trim(),
            tokensPerSecond = smoothCps * tokensPerChar,
            at = at,
        )
        lastStream = sample
        if (key.isNotEmpty()) {
            synchronized(streams) { streams[key] = sample }
        }
    }

    /** 最近一条仍在有效期内的实时样本；没有就是 null */
    fun recentStream(now: Long = System.currentTimeMillis()): StreamSample? =
        lastStream?.takeIf { isStreamFresh(it, now) }

    /** 指定项目的实时样本；状态栏多项目并排时各取各的 */
    fun streamOf(workspace: String, now: Long = System.currentTimeMillis()): StreamSample? {
        if (workspace.isBlank()) return null
        return synchronized(streams) { streams[workspace.trim()] }?.takeIf { isStreamFresh(it, now) }
    }

    /** 所有有效的实时样本，按时间倒序 */
    fun projectStreams(now: Long = System.currentTimeMillis()): List<StreamSample> =
        synchronized(streams) { streams.values.toList() }
            .filter { isStreamFresh(it, now) }
            .sortedByDescending { it.at }

    private fun isStreamFresh(sample: StreamSample, now: Long): Boolean =
        sample.tokensPerSecond > 0.0 && now - sample.at <= STREAM_TTL_MS

    /** 把一个窗口的字符数记到对应的时间桶里，供步末按耗时窗口回溯 */
    private fun addChars(chars: Int, at: Long) {
        val bucket = at / CHARS_BUCKET_MS
        val slot = ((bucket % CHARS_BUCKET_COUNT + CHARS_BUCKET_COUNT) % CHARS_BUCKET_COUNT).toInt()
        synchronized(charsBucketAt) {
            if (charsBucketAt[slot] != bucket) {
                charsBucketAt[slot] = bucket
                charsBucketSum[slot] = 0
            }
            charsBucketSum[slot] = charsBucketSum[slot] + chars
        }
    }

    /** 时间窗内累计的字符数；桶不存在按 0 算 */
    private fun charsBetween(from: Long, to: Long): Int {
        if (to <= from) return 0
        var total = 0
        val first = from / CHARS_BUCKET_MS
        val last = to / CHARS_BUCKET_MS
        synchronized(charsBucketAt) {
            var bucket = first
            while (bucket <= last) {
                val slot = ((bucket % CHARS_BUCKET_COUNT + CHARS_BUCKET_COUNT) % CHARS_BUCKET_COUNT).toInt()
                if (charsBucketAt[slot] == bucket) total += charsBucketSum[slot]
                bucket++
            }
        }
        return total
    }

    /**
     * 用一步的真实 outputTokens 校准「字符 -> token」系数。
     *
     * 窗口取 `[步结束时刻 - 步耗时, 步结束时刻]`：步耗时含工具执行与等待，那段时间页面不产字符，
     * 会让系数偏大（读数偏高）。因此只做慢速 EMA（0.25）并夹在合理区间内，
     * 靠多步平均把工具时间的偏差摊掉，而不是追求单步精确。
     */
    private fun calibrate(outputTokens: Int, durationMs: Long, at: Long) {
        if (outputTokens <= 0 || durationMs <= 0L) return
        val chars = charsBetween(at - durationMs, at)
        if (chars < MIN_CALIBRATE_CHARS) return
        val k = outputTokens.toDouble() / chars
        if (k < MIN_TOKENS_PER_CHAR || k > MAX_TOKENS_PER_CHAR) return
        tokensPerChar = tokensPerChar * 0.75 + k * 0.25
    }

    /** 最近一步的输出吞吐；没有样本时为 null */
    fun recentUsage(): UsageSample? = lastUsage

    /** 指定项目最近一步的吞吐；状态栏多项目展示用，时效由调用方判定 */
    fun usageOf(workspace: String): UsageSample? = synchronized(usages) { usages[workspace] }

    /** 所有项目的最近一步样本，按时间倒序 */
    fun projectUsages(): List<UsageSample> =
        synchronized(usages) { usages.values.sortedByDescending { it.at } }

    // ---------------------------------------------------------------
    // 运行态（状态栏的「思考中 / 等确认」）
    // ---------------------------------------------------------------

    /**
     * 记录一条运行态变化，由日志通道调用。
     *
     * 与提醒开关无关：状态栏在「仅统计」模式下也要能显示思考中，
     * 这里只写内存状态，不涉及代点与弹窗，没有副作用。
     */
    fun noteRuntime(workspace: String, project: String, phase: Phase) {
        val key = workspace.trim()
        if (key.isEmpty()) return
        val name = project.trim()
        synchronized(runtimes) {
            val prev = runtimes[key]
            runtimes[key] = ProjectActivity(
                workspace = key,
                project = name.ifEmpty { prev?.project.orEmpty() },
                phase = phase,
                at = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 刷新项目的最后活动时间（对话相关的日志行都会调）。
     *
     * 只在项目已经处于思考中 / 等确认时生效：没有对话证据的心跳行不能把
     * 空闲项目拉成「思考中」，但长工具执行期间能一直续上，不会中途掉状态。
     */
    fun touchRuntime(workspace: String, project: String) {
        val key = workspace.trim()
        if (key.isEmpty()) return
        val name = project.trim()
        synchronized(runtimes) {
            val prev = runtimes[key] ?: return
            if (prev.phase == Phase.IDLE) return
            runtimes[key] = prev.copy(
                project = name.ifEmpty { prev.project },
                at = System.currentTimeMillis(),
            )
        }
    }

    /** 项目当前的运行态；信号过期即视为空闲，避免异常退出后一直挂着「思考中」 */
    fun phaseOf(workspace: String, now: Long = System.currentTimeMillis()): Phase {
        val runtime = synchronized(runtimes) { runtimes[workspace.trim()] } ?: return Phase.IDLE
        return if (isFresh(runtime, now)) runtime.phase else Phase.IDLE
    }

    /** 所有仍在活动中的项目（思考中 / 等确认），按最近信号倒序；状态栏多项目展示用 */
    fun activeProjects(now: Long = System.currentTimeMillis()): List<ProjectActivity> =
        synchronized(runtimes) { runtimes.values.toList() }
            .filter { isFresh(it, now) }
            .sortedByDescending { it.at }

    /** 运行态是否还在有效期内；空闲态一律视为不新鲜 */
    private fun isFresh(runtime: ProjectActivity, now: Long): Boolean {
        val ttl = when (runtime.phase) {
            Phase.THINKING -> THINKING_TTL_MS
            Phase.WAITING -> WAITING_TTL_MS
            Phase.IDLE -> return false
        }
        return now - runtime.at <= ttl
    }

    /**
     * 日志通道：一步结束时的 usage 与步耗时。
     *
     * 只看数量级，不弹桌宠提示；看板读 [recentUsage]，状态栏按项目读 [usageOf]。
     */
    fun onLogUsage(workspace: String, project: String, model: String, outputTokens: Int, durationMs: Long) {
        if (outputTokens <= 0 || durationMs <= 0L) return
        val now = System.currentTimeMillis()
        // 步末是唯一拿得到真实 token 数的时刻：顺手校准页面通道的估算系数
        calibrate(outputTokens, durationMs, now)
        val key = workspace.trim()
        val sample = UsageSample(
            model = model.trim().ifBlank { "当前模型" },
            outputTokens = outputTokens,
            durationMs = durationMs,
            at = now,
            workspace = key,
            project = project.trim(),
        )
        lastUsage = sample
        if (key.isNotEmpty()) {
            synchronized(usages) { usages[key] = sample }
        }
        if (now - lastUsageFireAt < 10_000L) return
        lastUsageFireAt = now
        AccountEvents.fireRuntimeChanged()
    }

    /** 当前仍然有效（未过解封时间太久）的模型限制记录 */
    fun activeLimits(): List<ModelLimit> = synchronized(limits) {
        val now = System.currentTimeMillis()
        limits.values.filter { it.resetAt <= 0L || it.resetAt > now - LIMIT_KEEP_MS }.toList()
    }

    /** 桌宠 / 设置页用的一句话概览 */
    fun limitsSummary(): String = activeLimits().joinToString("；") { it.detail() }

    // ---------------------------------------------------------------
    // 去重与持久化
    // ---------------------------------------------------------------

    /** 抢占一次提示权；同 key 在窗口内只有第一个事件源能提示 */
    fun claim(type: String, key: String): Boolean = synchronized(claims) {
        val now = System.currentTimeMillis()
        if (claims.size > 512) claims.entries.removeIf { (_, at) -> now - at > CLAIM_WINDOW_MS }
        val prev = claims["$type|$key"]
        if (prev != null && now - prev < CLAIM_WINDOW_MS) return false
        claims["$type|$key"] = now
        true
    }

    private fun saveAutoCount() {
        try {
            val props = PropertiesComponent.getInstance()
            props.setValue(AUTO_DAY_KEY, autoDay)
            props.setValue(AUTO_COUNT_KEY, autoCount.toString())
        } catch (t: Throwable) {
            log.warn("保存今日代点次数失败", t)
        }
    }

    private fun loadAutoCount() {
        try {
            val props = PropertiesComponent.getInstance()
            autoDay = props.getValue(AUTO_DAY_KEY) ?: ""
            autoCount = props.getValue(AUTO_COUNT_KEY)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            log.warn("读取今日代点次数失败", t)
        }
    }

    private fun saveLimits() {
        try {
            val array = JsonArray()
            synchronized(limits) {
                for (v in limits.values) {
                    array.add(JsonObject().apply {
                        addProperty("model", v.model)
                        addProperty("code", v.code)
                        addProperty("resetAt", v.resetAt)
                        addProperty("at", v.at)
                    })
                }
            }
            PropertiesComponent.getInstance().setValue(STORE_KEY, array.toString())
        } catch (t: Throwable) {
            log.warn("保存模型限流记录失败", t)
        }
    }

    private fun loadLimits() {
        try {
            val raw = PropertiesComponent.getInstance().getValue(STORE_KEY) ?: return
            val array = JsonParser.parseString(raw).takeIf { it.isJsonArray }?.asJsonArray ?: return
            val now = System.currentTimeMillis()
            synchronized(limits) {
                for (el in array) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val at = o.long("at") ?: 0L
                    if (now - at > 24 * 60 * 60_000L) continue
                    val model = o.str("model")
                    val code = o.int("code") ?: 6004
                    if (model.isBlank()) continue
                    limits["$model|$code"] = ModelLimit(model, code, o.long("resetAt") ?: 0L, at)
                }
            }
        } catch (t: Throwable) {
            log.warn("读取模型限流记录失败", t)
        }
    }

    // ---------------------------------------------------------------
    // Gson 取值小工具
    // ---------------------------------------------------------------

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = get(k) ?: continue
            if (v.isJsonPrimitive && v.asJsonPrimitive.isString) return v.asString.trim()
        }
        return ""
    }

    private fun JsonObject.int(key: String): Int? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.long(key: String): Long? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.bool(key: String): Boolean = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    } catch (_: Throwable) {
        false
    }
}
