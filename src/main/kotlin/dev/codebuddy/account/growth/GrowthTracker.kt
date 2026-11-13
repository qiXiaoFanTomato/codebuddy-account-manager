package dev.codebuddy.account.growth

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.MultiInstanceCoordinator

/**
 * 成长中心的跨账号聚合：任务查询 + 一键完成 + 旅行巡检 + 活跃上报 + token 保活。
 *
 * 与 [dev.codebuddy.account.checkin.CheckinTracker] 同构：
 * 单实例状态 + 后台线程执行 + EDT 回调刷新界面，缓存 [STALE_MS] 内不重复打接口。
 *
 * 只对「凭据快照里有 accessToken」的账号操作；接口错误按账号隔离，不影响其余账号。
 */
object GrowthTracker {

    private val log = logger<GrowthTracker>()

    private const val STALE_MS = 5 * 60_000L

    /** 成长计划详情（今日活跃 / 连登 / 旅行）的缓存时效：状态按天变化，不必跟任务一起 5 分钟一刷 */
    private const val DETAIL_STALE_MS = 10 * 60_000L

    /** 同类写操作的跨进程冷却：抑制多开实例同刻重复提交，不改变单次操作的业务结果 */
    private const val ACTION_COOLDOWN_MS = 15_000L

    /** 单个账号的任务快照 */
    data class AccountGrowth(
        val accountKey: String,
        val displayName: String,
        val tasks: List<GrowthService.GrowthTask> = emptyList(),
        val travel: GrowthService.TravelState? = null,
        val streak: GrowthService.StreakState? = null,
        val error: String? = null,
    ) {
        val total: Int get() = tasks.size
        val claimable: Int get() = tasks.count { it.claimable }
        val claimed: Int get() = tasks.count { it.state == GrowthService.TaskState.CLAIMED }
        val doing: Int get() = tasks.count {
            it.state == GrowthService.TaskState.ACCEPTED || it.state == GrowthService.TaskState.IN_PROGRESS
        }
        val pending: Int get() = tasks.count { it.state == GrowthService.TaskState.NOT_ACCEPTED }
        val autoSupported: Int get() = tasks.count { GrowthActions.isAutoSupported(it.taskCode) }
        val claimedCredit: Double
            get() = tasks.filter { it.state == GrowthService.TaskState.CLAIMED }.sumOf { it.rewardCredit }
        val claimableCredit: Double get() = tasks.filter { it.claimable }.sumOf { it.rewardCredit }
    }

    /**
     * 成长计划详情：单个账号的今日活跃 / 连登 / Buddy 旅行。
     *
     * 与 [AccountGrowth] 分开查询：这三项各自一个接口，跟任务列表一起查会把
     * 「成长中心任务」卡也一起拖慢，所以只在看板需要时按独立缓存拉取。
     */
    data class AccountDetail(
        val accountKey: String,
        val displayName: String,
        val active: GrowthService.ActiveState? = null,
        val streak: GrowthService.StreakState? = null,
        val travel: GrowthService.TravelState? = null,
        val error: String? = null,
    )

    data class DetailState(
        val items: List<AccountDetail> = emptyList(),
        val fetchedAt: Long = 0L,
        val loading: Boolean = false,
    ) {
        val loaded: Boolean get() = fetchedAt > 0L
        val total: Int get() = items.size

        /** 今日已活跃的账号数（查询失败的账号不计入） */
        val activeCount: Int get() = items.count { it.active?.let { a -> a.ok && a.active } == true }

        /** 各账号连登天数的最大值；全部查询失败时为 0 */
        val maxStreakDays: Int get() = items.maxOfOrNull { it.streak?.takeIf { s -> s.ok }?.days ?: 0 } ?: 0

        val failedCount: Int get() = items.count { it.error != null }
    }

    data class State(
        val items: List<AccountGrowth> = emptyList(),
        val fetchedAt: Long = 0L,
        val loading: Boolean = false,
        val running: Boolean = false,
    ) {
        val loaded: Boolean get() = fetchedAt > 0L
        val totalTasks: Int get() = items.sumOf { it.total }
        val claimableTasks: Int get() = items.sumOf { it.claimable }
        val claimedTasks: Int get() = items.sumOf { it.claimed }
        val doingTasks: Int get() = items.sumOf { it.doing }
        val claimedCredit: Double get() = items.sumOf { it.claimedCredit }
        val claimableCredit: Double get() = items.sumOf { it.claimableCredit }
        val failedCount: Int get() = items.count { it.error != null }
    }

