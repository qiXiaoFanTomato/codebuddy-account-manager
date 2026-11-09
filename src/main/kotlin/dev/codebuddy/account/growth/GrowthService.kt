package dev.codebuddy.account.growth

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 成长中心（成长计划）接口层。
 *
 * ⚠️ 全部接口**不是官方公开文档接口**：路径与请求形状来自对官方前端包
 * （assets.workbuddy.cn 上 usercenter 的 growth chunk）与 WorkBuddy 客户端
 * 行为上报协议的逆向整理，随版本可能变化。因此：
 *  1. 响应一律「宽松解析」：字段取不到就取默认值，不臆造语义；
 *  2. 路径与行为上报开关可在设置页关闭；
 *  3. 单账号失败不影响其它账号。
 *
 * host 口径（cn / global 两套，与客户端一致）：
 *  - chat 域（CLI / 桌面端）：cn `https://copilot.tencent.com`、global `https://www.workbuddy.ai`
 *  - billing 域（Web 结算）：cn `https://www.codebuddy.cn`、global `https://www.workbuddy.ai`
 *  - web 域（成长中心页面）：cn `https://www.workbuddy.cn`、global `https://www.workbuddy.ai`
 */
object GrowthService {

    private val log = logger<GrowthService>()

    // ---- 路径（实测；可在设置页覆盖前三个） ----

    /** 任务列表 */
    const val DEFAULT_TASKS_PATH = "/v2/activity/growth/tasks"

    /** 批量接取任务 */
    const val DEFAULT_ACCEPT_PATH = "/v2/activity/growth/tasks/accept"

    /** 领奖路径模板（%s 为任务 code，走 web 域） */
    const val DEFAULT_CLAIM_PATH = "/activity/growth/tasks/%s/claim"

    const val TRAVEL_STATUS_PATH = "/activity/growth/buddy/travel/status"
    const val TRAVEL_DEPART_PATH = "/activity/growth/buddy/travel/depart"
    const val TRAVEL_CLAIM_PATH = "/activity/growth/buddy/travel/claim"
    const val BUDDY_INFO_PATH = "/activity/growth/buddy/info"
    const val BUDDY_FIRST_PATH = "/activity/growth/buddy/first"
    const val BUDDY_AGREEMENT_PATH = "/activity/growth/buddy/agreement"
    const val STREAK_PATH = "/activity/growth/streak"
    const val REDEEM_PATH = "/activity/growth/redeem"
    const val LOTTERY_SUMMARY_PATH = "/activity/growth/lottery/summary"
    const val LOTTERY_DRAW_PATH = "/activity/growth/lottery/draw"
    const val MAKEUP_CARD_PATH = "/activity/growth/makeup-cards/use"

    /** 行为上报（CLI / 桌面 / Web 三种指纹口径共用一个端点） */
    const val REPORT_PATH = "/v2/report"

    /** token 保活刷新 */
    const val REFRESH_PATH = "/v2/plugin/auth/token/refresh"

    private const val CHAT_BASE_CN = "https://copilot.tencent.com"
    private const val BILLING_BASE_CN = "https://www.codebuddy.cn"
    private const val WEB_BASE_CN = "https://www.workbuddy.cn"
    private const val GLOBAL_BASE = "https://www.workbuddy.ai"

    /** 桌面客户端实测 UA（5.5.6 内嵌 CLI 2.137.1） */
    private const val DESKTOP_UA = "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1"

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    // ---------------------------------------------------------------
    // 数据模型
    // ---------------------------------------------------------------

    /** 任务状态（对齐 accept_status） */
    enum class TaskState(val raw: String, val label: String) {
        NOT_ACCEPTED("not_accepted", "未接取"),
        ACCEPTED("accepted", "已接取"),
        IN_PROGRESS("in_progress", "进行中"),
        COMPLETED("completed", "可领奖"),
        CLAIMED("claimed", "已领取"),
        UNKNOWN("", "未知");

