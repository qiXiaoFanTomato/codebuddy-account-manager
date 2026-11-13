package dev.codebuddy.account.pet

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CodeBuddy 运行日志监控：把「等你确认 / 这轮结束 / 这轮没跑顺」翻译成桌宠提示。
 *
 * 信号来自 CodeBuddy 自己的日志（实测 4.12.42888899，Windows）：
 *   %LOCALAPPDATA%\CodeBuddyExtension\Logs\JetBrains\<yyyy-MM-dd>\<项目名>__<hash>.log
 *
 *  - 等待确认：[beforeExecute] User confirmation required (source: xxx)
 *             [call_xxx]  Hook 请求用户确认，转入等待确认状态
 *  - 危险命令：[SafetyRule] Dangerous command detected: <命令原文>
 *  - 已确认：  [call_xxx]  收到用户确认，进入流式执行状态
 *  - 一轮结束：[BaseAgent:craft] [id]  notifyAllStepsEnd, totalSteps: N, ... isMaxStepLimit: false ...
 *
 * 只读增量：每个文件记录已读偏移量与未完成的半行，首轮直接从文件末尾开始，不回放历史日志。
 * 日志文案不是公开契约，因此这里只作**兜底通道**：主通道是 [CbWebObserver] 的页面 DOM 观察，
 * 两路事件在 [CbEventCenter] 汇合去重，任一路认出弹窗 / 限流 / 中断都能提示，避免漏提示。
 * 任何异常都不影响主流程。
 *
 * 生命周期跟随桌宠窗口：由 [PetService] 在窗口创建后 [start]，隐藏 / 销毁时 [stop]。
 */
@Service(Service.Level.APP)
class CodeBuddyWatcher : Disposable {

    private val log = logger<CodeBuddyWatcher>()

    /** 单个日志文件的读取进度；[carry] 保存尚未凑齐换行的尾部字节，避免半行被整行丢弃 */
    private class FileState(var offset: Long, var carry: ByteArray = ByteArray(0))