    @Volatile
    private var state = State()

    @Volatile
    private var detailState = DetailState()

    private val lock = Any()
    private var loading = false
    private var running = false
    private var detailLoading = false

    /** 查询进行期间发生了写操作 / 强刷：本轮结果作废，结束后必须再查一次 */
    private var detailReloadPending = false
    private val waiters = mutableListOf<() -> Unit>()
    private val detailWaiters = mutableListOf<() -> Unit>()

    fun state(): State = state

    /** 成长计划详情缓存（今日活跃 / 连登 / 旅行） */
    fun details(): DetailState = detailState

    /** 查询各账号的今日活跃 / 连登 / 旅行；[DETAIL_STALE_MS] 内直接复用缓存 */
    fun ensureFreshDetails(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        synchronized(lock) {
            val fresh = !force && detailState.loaded &&
                System.currentTimeMillis() - detailState.fetchedAt < DETAIL_STALE_MS
            if (fresh) return
            if (onLoaded != null) detailWaiters.add(onLoaded)
            if (detailLoading) {
                // 正在查询的是操作之前的旧状态：本轮返回后必须重查，否则界面停在旧值
                detailReloadPending = true
                return
            }
            detailLoading = true
            detailReloadPending = false
            detailState = detailState.copy(loading = true)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            publishDetails(loadDetails())
        }
    }

    /** 查询各账号成长任务；缓存有效时不发请求也不回调 */
    fun ensureFresh(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        synchronized(lock) {
            val fresh = !force && state.loaded && System.currentTimeMillis() - state.fetchedAt < STALE_MS
            if (fresh) return
            if (onLoaded != null) waiters.add(onLoaded)
            if (loading) return
            loading = true
            state = state.copy(loading = true)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val items = loadTasks()
            publish(items)
        }
    }

    // ---------------------------------------------------------------
    // 一键完成
    // ---------------------------------------------------------------

    /**
     * 一键完成：接取未接取任务 → 执行可自动化的动作 → 领取已达标的奖励。
     *
     * @param accountKeys 为空表示账号库全部账号
     * @param onDone 在 EDT 回调，参数为本次汇总文案
     */
    fun completeAll(accountKeys: Set<String> = emptySet(), onDone: ((String) -> Unit)? = null) {
        launch("执行成长任务", { completeInternal(accountKeys) }, onDone)
    }

    /** 单个任务一键完成（自动动作 + 领奖） */
    fun completeTask(accountKey: String, taskCode: String, onDone: ((String) -> Unit)? = null) {
        launch("执行成长任务", {
            val snapshot = snapshotOf(accountKey) ?: return@launch "找不到账号 $accountKey"
            val token = tokenOf(snapshot) ?: return@launch "${snapshot.displayName}：快照中无 accessToken"
            val settings = AccountSettings.getInstance()
            val list = GrowthService.fetchTasks(
                domain = snapshot.domain,
                accessToken = token,
                uid = snapshot.uid,
                path = settings.growthTasksPath,
                enterpriseId = settings.growthEnterpriseId,
            )
            if (!list.ok) return@launch "${snapshot.displayName}：${list.error}"
            val task = list.tasks.firstOrNull { it.taskCode == taskCode }
                ?: return@launch "${snapshot.displayName}：任务不存在或已刷新"
            // 已达标的任务只差领奖：跳过行为上报，直接走 claim（否则不可自动完成的任务会卡在「需在客户端完成」）
            val outcome = if (task.claimable) {
                GrowthActions.Outcome(true, "已达标")
            } else {
                runAutoAction(snapshot, token, task)
            }
            if (!outcome.ok) return@launch "${snapshot.displayName}：${outcome.message}"
            val claim = GrowthService.claimTask(
                domain = snapshot.domain,
                accessToken = token,
                uid = snapshot.uid,
                taskCode = taskCode,
                pathTemplate = settings.growthClaimPath,
                enterpriseId = settings.growthEnterpriseId,
            )
            buildString {
                append(outcome.message)
                if (claim.ok) {
                    append("；已领取奖励")
                    val credit = claim.credit ?: 0.0
                    if (credit > 0) append("（+${formatCredit(credit)} credits）")
                } else {
                    append("；领奖失败：${claim.error}")
                }
            }
        }, onDone)
    }