        companion object {
            fun of(raw: String?): TaskState {
                val key = raw?.trim()?.lowercase() ?: return UNKNOWN
                return entries.firstOrNull { it.raw == key } ?: UNKNOWN
            }
        }
    }

    /** 单条成长任务 */
    data class GrowthTask(
        val taskCode: String,
        val title: String,
        val taskDesc: String = "",
        val description: String = "",
        val taskType: String? = null,
        val state: TaskState = TaskState.UNKNOWN,
        val rewardCredit: Double = 0.0,
        val rewardEnergy: Double = 0.0,
        val progressCurrent: Int = 0,
        val progressTarget: Int = 0,
        val jumpUrl: String? = null,
        val locked: Boolean = false,
    ) {
        /** 新手 / 自动类任务由服务端自动接取，不需要显式 accept */
        val auto: Boolean get() = taskType == "auto" || taskType == "beginner"

        val progressText: String
            get() = if (progressTarget > 0) "$progressCurrent/$progressTarget" else "$progressCurrent"

        /** 进度是否已达标（可领奖） */
        val claimable: Boolean
            get() = state == TaskState.COMPLETED ||
                (progressTarget > 0 && progressCurrent >= progressTarget && state != TaskState.CLAIMED)
    }

    /** 任务列表结果 */
    data class TaskListResult(
        val tasks: List<GrowthTask> = emptyList(),
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** 通用动作结果 */
    data class ActionResult(
        val message: String? = null,
        val credit: Double? = null,
        val energy: Double? = null,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** 猫猫旅行状态 */
    data class TravelState(
        val state: String = "",
        val dailyLimitReached: Boolean = false,
        val recordId: Long = 0L,
        val rewardCredit: Double = 0.0,
        /** 接口 depart_at：派出时间，秒级时间戳；0 表示无 */
        val departAt: Long = 0L,
        /** 接口 arrive_at：预计到站时间，秒级时间戳；0 表示无 */
        val arriveAt: Long = 0L,
        /** 接口 server_now：服务端当前时间（秒级），用来抵消本地时钟偏差 */
        val serverNow: Long = 0L,
        /** 接口 duration_hours：单程时长（小时） */
        val durationHours: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
        val stateLabel: String
            get() = when (state) {
                "idle" -> "空闲（可派出）"
                "traveling" -> "在途中"
                "arrived" -> "已到站（可领奖）"
                else -> "未知"
            }

        /** 距到站还有多久（毫秒）；没有到站时间返回 null */
        fun remainingMillis(): Long? {
            if (arriveAt <= 0L) return null
            val nowSec = if (serverNow > 0L) serverNow else System.currentTimeMillis() / 1000
            return (arriveAt - nowSec).coerceAtLeast(0L) * 1000L
        }

        /** 回来时间文案，如「约 2 小时后回来」；没有到站时间返回 null */
        fun etaText(): String? {
            val millis = remainingMillis() ?: return null
            if (millis <= 0L) return "已到站"
            val hours = millis / 3_600_000L
            val minutes = (millis % 3_600_000L) / 60_000L
            return when {
                hours >= 1L -> "约 $hours 小时${if (minutes > 0L) " $minutes 分钟" else ""}后回来"
                else -> "约 ${minutes.coerceAtLeast(1L)} 分钟后回来"
            }
        }
    }

    /** 连登状态 */
    data class StreakState(
        val days: Int = 0,
        val nextTier: String = "",
        val nextTierRemaining: Int = 0,
        val makeupCards: Int = 0,
        val tiers: List<RedeemTier> = emptyList(),
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** 连登兑换档位 */
    data class RedeemTier(
        val tier: String,
        val days: Int,
        val credit: Int,
        val energy: Int,
        val cards: Int,
        val chances: Int,
        val status: String = "",
    ) {
        val unlocked: Boolean get() = status.isBlank() || status.contains("redeemed", true) || status.contains("claimed", true)
        val label: String get() = "$tier · ${days}天 · ${credit}分${if (energy > 0) " +${energy}能量" else ""}"
    }

    /** token 刷新结果 */
    data class RefreshResult(
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresIn: Long = 0L,
        val domain: String = "",
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && accessToken.isNotBlank()
    }

    // ---------------------------------------------------------------
    // host 判定
    // ---------------------------------------------------------------

    /** global 账号（workbuddy.ai 域）走另一套 host */
    fun isGlobal(domain: String): Boolean = domain.contains(".workbuddy.ai", ignoreCase = true)

    fun chatBase(domain: String): String = if (isGlobal(domain)) GLOBAL_BASE else CHAT_BASE_CN

    fun billingBase(domain: String): String = if (isGlobal(domain)) GLOBAL_BASE else BILLING_BASE_CN

    fun webBase(domain: String): String = if (isGlobal(domain)) GLOBAL_BASE else WEB_BASE_CN

    // ---------------------------------------------------------------
    // 任务
    // ---------------------------------------------------------------

    fun fetchTasks(
        domain: String,
        accessToken: String,
        uid: String,
        path: String = DEFAULT_TASKS_PATH,
        enterpriseId: String = "",
    ): TaskListResult {
        if (accessToken.isBlank()) return TaskListResult(error = "accessToken 为空")
        val resp = request(
            base = chatBase(domain),
            path = path,
            method = "GET",
            token = accessToken,
            uid = uid,
            enterpriseId = enterpriseId,
            domain = domain,
            body = null,
        )
        if (resp.status != 200) return TaskListResult(error = "HTTP ${resp.status}")
        return parseTasks(resp.body)
    }

    fun acceptTasks(
        domain: String,
        accessToken: String,
        uid: String,
        taskCodes: List<String>,
        path: String = DEFAULT_ACCEPT_PATH,
        enterpriseId: String = "",
    ): ActionResult {
        if (taskCodes.isEmpty()) return ActionResult()
        if (accessToken.isBlank()) return ActionResult(error = "accessToken 为空")
        val payload = JsonObject().apply {
            add("task_codes", JsonArray().apply { taskCodes.forEach { add(it) } })
        }
        val resp = request(
            base = chatBase(domain),
            path = path,
            method = "POST",
            token = accessToken,
            uid = uid,
            enterpriseId = enterpriseId,
            domain = domain,
            body = payload.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    /** 领取任务奖励（走 web 域：x-client-platform: web） */
    fun claimTask(
        domain: String,
        accessToken: String,
        uid: String,
        taskCode: String,
        pathTemplate: String = DEFAULT_CLAIM_PATH,
        enterpriseId: String = "",
    ): ActionResult {
        if (accessToken.isBlank()) return ActionResult(error = "accessToken 为空")
        val encoded = URLEncoder.encode(taskCode, StandardCharsets.UTF_8)
        val base = webBase(domain)
        val resp = request(
            base = base,
            path = pathTemplate.replace("%s", encoded),
            method = "POST",
            token = accessToken,
            uid = uid,
            enterpriseId = enterpriseId,
            domain = domain,
            body = null,
            extraHeaders = mapOf(
                "x-client-platform" to "web",
                "Origin" to base,
                "Referer" to "$base/profile/growth-center",
            ),
            userAgent = WEB_UA,
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        val data = dataObject(resp.body)
        return parsed.copy(
            credit = num(data, "credit") ?: parsed.credit,
            energy = num(data, "energy") ?: parsed.energy,
        )
    }

    // ---------------------------------------------------------------
    // 行为上报（三种指纹口径）
    // ---------------------------------------------------------------

    /** CLI 口径对话活跃上报（billing 域）：点亮「对话 N 次」类任务 */
    fun reportChatActivity(
        domain: String,
        accessToken: String,
        uid: String,
        conversationId: String,
        requestId: String = "",
        modelId: String = "deepseek-v4-flash",
        modelName: String = "DeepSeek V4 Flash",
        enterpriseId: String = "",
    ): ActionResult {
        val now = System.currentTimeMillis()
        val rid = requestId.ifBlank { conversationId }
        val event = JsonObject().apply {
            addProperty("eventCode", "chat_request_send")
            addProperty("timestamp", now)
            addProperty("reportDelay", 0)
            addProperty("mode", "craft")
            addProperty("conversationId", conversationId)
            addProperty("requestId", rid)
            addProperty("inputLength", 12)
            addProperty("requestModelId", modelId)
            addProperty("requestModelName", modelName)
            addProperty("isPlan", false)
            addProperty("isAutoExecuteTerminal", false)
            addProperty("isAutoModify", false)
            addProperty("codebaseEnable", false)
            addProperty("maxToken", 0)
            addProperty("maxSteps", 0)
            addProperty("temperature", 0)
            addProperty("maxRetries", 0)
            add("mentionContexts", JsonArray())
            add("knowledgeId", JsonArray())
            add("knowledgeName", JsonArray())
            addProperty("codebaseId", "")
            addProperty("mentionContextCount", 0)
            addProperty("command", "")
            addProperty("expertId", "")
            addProperty("recommendId", "")
            addProperty("skillId", "")
            addProperty("skillCount", 0)
            addProperty("totalCount", 0)
            addProperty("fileUri", "")
            addProperty("presentAt", now)
            addProperty("traceId", "")
            addProperty("rootRequestId", rid)
            addProperty("parentConversationId", conversationId)
            addProperty("agentName", "default")
            addProperty("agentType", "conversation")
            addProperty("userId", uid)
        }
        return reportEvents(
            base = billingBase(domain),
            domain = domain,
            accessToken = accessToken,
            uid = uid,
            events = listOf(event),
            enterpriseId = enterpriseId,
        )
    }

    /** 桌面端口径上报（chat 域）：点亮「需电脑端」类任务 */
    fun reportDesktopEvents(
        domain: String,
        accessToken: String,
        uid: String,
        nickname: String,
        events: List<JsonObject>,
        enterpriseId: String = "",
    ): ActionResult {
        if (events.isEmpty()) return ActionResult(error = "没有待上报事件")
        val base = chatBase(domain)
        val now = System.currentTimeMillis()
        val fp = desktopFingerprint(uid, nickname, now)
        val array = JsonArray()
        for (ev in events) {
            val merged = JsonObject()
            for (key in fp.keySet()) merged.add(key, fp.get(key))
            for (key in ev.keySet()) merged.add(key, ev.get(key))
            array.add(merged)
        }
        return reportEvents(
            base = base,
            domain = domain,
            accessToken = accessToken,
            uid = uid,
            events = listOf(array),
            enterpriseId = enterpriseId,
            extraHeaders = mapOf(
                "X-Domain" to base,
                "X-Product" to "SaaS",
                "X-Request-ID" to "req-${now.toString().takeLast(9)}",
            ),
            userAgent = DESKTOP_UA,
        )
    }

    /** Web 口径上报（web 域）：点亮页面行为类任务 */
    fun reportWebEvent(
        domain: String,
        accessToken: String,
        uid: String,
        nickname: String,
        eventCode: String,
        pageUrl: String,
        elementId: String,
        elementName: String,
        enterpriseId: String = "",
    ): ActionResult {
        val base = webBase(domain)
        val now = System.currentTimeMillis()
        val event = JsonObject().apply {
            addProperty("eventCode", eventCode)
            addProperty("timestamp", now)
            addProperty("reportDelay", 0)
            addProperty("pageURL", pageUrl)
            addProperty("elementId", elementId)
            addProperty("elementName", elementName)
            addProperty("os", "Win32")
            addProperty("arch", "")
            addProperty("osVersion", "10.0")
            addProperty("userAgent", WEB_UA)
            addProperty("machineId", deriveId(uid, "webmachine"))
            addProperty("userId", uid)
            addProperty("userNickname", nickname)
            addProperty("enterpriseId", enterpriseId)
        }
        return reportEvents(
            base = base,
            domain = domain,
            accessToken = accessToken,
            uid = uid,
            events = listOf(event),
            enterpriseId = enterpriseId,
            extraHeaders = mapOf(
                "x-client-platform" to "web",
                "Origin" to base,
                "Referer" to pageUrl,
            ),
            userAgent = WEB_UA,
        )
    }

    private fun reportEvents(
        base: String,
        domain: String,
        accessToken: String,
        uid: String,
        events: List<JsonElement>,
        enterpriseId: String,
        extraHeaders: Map<String, String> = emptyMap(),
        userAgent: String = WEB_UA,
    ): ActionResult {
        if (accessToken.isBlank()) return ActionResult(error = "accessToken 为空")
        val payload = if (events.size == 1 && events[0] is JsonArray) events[0] else JsonArray().apply { events.forEach { add(it) } }
        val resp = request(
            base = base,
            path = REPORT_PATH,
            method = "POST",
            token = accessToken,
            uid = uid,
            enterpriseId = enterpriseId,
            domain = domain,
            body = payload.toString(),
            extraHeaders = extraHeaders,
            userAgent = userAgent,
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    // ---------------------------------------------------------------
    // 猫猫旅行
    // ---------------------------------------------------------------

    fun travelStatus(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): TravelState {
        if (accessToken.isBlank()) return TravelState(error = "accessToken 为空")
        val resp = request(
            base = chatBase(domain), path = TRAVEL_STATUS_PATH, method = "GET",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = null,
        )
        if (resp.status != 200) return TravelState(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        if (!parsed.ok) return TravelState(error = parsed.error)
        val data = dataObject(resp.body)
        // 字段取自接口实际返回：state / record_id / depart_at / arrive_at / server_now / duration_hours
        return TravelState(
            state = str(data, "state"),
            dailyLimitReached = bool(data, "daily_limit_reached") ?: false,
            recordId = long(data, "record_id") ?: 0L,
            rewardCredit = num(data, "reward_credit") ?: 0.0,
            departAt = long(data, "depart_at") ?: 0L,
            arriveAt = long(data, "arrive_at") ?: 0L,
            serverNow = long(data, "server_now") ?: 0L,
            durationHours = int(data, "duration_hours") ?: 0,
        )
    }

    fun travelDepart(
        domain: String,
        accessToken: String,
        uid: String,
        locationId: Int = 4,
        enterpriseId: String = "",
    ): ActionResult {
        val body = JsonObject().apply { addProperty("location_id", locationId) }
        val resp = request(
            base = chatBase(domain), path = TRAVEL_DEPART_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = body.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    fun travelClaim(
        domain: String,
        accessToken: String,
        uid: String,
        recordId: Long,
        enterpriseId: String = "",
    ): ActionResult {
        val body = JsonObject().apply { addProperty("record_id", recordId) }
        val resp = request(
            base = chatBase(domain), path = TRAVEL_CLAIM_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = body.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        val data = dataObject(resp.body)
        return parsed.copy(credit = num(data, "reward_credit") ?: parsed.credit)
    }

    /** 是否已有 Buddy（data.buddy 为 null 表示没有） */
    fun hasBuddy(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): ActionResult {
        val resp = request(
            base = chatBase(domain), path = BUDDY_INFO_PATH, method = "GET",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = null,
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        if (!parsed.ok) return parsed
        val data = dataObject(resp.body)
        val buddy = data?.get("buddy")
        val exists = buddy != null && !buddy.isJsonNull && buddy.toString() != "{}"
        return ActionResult(message = if (exists) "已有 Buddy" else "尚无 Buddy")
    }

    fun buddyAgreement(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): ActionResult {
        val body = JsonObject().apply { addProperty("agree", true) }
        val resp = request(
            base = chatBase(domain), path = BUDDY_AGREEMENT_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = body.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    fun buddyFirst(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): ActionResult {
        val resp = request(
            base = chatBase(domain), path = BUDDY_FIRST_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = "{}",
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    // ---------------------------------------------------------------
    // 连登 / 兑换 / 抽奖
    // ---------------------------------------------------------------

    fun streak(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): StreakState {
        if (accessToken.isBlank()) return StreakState(error = "accessToken 为空")
        val resp = request(
            base = chatBase(domain), path = STREAK_PATH, method = "GET",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = null,
        )
        if (resp.status != 200) return StreakState(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        if (!parsed.ok) return StreakState(error = parsed.error)
        val data = dataObject(resp.body)
        val streakObj = data?.getAsJsonObject("streak")
        val cards = data?.getAsJsonObject("makeup_cards")
        val redemption = data?.getAsJsonObject("redemption_status")
        val tiers = mutableListOf<RedeemTier>()
        redemption?.getAsJsonArray("tiers")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            tiers.add(
                RedeemTier(
                    tier = str(o, "tier"),
                    days = int(o, "days") ?: 0,
                    credit = int(o, "credit") ?: 0,
                    energy = int(o, "energy") ?: 0,
                    cards = int(o, "cards") ?: 0,
                    chances = int(o, "chances") ?: 0,
                    status = "",
                )
            )
        }
        return StreakState(
            days = int(streakObj, "days") ?: 0,
            nextTier = str(streakObj, "next_tier"),
            nextTierRemaining = int(streakObj, "next_tier_remaining") ?: 0,
            makeupCards = int(cards, "balance") ?: 0,
            tiers = tiers,
        )
    }

    /** 连登兑换（tier 形如 7d / 14d / 28d）；未解锁返回 403 */
    fun redeem(
        domain: String,
        accessToken: String,
        uid: String,
        tier: String,
        enterpriseId: String = "",
    ): ActionResult {
        val body = JsonObject().apply {
            addProperty("tier", tier)
            addProperty("client_token", UUID.randomUUID().toString())
        }
        val resp = request(
            base = chatBase(domain), path = REDEEM_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = body.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    fun lotterySummary(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): ActionResult {
        val resp = request(
            base = chatBase(domain), path = LOTTERY_SUMMARY_PATH, method = "GET",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = null,
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        val data = dataObject(resp.body)
        return parsed.copy(message = "抽奖次数 ${int(data, "chances") ?: 0}")
    }

    fun lotteryDraw(domain: String, accessToken: String, uid: String, enterpriseId: String = ""): ActionResult {
        val body = JsonObject().apply { addProperty("client_token", UUID.randomUUID().toString()) }
        val resp = request(
            base = chatBase(domain), path = LOTTERY_DRAW_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = body.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    /**
     * 使用补签卡补签指定日期（保住连登连续天数）。
     *
     * 是否真的漏签由服务端判定：已签过 / 无卡都会返回业务错误。
     */
    fun useMakeupCard(
        domain: String,
        accessToken: String,
        uid: String,
        targetDate: String,
        enterpriseId: String = "",
    ): ActionResult {
        val body = JsonObject().apply { addProperty("target_date", targetDate) }
        val resp = request(
            base = chatBase(domain), path = MAKEUP_CARD_PATH, method = "POST",
            token = accessToken, uid = uid, enterpriseId = enterpriseId, domain = domain, body = body.toString(),
        )
        if (resp.status != 200) return ActionResult(error = "HTTP ${resp.status}")
        return parseAction(resp.body)
    }

    // ---------------------------------------------------------------
    // token 保活
    // ---------------------------------------------------------------

    /**
     * 刷新 access token（保活）。
     *
     * 刷新是**无 body** 的 POST，凭证走 `X-Refresh-Token` 头 +
     * `X-Auth-Refresh-Source: plugin`；新 token 在响应的 data 里。
     */
    fun refreshToken(
        domain: String,
        accessToken: String,
        refreshToken: String,
        uid: String,
        enterpriseId: String = "",
    ): RefreshResult {
        if (refreshToken.isBlank()) return RefreshResult(error = "缺少 refreshToken")
        val resp = request(
            base = chatBase(domain),
            path = REFRESH_PATH,
            method = "POST",
            token = accessToken,
            uid = uid,
            enterpriseId = enterpriseId,
            domain = domain,
            body = null,
            extraHeaders = mapOf(
                "X-Refresh-Token" to refreshToken,
                "X-Auth-Refresh-Source" to "plugin",
            ),
            allowEmptyToken = true,
        )
        if (resp.status != 200) return RefreshResult(error = "HTTP ${resp.status}")
        val parsed = parseAction(resp.body)
        if (!parsed.ok) return RefreshResult(error = parsed.error)
        val data = dataObject(resp.body)
        val at = str(data, "accessToken")
        if (at.isBlank()) return RefreshResult(error = "响应中没有 accessToken")
        return RefreshResult(
            accessToken = at,
            refreshToken = str(data, "refreshToken").ifBlank { refreshToken },
            expiresIn = long(data, "expiresIn") ?: 0L,
            domain = str(data, "domain"),
        )
    }

    // ---------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------

    private data class Resp(val status: Int, val body: String)

    private fun request(
        base: String,
        path: String,
        method: String,
        token: String,
        uid: String,
        enterpriseId: String,
        domain: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        userAgent: String = WEB_UA,
        allowEmptyToken: Boolean = false,
    ): Resp {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val url = base + normalized
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .header("X-CodeBuddy-Request", "1")
            if (token.isNotBlank() || allowEmptyToken) {
                builder.header("Authorization", "Bearer $token")
            }
            if (uid.isNotBlank()) builder.header("X-User-Id", uid)
            if (enterpriseId.isNotBlank()) {
                builder.header("X-Enterprise-Id", enterpriseId)
                builder.header("X-Tenant-Id", enterpriseId)
            }
            if (domain.isNotBlank()) builder.header("X-Domain", domain)
            builder.header("Accept-Language", if (isGlobal(domain)) "en-US" else "zh-CN")
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            if (method.equals("GET", true)) {
                builder.GET()
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            Resp(response.statusCode(), response.body())
        } catch (t: Throwable) {
            log.warn("成长中心请求失败: $url", t)
            Resp(-1, "")
        }
    }

    // ---------------------------------------------------------------
    // 解析
    // ---------------------------------------------------------------

    private fun parseTasks(body: String): TaskListResult {
        val root = parseRoot(body) ?: return TaskListResult(error = "返回不是 JSON")
        val code = int(root, "code")
        val msg = str(root, "msg", "message")
        if (code != null && code != 0) {
            return TaskListResult(error = "接口返回 code=$code${if (msg.isNotBlank()) ": $msg" else ""}")
        }
        val data = root.get("data")
        val array: JsonArray = when {
            data == null || data.isJsonNull ->
                root.get("tasks")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return TaskListResult(tasks = emptyList())

            data.isJsonArray -> data.asJsonArray
            data.isJsonObject ->
                data.asJsonObject.get("tasks")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return TaskListResult(tasks = emptyList())

            else -> return TaskListResult(tasks = emptyList())
        }
        val tasks = array.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val code = str(o, "task_code", "taskCode")
            if (code.isBlank()) return@mapNotNull null
            var current = int(o, "current") ?: 0
            var target = int(o, "target") ?: 0
            val progress = o.get("progress")
            if (progress != null && progress.isJsonObject) {
                val p = progress.asJsonObject
                val pc = int(p, "current") ?: 0
                val pt = int(p, "target") ?: 0
                if (pt > 0 || pc > 0) {
                    current = pc
                    target = pt
                }
            }
            GrowthTask(
                taskCode = code,
                title = str(o, "title"),
                taskDesc = str(o, "task_desc"),
                description = str(o, "description"),
                taskType = str(o, "task_type").ifBlank { null },
                state = TaskState.of(str(o, "accept_status", "status")),
                rewardCredit = num(o, "reward_credit") ?: 0.0,
                rewardEnergy = num(o, "reward_energy") ?: 0.0,
                progressCurrent = current,
                progressTarget = target,
                jumpUrl = str(o, "jump_url").ifBlank { null },
                locked = bool(o, "locked") ?: false,
            )
        }
        return TaskListResult(tasks = tasks)
    }

    private fun parseAction(body: String): ActionResult {
        val root = parseRoot(body) ?: return ActionResult(error = "返回不是 JSON")
        val code = int(root, "code")
        val msg = str(root, "msg", "message")
        if (code != null && code != 0) {
            return ActionResult(error = "接口返回 code=$code${if (msg.isNotBlank()) ": $msg" else ""}")
        }
        return ActionResult(message = msg.ifBlank { null })
    }

    private fun dataObject(body: String): JsonObject? {
        val root = parseRoot(body) ?: return null
        val data = root.get("data") ?: return null
        return if (data.isJsonObject) data.asJsonObject else null
    }

    private fun parseRoot(body: String): JsonObject? = try {
        val el = JsonParser.parseString(body)
        if (el.isJsonObject) el.asJsonObject else null
    } catch (e: Exception) {
        log.warn("成长中心响应解析失败", e)
        null
    }

    private fun str(o: JsonObject?, vararg keys: String): String {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                if (el.isJsonPrimitive) el.asString else el.toString()
            } catch (_: Exception) {
                ""
            }
        }
        return ""
    }

    private fun int(o: JsonObject?, vararg keys: String): Int? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.toIntOrNull() else el.asInt
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun long(o: JsonObject?, vararg keys: String): Long? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.toLongOrNull() else el.asLong
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun num(o: JsonObject?, vararg keys: String): Double? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.toDoubleOrNull() else el.asDouble
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun bool(o: JsonObject?, vararg keys: String): Boolean? {
        for (k in keys) {
            val el = o?.get(k) ?: continue
            if (el.isJsonNull) continue
            return try {
                when {
                    el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                    el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt != 0
                    el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                        el.asString.trim().let { it == "1" || it.equals("true", true) }

                    else -> null
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    // ---------------------------------------------------------------
    // 指纹
    // ---------------------------------------------------------------

    /** 由 uid 稳定派生的 36 位 hex 设备标识（模拟固定设备） */
    fun deriveId(uid: String, salt: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$salt:$uid".toByteArray(StandardCharsets.UTF_8))
        return digest.take(18).joinToString("") { "%02x".format(it) }
    }

    /** 桌面端公共指纹（每个事件都带） */
    fun desktopFingerprint(uid: String, nickname: String, now: Long): JsonObject = JsonObject().apply {
        addProperty("timezone", "Asia/Shanghai")
        addProperty("reportDelay", 2000)
        addProperty("userId", uid)
        addProperty("username", nickname)
        addProperty("userNickname", nickname)
        addProperty("product", "SaaS")
        addProperty("releaseDate", 1789036585355L)
        addProperty("commit", "5f9692923c93033111c51ad7b003eb80204a9b75")
        addProperty("ideName", "WorkBuddy")
        addProperty("ideType", "WorkBuddy")
        addProperty("ideVersion", "5.5.6")
        addProperty("machineId", deriveId(uid, "machine"))
        addProperty("sessionId", deriveId(uid, "session"))
        addProperty("extName", "workbuddy-desktop")
        addProperty("extVersion", "5.5.6")
        addProperty("os", "win32")
        addProperty("arch", "x64")
        addProperty("osVersion", "10.0.26220")
        addProperty("cpuCores", 20)
        addProperty("memorySize", 24)
        addProperty("timestamp", now)
        addProperty("presentAt", now)
    }
}