    /** 一条等待确认事件（callId 缺失时用 orphan:<project> 作为临时键） */
    private class PendingAsk(
        val project: String,
        val what: String,
        val at: Long,
        /** 被判定为危险操作（危险命令 / 安全规则拦截）时不自动同意 */
        val dangerous: Boolean = false,
    ) {
        /** 是否已提示过主人 */
        var notified: Boolean = false

        /** 是否已尝试替主人点过同意（只点一次） */
        var approveTried: Boolean = false

        /** 注入点击脚本的时间（0 表示没点过） */
        var approveAt: Long = 0L

        /** 是否已核对过点击结果 */
        var approveVerified: Boolean = false
    }

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-log-watch").apply { isDaemon = true }
        }

    private var task: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

    /**
     * 仅采集吞吐样本模式：状态栏的 tok/s 读数打开而桌宠关闭时使用。
     *
     * 该模式下 [tick] 不判权限确认、不做代点与自动继续，只解析 `Step execution metrics`，
     * 避免「桌宠已关掉却仍在替主人点同意」。桌宠后续启动时 [start] 会把它切回全量模式。
     */
    private val metricsOnly = AtomicBoolean(false)

    /** 日志路径 -> 已读偏移 */
    private val states = HashMap<String, FileState>()

    /** callId -> 待提示（等一拍确认不是自动确认后再提示） */
    private val pending = HashMap<String, PendingAsk>()

    /** 只有 `User confirmation required`、还没拿到 callId 的等待事件，key = project */
    private val orphanAsks = HashMap<String, PendingAsk>()

    /** 项目 -> (危险命令原文, 时间)，用于补充提示正文 */
    private val dangerByProject = HashMap<String, Pair<String, Long>>()

    /** 项目 -> (宿主自动放行判定时间, 判定行的业务标签)，用于把「默认放行」翻译成桌宠提示 */
    private val autoAllowed = HashMap<String, Pair<Long, String>>()

    /** 项目 -> (最近一次实际调用的模型名, 时间)，用于补全限流与吞吐文案 */
    private val recentModel = HashMap<String, Pair<String, Long>>()

    /** 已处理过的事件键（callId / requestId），带容量上限的 LRU */
    private val handled = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_HANDLED_KEYS
    }

    /** 启动监控（幂等）；重新启动会重建基线，不补发启动前的历史事件 */
    fun start() = startInternal(onlyMetrics = false)

    /** 只采集吞吐样本（状态栏读数用）：不判确认、不代点、不自动继续 */
    fun startMetricsOnly() = startInternal(onlyMetrics = true)

    /** 状态栏速度读数关闭时释放「仅统计」实例；桌宠的全量监控不受影响 */
    fun stopMetricsOnly() {
        if (!metricsOnly.get()) return
        stop()
    }

    private fun startInternal(onlyMetrics: Boolean) {
        if (!running.compareAndSet(false, true)) {
            // 已在运行：只允许「仅统计 → 全量」的升级（桌宠接管），不允许反向降级，
            // 否则状态栏的读数组件会把桌宠的确认 / 中断通道掐掉
            if (!onlyMetrics) metricsOnly.set(false)
            return
        }
        metricsOnly.set(onlyMetrics)
        synchronized(states) {
            states.clear()
            pending.clear()
            orphanAsks.clear()
            dangerByProject.clear()
            autoAllowed.clear()
            recentModel.clear()
        }
        task = executor.scheduleWithFixedDelay(
            { tickQuietly() },
            FIRST_DELAY_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 停止监控并清空中间状态 */
    fun stop() {
        running.set(false)
        metricsOnly.set(false)
        task?.cancel(false)
        task = null
        synchronized(states) {
            states.clear()
            pending.clear()
            orphanAsks.clear()
            dangerByProject.clear()
            autoAllowed.clear()
            recentModel.clear()
        }
    }

    override fun dispose() {
        stop()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------
    // 轮询
    // ---------------------------------------------------------------

    private fun tickQuietly() {
        try {
            tick()
        } catch (e: Exception) {
            log.warn("CodeBuddy 日志监控异常", e)
        }
    }

    private fun tick() {
        if (!running.get()) return
        val settings = try {
            PetSettings.getInstance()
        } catch (_: Exception) {
            return
        }
        // 只要任一相关能力开启就继续收集事件，避免「关提醒」连带关掉代点 / 自动继续；
        // 「仅统计吞吐」模式（状态栏读数）一律不判事件，防止桌宠关掉后仍替主人点同意
        val onlyMetrics = metricsOnly.get()
        val approvalActive = !onlyMetrics &&
            (settings.cbApproveNoticeEnabled || settings.cbApprovalMode != CbApprovalMode.NONE)
        val interruptActive = !onlyMetrics &&
            (settings.cbProgressNoticeEnabled || settings.cbAutoContinueEnabled)
        // 三个通道互斥：桌宠弹窗接管时，页面代点与日志补刀一律让位，避免同一个确认被处理两次
        val pageActive = settings.cbApprovalMode == CbApprovalMode.PAGE
        // 页面「直接放行」已经接管时，超时补刀不再重复点同一个按钮
        val noDisturbActive =
            pageActive && settings.cbApprovalNormal == CbNormalPolicy.ALLOW && CbWebObserver.isConnected()
        val autoApproveOn = pageActive && settings.cbApprovalNormal == CbNormalPolicy.ASK_THEN_ALLOW
        // 页面已经直接点过时不再超时补刀，避免同一个确认被处理两次
        val autoApproveDangerOn =
            pageActive && settings.cbApprovalDanger == CbDangerPolicy.AUTO && !noDisturbActive
        val autoApproveMs = settings.cbAutoApproveMinutes * 60_000L
        // 「默认放行」提示：只在实际生效的直接放行策略下播报，避免把宿主原生弹框也算成代点
        val autoAllowNotice = !onlyMetrics && settings.cbApprovalMode != CbApprovalMode.NONE &&
            settings.cbApprovalNormal == CbNormalPolicy.ALLOW
        val files = candidateFiles()
        // 不再出现在候选集里的旧文件（跨天轮转）及时释放读取进度
        if (files.isNotEmpty()) {
            val live = files.mapTo(HashSet()) { it.second.toString() }
            synchronized(states) { states.keys.retainAll(live) }
        }
        // 两个开关都关时仍然推进偏移量，重新打开后不会补发旧事件
        for ((project, path) in files) {
            try {
                readNewLines(project, path, approvalActive, interruptActive, autoAllowNotice)
            } catch (_: Exception) {
                // 单文件读取失败不影响其它文件
            }
        }
        if (approvalActive) {
            processAsks(autoApproveOn, autoApproveDangerOn, autoApproveMs)
        } else {
            clearAsks()
        }
    }

    /** 今天与昨天的日志（跨零点轮转时仍能读到最后一段） */
    private fun candidateFiles(): List<Pair<String, Path>> {
        val root = logRoot() ?: return emptyList()
        val out = ArrayList<Pair<String, Path>>()
        for (date in listOf(LocalDate.now(), LocalDate.now().minusDays(1))) {
            val dir = root.resolve(date.format(DATE_FORMAT))
            if (!Files.isDirectory(dir)) continue
            try {
                Files.newDirectoryStream(dir, "*.log").use { stream ->
                    for (p in stream) out.add(projectOf(p) to p)
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun readNewLines(
        project: String,
        path: Path,
        approveOn: Boolean,
        progressOn: Boolean,
        autoAllowNotice: Boolean,
    ) {
        val key = path.toString()
        val size = try {
            Files.size(path)
        } catch (_: Exception) {
            return
        }
        val state = synchronized(states) {
            states.getOrPut(key) {
                // 首次见到该文件：从末尾开始，只关注新写入的内容
                FileState(size)
            }
        }
        if (size < state.offset) {
            // 文件被重建 / 截断
            state.offset = 0
            state.carry = ByteArray(0)
        }
        if (size <= state.offset) return

        val limit = (size - state.offset).coerceAtMost(MAX_BYTES_PER_TICK.toLong()).toInt()
        val bytes = ByteArray(limit)
        try {
            RandomAccessFile(path.toFile(), "r").use { raf ->
                raf.seek(state.offset)
                raf.readFully(bytes)
            }
        } catch (_: Exception) {
            return
        }
        // 拼接上一轮的半行；只解析到最后一个换行，剩余字节留到下一轮
        val chunk = state.carry + bytes
        val lastNl = chunk.lastIndexOf('\n'.code.toByte())
        state.offset += limit
        if (lastNl < 0) {
            state.carry = if (chunk.size > MAX_CARRY_BYTES) {
                chunk.copyOfRange(chunk.size - MAX_CARRY_BYTES, chunk.size)
            } else {
                chunk
            }
            return
        }
        val text = String(chunk, 0, lastNl + 1, StandardCharsets.UTF_8)
        state.carry = if (lastNl + 1 < chunk.size) {
            chunk.copyOfRange(lastNl + 1, chunk.size)
        } else {
            ByteArray(0)
        }
        // 不再截断行数：一次写入超过上限时，中间的行也不能丢
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            try {
                handleLine(project, line, approveOn, progressOn, autoAllowNotice)
            } catch (_: Exception) {
                // 单行解析失败忽略
            }
        }
    }

    // ---------------------------------------------------------------
    // 行解析
    // ---------------------------------------------------------------

    private fun handleLine(
        project: String,
        line: String,
        approveOn: Boolean,
        progressOn: Boolean,
        autoAllowNotice: Boolean,
    ) {
        val now = System.currentTimeMillis()

        // 最近使用的模型名：限流与吞吐文案都靠它补全，日志比页面文本更可靠
        MODEL_NAME.find(line)?.groupValues?.get(1)?.let { model ->
            synchronized(states) { recentModel[project] = model to now }
        }

        // 一步结束：输出 token 与步耗时 -> 最近一步的吞吐（看板展示，不弹提示）
        if (line.contains("Step execution metrics")) {
            val outputTokens = OUTPUT_TOKENS.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val duration = STEP_DURATION.find(line)?.groupValues?.get(1)?.toLongOrNull()
            if (outputTokens != null && duration != null) {
                CbEventCenter.onLogUsage(recentModelOf(project) ?: "当前模型", outputTokens, duration)
            }
        }

        // 宿主自己放行（needConfirm=false）：先记一拍，紧随其后的命令原文用来告诉主人刚跑过什么
        if (AUTO_ALLOW_HINT.containsMatchIn(line)) {
            if (autoAllowNotice) {
                val tag = tagOf(line)
                synchronized(states) { autoAllowed[project] = now to tag }
            }
            return
        }

        // 默认放行的终端命令原文：`命令: "<命令>", isWatchCommand: false`
        // 同一行还会派生出「执行普通命令: ...」，只认前一条，避免一次调用提示两次
        if (autoAllowNotice && line.contains("命令: \"") && !line.contains("执行普通命令")) {
            val tag = tagOf(line)
            val allowed = synchronized(states) {
                val entry = autoAllowed[project]
                if (entry != null && entry.second == tag && now - entry.first <= AUTO_ALLOW_BIND_MS) {
                    autoAllowed.remove(project)
                    true
                } else {
                    false
                }
            }
            val command = COMMAND_TEXT.find(line)?.groupValues?.get(1)
            if (allowed && !command.isNullOrBlank()) {
                CbEventCenter.onLogAutoApproval("命令", oneLine(command, MAX_COMMAND_CHARS))
            }
            return
        }

        // 模型频率限制 / 用量限制：业务码与解封时间往往先出现在日志里
        if (LIMIT_HINT.containsMatchIn(line)) {
            val code = when {
                line.contains("6004") || line.contains("频率限制") -> 6004
                line.contains("6000") || line.contains("用量限制") -> 6000
                line.contains("14018") || line.contains("额度不足") -> 14018
                else -> 6004
            }
            // 限流行本身往往不带模型名，用最近一次实际调用的模型补全
            val model = MODEL_ID.find(line)?.groupValues?.get(1)
                ?: recentModelOf(project) ?: "当前模型"
            val resetAt = parseResetAt(line)
            if (CbEventCenter.claim("rateLimitLog", "$project|$code|$resetAt|${line.take(60)}")) {
                CbEventCenter.onLogRateLimit(model, code, resetAt)
            }
            return
        }

        // 危险命令原文：补充提示正文，并留一条兜底待提示（弹窗日志可能不写「等待确认」）
        if (line.contains("Dangerous command detected:")) {
            val cmd = line.substringAfter("Dangerous command detected:").trim()
            if (cmd.isNotBlank()) {
                synchronized(states) {
                    dangerByProject[project] = cmd to now
                    if (approveOn) {
                        val key = "danger:$project:${now / 2_000L}"
                        if (!pending.containsKey(key)) {
                            pending[key] = PendingAsk(
                                project, "危险命令：" + oneLine(cmd, MAX_COMMAND_CHARS), now, dangerous = true,
                            )
                        }
                    }
                }
            }
            return
        }

        // 等待主人确认：文案集合放宽，且不再强制要求 callId
        if (ASK_HINT.containsMatchIn(line)) {
            if (!approveOn) return
            val id = callIdOf(line) ?: "line:$project:${line.hashCode()}:${now / 2_000L}"
            if (handled.containsKey(id)) return
            markHandled(id)
            val dangerous = dangerText(project) != null
            synchronized(states) {
                orphanAsks.remove(project)
                val duplicated = pending.values.any { it.project == project && now - it.at < 5_000L }
                if (!duplicated) pending[id] = PendingAsk(project, describe(project, line), now, dangerous)
            }
            return
        }

        // 主人确认 / 拒绝：撤销待提示（含危险命令 / 无 callId 的兜底项）
        if (CONFIRM_HINT.containsMatchIn(line)) {
            val id = callIdOf(line)
            synchronized(states) {
                if (id != null) pending.remove(id)
                // 兜底项没有 callId，只能按项目前缀清，否则「已确认」行撤不掉刚入队的提示
                pending.entries.removeIf {
                    it.key.startsWith("danger:$project:") || it.key.startsWith("line:$project:")
                }
                orphanAsks.remove(project)
            }
            if (id != null) markHandled(id)
            return
        }

        // 一轮结束（含异常标志位）；requestId 缺失时用时间窗口兜底，不能直接丢整行。
        // 只认主 agent 的整行结构：子 agent（code-explorer / web-fetch / summary）也会打
        // notifyAllStepsEnd，工具回显（搜索结果 / 命令原文 / 终端输出）里同样会出现这个字样，
        // 只按 contains 判定会在主对话仍在执行时误报「对话完成」。
        if (ROUND_END.containsMatchIn(line)) {
            if (!progressOn) return
            val requestId = REQUEST_ID.find(line)?.groupValues?.get(1) ?: "auto:$project:${now / 2_000L}"
            val key = "req:$requestId"
            if (handled.containsKey(key)) return
            markHandled(key)
            val steps = STEPS.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val reasons = abnormalReasons(line)
            if (reasons.isEmpty()) {
                // 强提醒里补上项目名、对话标题与内容摘要（桌宠专用动画由 PetNotifier 承载）
                CbEventCenter.onConversationFinished(project, steps)
            } else {
                CbEventCenter.onLogInterrupted(project, reasons)
                if (PetSettings.getInstance().cbAutoContinueEnabled) CbWebObserver.clickContinue()
            }
        }
    }

    /** 从日志里解析官方限流文案的解封时刻；解析不出来返回 0（由页面通道补齐） */
    private fun parseResetAt(line: String): Long {
        val stamp = RESET_TS.find(line)?.groupValues?.get(1)?.toLongOrNull()
        if (stamp != null) return if (stamp < 1_000_000_000_000L) stamp * 1000L else stamp
        val raw = RESET_AT.find(line)?.groupValues?.get(1)?.replace(' ', 'T') ?: return 0L
        val zoned = if (Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$").matches(raw)) {
            "$raw+08:00"
        } else {
            raw
        }
        return try {
            java.time.OffsetDateTime.parse(zoned).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    /** 异常结束的原因（notifyAllStepsEnd 行里的标志位，实测字段名） */
    private fun abnormalReasons(line: String): List<String> {
        val out = ArrayList<String>()
        if (line.contains("isMaxTokenLimit: true")) out.add("达到 token 上限")
        if (line.contains("isMaxStepLimit: true")) out.add("达到步数上限")
        if (line.contains("isContentFilter: true")) out.add("内容被过滤")
        if (line.contains("isEmptyToolCallsStuck: true")) out.add("工具调用卡住")
        if (line.contains("isNoContentStuck: true")) out.add("输出为空卡住")
        if (line.contains("isDuplicateToolCallLimit: true")) out.add("重复调用超限")
        if (line.contains("isEmptyResponse: true")) out.add("返回为空")
        return out
    }

    /**
     * 处理等待中的确认事件：
     * - 超过宽限期提示一次（自动确认的行会在同一批日志里撤销，不会进入 pending）；
     * - 页面代点通道下，策略含「超时放行」的普通 / 危险命令到点替主人点一次「允许」；
     * - 一小时仍未确认的事件直接丢弃，避免状态无限累积。
     */
    private fun processAsks(autoApproveOn: Boolean, autoApproveDangerOn: Boolean, autoApproveMs: Long) {
        val now = System.currentTimeMillis()
        val callAsks = ArrayList<Pair<String, PendingAsk>>()
        val readyOrphans = ArrayList<PendingAsk>()
        synchronized(states) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value.at > ASK_EXPIRE_MS) {
                    it.remove()
                    continue
                }
                callAsks.add(e.key to e.value)
            }
            val oit = orphanAsks.entries.iterator()
            while (oit.hasNext()) {
                val e = oit.next()
                if (now - e.value.at > ORPHAN_TTL_MS) {
                    oit.remove()
                    continue
                }
                if (now - e.value.at >= ORPHAN_GRACE_MS) {
                    readyOrphans.add(e.value)
                    oit.remove()
                }
            }
        }
        for ((id, ask) in callAsks) {
            if (!ask.notified && now - ask.at >= CONFIRM_GRACE_MS) {
                ask.notified = true
                showAsk(ask)
            }
            val mayAutoApprove = if (ask.dangerous) autoApproveDangerOn else autoApproveOn
            if (mayAutoApprove && !ask.approveTried &&
                autoApproveMs in 60_000L..ASK_EXPIRE_MS && now - ask.at >= autoApproveMs
            ) {
                ask.approveTried = true
                autoApprove(ask)
            }
            // 点完 10 秒还没等到「收到用户确认」，说明按钮没点中，提醒主人自己看
            if (!ask.approveVerified && ask.approveAt > 0 && now - ask.approveAt >= APPROVE_VERIFY_MS) {
                ask.approveVerified = true
                PetNotifier.notify(
                    PetNotifier.Level.WARN,
                    "替主人点了同意，但好像没生效",
                    "「${ask.project}」${ask.what}：请到 CodeBuddy 面板自己点一下",
                )
            }
        }
        // 孤儿事件没有 callId，不做长期去重，否则同项目后续事件会被永久拦截
        for (ask in readyOrphans) showAsk(ask)
    }

    /** 超时替主人点同意；不校验点击结果，由后续日志的「收到用户确认」自然收尾 */
    private fun autoApprove(ask: PendingAsk) {
        val minutes = ((System.currentTimeMillis() - ask.at) / 60_000L).coerceAtLeast(1)
        ask.approveAt = System.currentTimeMillis()
        val injected = try {
            CodeBuddyApprover.clickAllow(ask.project)
        } catch (e: Exception) {
            log.warn("自动同意失败", e)
            false
        }
        if (injected) {
            CbEventCenter.countAutoApproval()
            PetNotifier.notify(
                PetNotifier.Level.INFO,
                "我先替主人点了同意",
                "「${ask.project}」${ask.what}（等了 $minutes 分钟）",
            )
        } else {
            PetNotifier.notify(
                PetNotifier.Level.WARN,
                "想替主人点同意，但没找到 CodeBuddy 面板",
                "「${ask.project}」请打开对话面板或自己点一下",
            )
        }
    }

    private fun showAsk(ask: PendingAsk) {
        // 与页面 DOM 通道共用去重：谁先认出弹窗谁提示，不会重复打扰主人
        CbEventCenter.onLogApproval(ask.project, ask.what, ask.dangerous)
    }

    private fun clearAsks() {
        synchronized(states) {
            pending.clear()
            orphanAsks.clear()
        }
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private fun markHandled(key: String) {
        synchronized(states) { handled[key] = true }
    }

    /** 该项目最近一次实际调用的模型名；超过时效视为未知 */
    private fun recentModelOf(project: String): String? {
        val entry = synchronized(states) { recentModel[project] } ?: return null
        return if (System.currentTimeMillis() - entry.second <= MODEL_TTL_MS) entry.first else null
    }

    /** 30 秒内该项目刚被判定的危险命令原文；没有则为 null */
    private fun dangerText(project: String): String? {
        val danger = synchronized(states) { dangerByProject[project] } ?: return null
        return if (System.currentTimeMillis() - danger.second <= DANGER_TTL_MS) danger.first else null
    }

    /** 提示正文：优先用危险命令原文，否则按日志行首标签推断操作类型 */
    private fun describe(project: String, line: String): String {
        val danger = dangerText(project)
        if (!danger.isNullOrBlank()) {
            return "危险命令：" + oneLine(danger, MAX_COMMAND_CHARS)
        }
        val tag = tagOf(line)
        return when {
            tag.startsWith("Tool:") -> "工具 " + tag.removePrefix("Tool:")
            tag.equals("TerminalExecutor", ignoreCase = true) -> "一条终端命令"
            tag.isNotBlank() -> "$tag 操作"
            else -> "一个操作"
        }
    }

    /** 日志行形如 `[时间] [Info] [TerminalExecutor]  正文`，取第三个方括号作为业务标签 */
    private fun tagOf(line: String): String {
        val all = ArrayList<String>()
        for (m in BRACKET.findAll(line)) {
            all.add(m.groupValues[1])
            if (all.size >= 4) break
        }
        return all.drop(2).firstOrNull { it.isNotBlank() && !it.contains(' ') } ?: ""
    }

    private fun callIdOf(line: String): String? = CALL_ID.find(line)?.groupValues?.get(1)

    private fun oneLine(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }

    private fun logRoot(): Path? {
        val local = System.getenv("LOCALAPPDATA") ?: return null
        val dir = Paths.get(local, "CodeBuddyExtension", "Logs", "JetBrains")
        return if (Files.isDirectory(dir)) dir else null
    }

    /** 日志文件名格式：<项目名>__<workspaceHash>.log */
    private fun projectOf(path: Path): String {
        val name = path.fileName?.toString()?.removeSuffix(".log") ?: return "当前项目"
        val project = name.substringBefore("__").ifBlank { "当前项目" }
        return project
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** 轮询间隔：日志是实时追加的，2 秒足够且开销可忽略 */
        private const val POLL_INTERVAL_MS = 2_000L
        private const val FIRST_DELAY_MS = 3_000L

        /** 单次单文件最多读取的字节数 */
        private const val MAX_BYTES_PER_TICK = 512 * 1024

        /** 尚未凑齐换行的尾部字节上限（超长单行日志保护） */
        private const val MAX_CARRY_BYTES = 64 * 1024

        /** 确认事件的最小等待时间：短于它的多为自动确认 */
        private const val CONFIRM_GRACE_MS = 2_500L

        /** 只有「需要确认」没有 callId 的行，等待绑定的上限 */
        private const val ORPHAN_GRACE_MS = 5_000L
        private const val ORPHAN_TTL_MS = 30_000L

        /** 危险命令原文的时效 */
        private const val DANGER_TTL_MS = 30_000L

        /** 自动放行判定与实际命令行的最大间隔：两行通常同一拍写入，留几秒余量 */
        private const val AUTO_ALLOW_BIND_MS = 10_000L

        /** 最近模型名的时效：半小时内出现的调用都算「当前模型」 */
        private const val MODEL_TTL_MS = 30 * 60_000L

        /** 等待确认事件的最长保留时间（超过即丢弃，避免状态累积） */
        private const val ASK_EXPIRE_MS = 60 * 60_000L

        /** 替主人点击后，等多久去核对是否生效 */
        private const val APPROVE_VERIFY_MS = 10_000L

        private const val MAX_COMMAND_CHARS = 80
        private const val MAX_HANDLED_KEYS = 512

        /**
         * 主 agent 的一轮结束行，实测形如：
         * `[2026-11-13 17:26:12.973] [Info] [BaseAgent:craft] [<32位requestId>]  notifyAllStepsEnd, totalSteps: 21, ...`
         *
         * 必须整行匹配（日志前缀 + `BaseAgent:craft` + requestId + 正文以 notifyAllStepsEnd 开头）：
         * 实测子 agent（`BaseAgent:code-explorer` / `web-fetch` / `summary`）也会打同样的结束行，
         * 而主 agent 此时仍在跑；用 `SearchContent` / `StreamParser` / `TerminalExecutor` /
         * `StandaloneTerminalProcess` 回显的工具参数与输出里也会出现这个字样。两者都曾造成
         * 「提示完成了，回到对话却还在执行」。
         */
        private val ROUND_END = Regex(
            "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[(?:Info|Warn)] \\[BaseAgent:craft] " +
                "\\[[0-9a-f]{32}]\\s+notifyAllStepsEnd,",
        )
        private val CALL_ID = Regex("\\[((?:call_|chatcmpl-tool-)[0-9A-Za-z_\\-]+)]")
        private val REQUEST_ID = Regex("requestId: ([0-9A-Za-z_\\-]+)")
        private val STEPS = Regex("totalSteps: (\\d+)")
        private val BRACKET = Regex("\\[([^\\]]+)]")

        /**
         * 模型限流文案（业务码 6004 频率 / 6000 用量 / 14018 额度，取自 WorkDaddy 同口径）。
         *
         * 只认**结构化字段**或官方错误详情里的限流短语：早期的「限流 / 频率限制」泛词
         * 会被聊天内容、工具输出与插件日志回显命中，曾造成「没被限流也提示限流」。
         */
        private val LIMIT_HINT = Regex(
            "(?:\"?(?:bizCode|errorCode|statusCode)\"?\\s*[:=]\\s*\"?(?:6004|6000|14018|429)\\b)|" +
                "(?:[^A-Za-z]code\"?\\s*[:=]\\s*\"?(?:6004|6000|14018)\\b)|" +
                "(?:(?:details|reason)\\s*[:=]\\s*[^\\n]{0,40}?(?:频率限制|用量限制|额度不足))|" +
                "(?:rate.?limit exceeded)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 等待确认文案：客户端换版本后不再只认固定的两句。
         *
         * 新版（marscode 1.7.0.4）不再打印「转入等待确认状态」，改为结构化决策行：
         * `[beforeExecute] Permission decision: source=xxx, allowed=true, needConfirm=true`
         * 与 `requiredApprove: true, needUserConfirm: true`，因此按字段取值匹配，
         * `needConfirm=false` 这类自动放行不会命中。
         */
        private val ASK_HINT = Regex(
            "转入等待确认状态|User confirmation required|等待用户确认|需要用户确认|等待确认|" +
                "waiting for confirmation|permission required|包含危险命令|" +
                "needConfirm\\s*[:=]\\s*true|needUserConfirm\\s*[:=]\\s*true|requiredApprove\\s*[:=]\\s*true",
            RegexOption.IGNORE_CASE,
        )
        private val CONFIRM_HINT = Regex(
            "收到用户确认|收到用户拒绝|用户拒绝执行|用户已确认|用户取消|user confirmed|user rejected|confirmed by user|" +
                // 新版同一批日志里紧跟「第一阶段已确认」，说明这次并不会真的等主人点
                "skipping second confirmation",
            RegexOption.IGNORE_CASE,
        )
        private val MODEL_ID = Regex(
            "\\b((?:claude|deepseek|gpt|gemini|hunyuan|kimi|qwen|glm|doubao)[-\\w.]*)",
            RegexOption.IGNORE_CASE,
        )

        /** `Message completed: <messageId>, model: <name>, success: true`，实测字段名 */
        private val MODEL_NAME = Regex("model: ([A-Za-z0-9][A-Za-z0-9._\\-]{1,60}), success")

        /** `Step execution metrics: step=N, duration=Xms, usage={"outputTokens":N,...}` */
        private val OUTPUT_TOKENS = Regex("\"outputTokens\"\\s*:\\s*(\\d+)")
        private val STEP_DURATION = Regex("duration=(\\d+)ms")

        /**
         * 宿主自动放行：`[beforeExecute] Permission decision: source=..., allowed=true, needConfirm=false`。
         *
         * 这是「CodeBuddy 自己把命令放过去了」唯一可见的信号：hook 通道没接上时也能靠它提示主人。
         * `needConfirm=true` 那一支属于等待确认，由 [ASK_HINT] 处理。
         */
        private val AUTO_ALLOW_HINT = Regex(
            "Permission decision:.*allowed=true.*needConfirm=fals",
            RegexOption.IGNORE_CASE,
        )

        /** `命令: "<命令原文>", isWatchCommand: false` */
        private val COMMAND_TEXT = Regex("命令: \"(.*?)\", isWatchCommand")
        private val RESET_AT = Regex(
            "(?:Reset at|resetAt|预计解封|预计恢复|解封时间|恢复时间)[^0-9]{0,12}" +
                "(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2})",
            RegexOption.IGNORE_CASE,
        )
        private val RESET_TS = Regex(
            "(?:resetAt|reset_at|resetTime|unlockAt)\\s*[:=]\\s*(\\d{10,13})",
            RegexOption.IGNORE_CASE,
        )

        fun getInstance(): CodeBuddyWatcher =
            ApplicationManager.getApplication().getService(CodeBuddyWatcher::class.java)
    }
}