    /** 旅行巡检：无猫 → 领养链路；有猫 → 派出 / 到站领奖 */
    fun travelAll(accountKeys: Set<String> = emptySet(), onDone: ((String) -> Unit)? = null) {
        launch("旅行巡检", {
            var departed = 0
            var claimed = 0
            var adopted = 0
            var skipped = 0
            val errors = ArrayList<String>()
            // 各账号猫猫的回来时间（如「启小凡 约 2 小时后回来」）
            val etas = ArrayList<String>()
            forEachAccount(accountKeys) { snapshot, token ->
                val settings = AccountSettings.getInstance()
                val buddy = GrowthService.hasBuddy(snapshot.domain, token, snapshot.uid, settings.growthEnterpriseId)
                if (!buddy.ok) {
                    errors.add("${snapshot.displayName}：${buddy.error}")
                    return@forEachAccount
                }
                if (buddy.message == "尚无 Buddy") {
                    // 领养前置：先上报活跃事件解锁 first_buddy
                    val report = GrowthService.reportChatActivity(
                        domain = snapshot.domain,
                        accessToken = token,
                        uid = snapshot.uid,
                        conversationId = "cbam-adopt-${System.currentTimeMillis()}",
                        enterpriseId = settings.growthEnterpriseId,
                    )
                    if (report.ok) Thread.sleep(GrowthActions.REPORT_GAP_MS)
                    val agree = GrowthService.buddyAgreement(
                        snapshot.domain, token, snapshot.uid, settings.growthEnterpriseId,
                    )
                    if (!agree.ok) {
                        errors.add("${snapshot.displayName}：同意协议失败 ${agree.error}")
                        return@forEachAccount
                    }
                    val first = GrowthService.buddyFirst(snapshot.domain, token, snapshot.uid, settings.growthEnterpriseId)
                    if (first.ok) {
                        adopted++
                    } else {
                        errors.add("${snapshot.displayName}：领养失败 ${first.error}")
                    }
                    return@forEachAccount
                }
                val ts = GrowthService.travelStatus(
                    snapshot.domain, token, snapshot.uid, settings.growthEnterpriseId,
                )
                if (!ts.ok) {
                    errors.add("${snapshot.displayName}：${ts.error}")
                    return@forEachAccount
                }
                when (ts.state) {
                    "arrived" -> {
                        if (ts.recordId == 0L) {
                            skipped++
                        } else {
                            val r = GrowthService.travelClaim(
                                snapshot.domain, token, snapshot.uid, ts.recordId, settings.growthEnterpriseId,
                            )
                            if (r.ok) {
                                claimed++
                            } else {
                                errors.add("${snapshot.displayName}：领奖失败 ${r.error}")
                            }
                        }
                    }

                    "idle" -> {
                        if (ts.dailyLimitReached) {
                            skipped++
                        } else {
                            val r = GrowthService.travelDepart(
                                snapshot.domain, token, snapshot.uid, 4, settings.growthEnterpriseId,
                            )
                            if (r.ok) {
                                departed++
                                // 派出后补查一次状态，拿到 arrive_at 才报得出回来时间
                                val after = GrowthService.travelStatus(
                                    snapshot.domain, token, snapshot.uid, settings.growthEnterpriseId,
                                )
                                after.etaText()?.let { etas.add("${snapshot.displayName} $it") }
                            } else {
                                errors.add("${snapshot.displayName}：派出失败 ${r.error}")
                            }
                        }
                    }

                    "traveling" -> {
                        // 在途中：报出还有多久回来
                        ts.etaText()?.let { etas.add("${snapshot.displayName} $it") }
                    }

                    else -> skipped++
                }
            }
            buildString {
                append("派出 $departed 个")
                if (claimed > 0) append("，领奖 $claimed 次")
                if (adopted > 0) append("，领养 $adopted 只")
                if (skipped > 0) append("，跳过 $skipped 个")
                if (etas.isNotEmpty()) {
                    append("；")
                    append(etas.take(3).joinToString("；"))
                    if (etas.size > 3) append("；等 ${etas.size} 只")
                }
                if (errors.isNotEmpty()) append("，失败 ${errors.size} 项（${errors.take(2).joinToString("；")}）")
            }
        }, onDone)
    }

    /** 活跃上报：每个账号发一条对话活跃事件（点亮连登 / 对话类任务） */
    fun activityAll(accountKeys: Set<String> = emptySet(), onDone: ((String) -> Unit)? = null) {
        launch("活跃上报", {
            var ok = 0
            var failed = 0
            val errors = ArrayList<String>()
            forEachAccount(accountKeys) { snapshot, token ->
                val settings = AccountSettings.getInstance()
                val r = GrowthService.reportChatActivity(
                    domain = snapshot.domain,
                    accessToken = token,
                    uid = snapshot.uid,
                    conversationId = "cbam-active-${System.currentTimeMillis()}",
                    enterpriseId = settings.growthEnterpriseId,
                )
                if (r.ok) ok++ else {
                    failed++
                    errors.add("${snapshot.displayName}：${r.error}")
                }
            }
            buildString {
                append("已上报 $ok 个账号")
                if (failed > 0) append("，失败 $failed 个（${errors.take(2).joinToString("；")}）")
            }
        }, onDone)
    }

    /** 全部保活：用 refreshToken 刷新各账号 accessToken，并写回账号库 */
    fun keepaliveAll(accountKeys: Set<String> = emptySet(), onDone: ((String) -> Unit)? = null) {
        launch("全部保活", {
            var ok = 0
            var failed = 0
            val errors = ArrayList<String>()
            forEachAccount(accountKeys) { snapshot, token ->
                val refresh = refreshTokenOf(snapshot)
                if (refresh.isNullOrBlank()) {
                    failed++
                    errors.add("${snapshot.displayName}：缺少 refreshToken")
                    return@forEachAccount
                }
                val settings = AccountSettings.getInstance()
                val r = GrowthService.refreshToken(
                    domain = snapshot.domain,
                    accessToken = token,
                    refreshToken = refresh,
                    uid = snapshot.uid,
                    enterpriseId = settings.growthEnterpriseId,
                )
                if (!r.ok) {
                    failed++
                    errors.add("${snapshot.displayName}：${r.error}")
                    return@forEachAccount
                }
                if (saveRefreshedToken(snapshot, r)) {
                    ok++
                } else {
                    failed++
                    errors.add("${snapshot.displayName}：写回账号库失败")
                }
            }
            buildString {
                append("已保活 $ok 个账号")
                if (failed > 0) append("，失败 $failed 个（${errors.take(2).joinToString("；")}）")
            }
        }, onDone)
    }

    /** 补签昨日：账号有补签卡时补一次，保住连登连续天数 */
    fun makeupAll(accountKeys: Set<String> = emptySet(), onDone: ((String) -> Unit)? = null) {
        launch("补签昨日", {
            val settings = AccountSettings.getInstance()
            val yesterday = java.time.LocalDate.now().minusDays(1).toString()
            var ok = 0
            var fail = 0
            var noCard = 0
            val notes = ArrayList<String>()
            forEachAccount(accountKeys) { snapshot, token ->
                val st = GrowthService.streak(snapshot.domain, token, snapshot.uid, settings.growthEnterpriseId)
                if (!st.ok) {
                    fail++
                    notes.add("${snapshot.displayName}：${st.error}")
                    return@forEachAccount
                }
                if (st.makeupCards <= 0) {
                    noCard++
                    return@forEachAccount
                }
                val r = GrowthService.useMakeupCard(
                    snapshot.domain, token, snapshot.uid, yesterday, settings.growthEnterpriseId,
                )
                if (r.ok) ok++ else {
                    fail++
                    notes.add("${snapshot.displayName}：${r.error}")
                }
            }
            buildString {
                append("补签 $ok 个账号")
                if (noCard > 0) append("，$noCard 个账号无补签卡")
                if (fail > 0) append("，失败 $fail 个（${notes.take(2).joinToString("；")}）")
            }
        }, onDone)
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /** 一键完成的账号内流程 */
    private fun completeInternal(accountKeys: Set<String>): String {
        var accepted = 0
        var autoDone = 0
        var claimed = 0
        var credit = 0.0
        var failed = 0
        val errors = ArrayList<String>()

        forEachAccount(accountKeys) { snapshot, token ->
            val settings = AccountSettings.getInstance()
            var list = GrowthService.fetchTasks(
                domain = snapshot.domain,
                accessToken = token,
                uid = snapshot.uid,
                path = settings.growthTasksPath,
                enterpriseId = settings.growthEnterpriseId,
            )
            if (!list.ok) {
                failed++
                errors.add("${snapshot.displayName}：${list.error}")
                return@forEachAccount
            }

            // 1) 接取未接取的任务
            val pendingCodes = list.tasks
                .filter { it.state == GrowthService.TaskState.NOT_ACCEPTED && !it.auto }
                .map { it.taskCode }
            if (pendingCodes.isNotEmpty()) {
                val r = GrowthService.acceptTasks(
                    domain = snapshot.domain,
                    accessToken = token,
                    uid = snapshot.uid,
                    taskCodes = pendingCodes,
                    path = settings.growthAcceptPath,
                    enterpriseId = settings.growthEnterpriseId,
                )
                if (r.ok) {
                    accepted += pendingCodes.size
                    // 接取后状态会变，重新拉一次再判断可领奖的任务
                    list = GrowthService.fetchTasks(
                        domain = snapshot.domain,
                        accessToken = token,
                        uid = snapshot.uid,
                        path = settings.growthTasksPath,
                        enterpriseId = settings.growthEnterpriseId,
                    )
                } else {
                    errors.add("${snapshot.displayName}：接取任务失败 ${r.error}")
                }
            }

            // 2) 执行可自动化的动作（事件上报类任务）
            if (settings.growthAutoActionsEnabled) {
                for (task in list.tasks) {
                    if (task.state == GrowthService.TaskState.CLAIMED) continue
                    if (task.state == GrowthService.TaskState.COMPLETED) continue
                    if (!GrowthActions.isAutoSupported(task.taskCode)) continue
                    val outcome = runAutoAction(snapshot, token, task)
                    if (outcome.ok) autoDone++ else failed++
                }
                // 动作执行后状态可能变化，重新拉一次
                list = GrowthService.fetchTasks(
                    domain = snapshot.domain,
                    accessToken = token,
                    uid = snapshot.uid,
                    path = settings.growthTasksPath,
                    enterpriseId = settings.growthEnterpriseId,
                )
            }

            // 3) 领取已达标的奖励
            for (task in list.tasks) {
                if (!task.claimable) continue
                val r = GrowthService.claimTask(
                    domain = snapshot.domain,
                    accessToken = token,
                    uid = snapshot.uid,
                    taskCode = task.taskCode,
                    pathTemplate = settings.growthClaimPath,
                    enterpriseId = settings.growthEnterpriseId,
                )
                if (r.ok) {
                    claimed++
                    credit += task.rewardCredit
                } else {
                    failed++
                    errors.add("${snapshot.displayName}：领取「${task.title}」失败 ${r.error}")
                }
            }
        }

        return buildString {
            append("接取 $accepted 个任务")
            if (autoDone > 0) append("，自动完成 $autoDone 个")
            if (claimed > 0) append("，领取 $claimed 个奖励")
            if (credit > 0) append("（+${formatCredit(credit)} credits）")
            if (failed > 0) append("，失败 $failed 项")
            if (errors.isNotEmpty()) append("（${errors.take(2).joinToString("；")}）")
        }
    }

    /** 执行单个任务的自动动作 */
    private fun runAutoAction(
        snapshot: CredentialSnapshot,
        token: String,
        task: GrowthService.GrowthTask,
    ): GrowthActions.Outcome {
        val action = GrowthActions.find(task.taskCode)
            ?: return GrowthActions.Outcome(false, "「${task.title}」需在客户端完成")
        return try {
            action.run(
                GrowthActions.Ctx(
                    domain = snapshot.domain,
                    token = token,
                    uid = snapshot.uid,
                    nickname = snapshot.nickname,
                    enterpriseId = AccountSettings.getInstance().growthEnterpriseId,
                    task = task,
                )
            )
        } catch (t: Throwable) {
            log.warn("成长任务动作失败: ${task.taskCode}", t)
            GrowthActions.Outcome(false, "${task.title}：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 统一执行包装：跨进程去重 + 后台线程执行 + running 状态管理 + 结束后刷新状态 + EDT 回调 */
    private fun launch(label: String, block: () -> String, onDone: ((String) -> Unit)?) {
        // 同一类写操作短时间内只允许一个 IDE 实例执行，避免多开时重复接取 / 上报 / 领奖 / 保活
        if (!MultiInstanceCoordinator.claim("growth-action", label, ACTION_COOLDOWN_MS)) {
            onDone?.let { cb -> invokeOnEdt(cb, "$label 已由其它 IDE 实例执行，请稍后再试") }
            return
        }
        synchronized(lock) {
            if (running) {
                onDone?.let { cb -> invokeOnEdt(cb, "$label 正在执行中") }
                return
            }
            running = true
            state = state.copy(running = true)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val summary = try {
                block()
            } catch (t: Throwable) {
                log.warn("$label 失败", t)
                MultiInstanceCoordinator.releaseClaim("growth-action", label)
                "$label 失败：${t.message ?: t.javaClass.simpleName}"
            }
            finish(loadTasks())
            onDone?.let { cb -> invokeOnEdt(cb, summary) }
        }
    }

    private fun invokeOnEdt(cb: (String) -> Unit, summary: String) {
        ApplicationManager.getApplication().invokeLater({
            try {
                cb(summary)
            } catch (t: Throwable) {
                log.warn("成长任务回调失败", t)
            }
        }, ModalityState.any())
    }

    /** 遍历账号（按过滤条件），回调里可 return@forEachAccount 跳过当前账号 */
    private fun forEachAccount(
        accountKeys: Set<String>,
        action: (CredentialSnapshot, String) -> Unit,
    ) {
        val library = AccountLibrary.getInstance()
        val saved: List<CredentialSnapshot> = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }
        val tokens = library.accessTokenMap()
        for (s in saved) {
            if (accountKeys.isNotEmpty() && s.accountKey !in accountKeys) continue
            val token = tokens[s.accountKey]
            if (token.isNullOrBlank()) continue
            action(s, token)
        }
    }

    private fun snapshotOf(accountKey: String): CredentialSnapshot? =
        AccountLibrary.getInstance().listSaved().firstOrNull { it.accountKey == accountKey }

    private fun tokenOf(snapshot: CredentialSnapshot): String? =
        AccountLibrary.getInstance().accessTokenMap()[snapshot.accountKey]

    /** 从快照原始 JSON 里取 refreshToken */
    private fun refreshTokenOf(snapshot: CredentialSnapshot): String? = try {
        val root = JsonParser.parseString(snapshot.rawContent).asJsonObject
        root.getAsJsonObject("auth")?.get("refreshToken")?.asString
    } catch (_: Exception) {
        null
    }

    /** 刷新成功后把新 token 写回账号库快照 */
    private fun saveRefreshedToken(snapshot: CredentialSnapshot, result: GrowthService.RefreshResult): Boolean = try {
        val root = JsonParser.parseString(snapshot.rawContent).asJsonObject
        val auth = root.getAsJsonObject("auth")
        if (auth == null) {
            false
        } else {
            auth.addProperty("accessToken", result.accessToken)
            if (result.refreshToken.isNotBlank()) auth.addProperty("refreshToken", result.refreshToken)
            if (result.expiresIn > 0) {
                auth.addProperty("expiresAt", System.currentTimeMillis() + result.expiresIn * 1000)
            }
            if (result.domain.isNotBlank()) auth.addProperty("domain", result.domain)
            AccountLibrary.getInstance().importSnapshot(snapshot.copy(rawContent = root.toString()))
        }
    } catch (t: Throwable) {
        log.warn("写回刷新后的凭据失败: ${snapshot.accountKey}", t)
        false
    }

    /** 结束执行：先复位 running 再发布状态 */
    private fun finish(items: List<AccountGrowth>) {
        synchronized(lock) { running = false }
        publish(items)
        // 接取 / 上报 / 补签 / 旅行都可能改变活跃与连登，详情缓存一并过期，等看板下次读取时重查
        invalidateDetails()
    }

    /** 详情缓存过期（不影响已渲染的旧数据，只让下一次 ensureFreshDetails 重新查询） */
    private fun invalidateDetails() {
        synchronized(lock) {
            if (detailState.fetchedAt > 0L) detailState = detailState.copy(fetchedAt = 0L)
            // 本轮查询发出于写操作之前：结果已过期，返回后补一次
            if (detailLoading) detailReloadPending = true
        }
    }

    private fun loadTasks(): List<AccountGrowth> {
        val settings = AccountSettings.getInstance()
        if (!settings.growthEnabled) return emptyList()

        val library = AccountLibrary.getInstance()
        val saved: List<CredentialSnapshot> = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        val tokens = library.accessTokenMap()
        return saved.map { s ->
            val token = tokens[s.accountKey]
            if (token.isNullOrBlank()) {
                AccountGrowth(s.accountKey, s.displayName, emptyList(), error = "快照中无 accessToken")
            } else {
                val r = GrowthService.fetchTasks(
                    domain = s.domain,
                    accessToken = token,
                    uid = s.uid,
                    path = settings.growthTasksPath,
                    enterpriseId = settings.growthEnterpriseId,
                )
                AccountGrowth(s.accountKey, s.displayName, r.tasks, error = r.error)
            }
        }
    }

    /** 逐账号查今日活跃 / 连登 / 旅行；与任务列表分开，避免拖慢成长中心任务卡 */
    private fun loadDetails(): List<AccountDetail> {
        val settings = AccountSettings.getInstance()
        if (!settings.growthEnabled) return emptyList()

        val library = AccountLibrary.getInstance()
        val saved: List<CredentialSnapshot> = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }

        val tokens = library.accessTokenMap()
        return saved.map { s ->
            val token = tokens[s.accountKey]
            if (token.isNullOrBlank()) {
                AccountDetail(s.accountKey, s.displayName, error = "快照中无 accessToken")
            } else {
                AccountDetail(
                    accountKey = s.accountKey,
                    displayName = s.displayName,
                    active = GrowthService.todayActive(s.domain, token, s.uid, settings.growthEnterpriseId),
                    streak = GrowthService.streak(s.domain, token, s.uid, settings.growthEnterpriseId),
                    travel = GrowthService.travelStatus(s.domain, token, s.uid, settings.growthEnterpriseId),
                )
            }
        }
    }

    private fun publishDetails(items: List<AccountDetail>) {
        val pending: List<() -> Unit>
        val reload: Boolean
        synchronized(lock) {
            detailState = DetailState(items = items, fetchedAt = System.currentTimeMillis(), loading = false)
            detailLoading = false
            reload = detailReloadPending
            detailReloadPending = false
            pending = detailWaiters.toList()
            detailWaiters.clear()
        }
        if (reload) {
            // 本轮结果是写操作之前的：回调留给补查那一轮，界面才不会停在旧状态
            synchronized(lock) { detailWaiters.addAll(pending) }
            ensureFreshDetails(force = true)
            return
        }
        pending.forEach { callback ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    callback()
                } catch (t: Throwable) {
                    log.warn("成长计划详情回调失败", t)
                }
            }, ModalityState.any())
        }
    }

    private fun publish(items: List<AccountGrowth>) {
        val pending: List<() -> Unit>
        synchronized(lock) {
            state = State(
                items = items,
                fetchedAt = System.currentTimeMillis(),
                loading = false,
                running = running,
            )
            loading = false
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { callback ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    callback()
                } catch (t: Throwable) {
                    log.warn("成长任务状态回调失败", t)
                }
            }, ModalityState.any())
        }
    }

    private fun formatCredit(v: Double): String =
        if (v >= 1000) "%,.0f".format(v) else "%.2f".format(v)
}
