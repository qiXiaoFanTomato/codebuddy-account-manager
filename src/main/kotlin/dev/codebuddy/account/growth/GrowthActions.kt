package dev.codebuddy.account.growth

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 成长任务的「一键完成」动作表。
 *
 * ⚠️ 这里的动作都是**客户端行为上报**：官方客户端在完成某类任务后，会向
 * `/v2/report` 上报对应的事件链，服务端据此点亮任务进度。插件复刻这些事件链，
 * 等价于「不用打开客户端也能把任务刷到可领奖状态」。
 *
 * 分成三种指纹口径（服务端按 UA / X-Domain 区分来源）：
 *  - CLI 口径（billing 域 `www.codebuddy.cn/v2/report`）：`chat_request_send`
 *  - 桌面口径（chat 域 `copilot.tencent.com/v2/report`）：UA 为 WorkBuddy 桌面端
 *  - Web 口径（web 域 `www.workbuddy.cn/v2/report`）：`x-client-platform: web`
 *
 * 只收录**纯 API 即可点亮**的任务；需要真实模型对话 / 真实设备行为的任务
 * （如「体验专家」「尝鲜技能」「夜猫子」「主题皮肤」）不在表内，界面上标注
 * 「需在客户端完成」并给出跳转链接。
 */
object GrowthActions {

    /** 连续上报之间的间隔（对齐实测 1.05s 口径，避免风控） */
    const val REPORT_GAP_MS = 1050L

    /** 一次动作执行所需的上下文 */
    data class Ctx(
        val domain: String,
        val token: String,
        val uid: String,
        val nickname: String,
        val enterpriseId: String,
        val task: GrowthService.GrowthTask,
    )

    /** 动作执行结果 */
    data class Outcome(val ok: Boolean, val message: String)

    /** 一个可自动化的任务动作 */
    data class Action(
        val taskCode: String,
        val desc: String,
        val run: (Ctx) -> Outcome,
    )

    private val actions: List<Action> = listOf(
        Action("chat_5", "补报对话活跃事件（按差额）") { ctx ->
            val target = if (ctx.task.progressTarget > 0) ctx.task.progressTarget else 5
            val need = target - ctx.task.progressCurrent
            if (need <= 0) return@Action Outcome(true, "进度已达标，无需上报")
            var done = 0
            for (i in 0 until need) {
                val cid = "cbam-chat5-${System.currentTimeMillis()}-$i"
                val r = GrowthService.reportChatActivity(
                    domain = ctx.domain,
                    accessToken = ctx.token,
                    uid = ctx.uid,
                    conversationId = cid,
                    enterpriseId = ctx.enterpriseId,
                )
                if (!r.ok) return@Action Outcome(false, "第 ${i + 1}/$need 条上报失败：${r.error}")
                done++
                if (i < need - 1) Thread.sleep(REPORT_GAP_MS)
            }
            Outcome(true, "已补报 $done 条对话事件")
        },

        Action("first_buddy", "上报解锁 → 同意协议 → 领取第一只 Buddy") { ctx ->
            val r = GrowthService.reportChatActivity(
                domain = ctx.domain,
                accessToken = ctx.token,
                uid = ctx.uid,
                conversationId = "cbam-adopt-${System.currentTimeMillis()}",
                enterpriseId = ctx.enterpriseId,
            )
            if (!r.ok) return@Action Outcome(false, "前置上报失败：${r.error}")
            Thread.sleep(REPORT_GAP_MS)
            val agree = GrowthService.buddyAgreement(ctx.domain, ctx.token, ctx.uid, ctx.enterpriseId)
            if (!agree.ok) return@Action Outcome(false, "同意协议失败：${agree.error}")
            val first = GrowthService.buddyFirst(ctx.domain, ctx.token, ctx.uid, ctx.enterpriseId)
            if (!first.ok) {
                val msg = first.error.orEmpty()
                return@Action if (msg.contains("first_buddy")) {
                    Outcome(false, "领养门槛未过（需当日活跃），请稍后重试")
                } else {
                    Outcome(false, "领取 Buddy 失败：$msg")
                }
            }
            Outcome(true, "已领取 Buddy")
        },

        Action("RichMeow_Chat", "上报桌面端完整对话事件链") { ctx ->
            val ms = System.currentTimeMillis()
            val conv = "cbam-rm-$ms"
            val req = "cbam-rm-req-$ms"
            val events = desktopChatSequence(conv, req, "req-${ms}-user", "fast-model", "fast-model")
            val r = GrowthService.reportDesktopEvents(
                domain = ctx.domain,
                accessToken = ctx.token,
                uid = ctx.uid,
                nickname = ctx.nickname,
                events = events,
                enterpriseId = ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件链上报失败：${r.error}") else Outcome(true, "已上报桌面端对话事件链")
        },

        Action("Buddy_App", "上报「进入 Buddy 应用」五连事件") { ctx ->
            val events = desktopBuddyAppSequence("cb_y5Dy46tPQGGWtueMxXbe", "企鹅教师助手")
            val r = GrowthService.reportDesktopEvents(
                ctx.domain, ctx.token, ctx.uid, ctx.nickname, events, ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件上报失败：${r.error}") else Outcome(true, "已上报 buddyapp 五连事件")
        },

        Action("Buddy_App_QQ", "上报「进入企鹅教师助手」五连事件") { ctx ->
            val events = desktopBuddyAppSequence("cb_y5Dy46tPQGGWtueMxXbe", "企鹅教师助手")
            val r = GrowthService.reportDesktopEvents(
                ctx.domain, ctx.token, ctx.uid, ctx.nickname, events, ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件上报失败：${r.error}") else Outcome(true, "已上报 buddyapp 五连事件")
        },

        Action("automation_1", "上报「定时任务创建」事件") { ctx ->
            val events = listOf(
                desktopEvent("automated_task_create_suc").apply {
                    addProperty("name", "CodeBuddy 自动化")
                    addProperty("source", "manually")
                    addProperty("modelId", "fast-model")
                    addProperty("modelIsThinking", true)
                    addProperty("connectorCount", 0)
                    addProperty("skills", "")
                    addProperty("skillCount", 0)
                    addProperty("scheduleType", "once")
                    addProperty("mode", "LOCAL")
                }
            )
            val r = GrowthService.reportDesktopEvents(
                ctx.domain, ctx.token, ctx.uid, ctx.nickname, events, ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件上报失败：${r.error}") else Outcome(true, "已上报定时任务创建事件")
        },

        Action("Library_read", "上报「读资料库介绍」事件") { ctx ->
            val r = GrowthService.reportWebEvent(
                domain = ctx.domain,
                accessToken = ctx.token,
                uid = ctx.uid,
                nickname = ctx.nickname,
                eventCode = "web_element_click",
                pageUrl = "https://www.workbuddy.cn/space/d/o0KWYeynteVv06UnAZqIFm",
                elementId = "library_doc_intro_click",
                elementName = "WorkBuddy资料库介绍",
                enterpriseId = ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件上报失败：${r.error}") else Outcome(true, "已上报资料库阅读事件")
        },

        Action("template_5", "上报「使用模板创建任务」事件组 ×5") { ctx ->
            val templates = listOf(
                "1" to "深度研究",
                "2" to "周报生成",
                "3" to "竞品分析",
                "4" to "活动策划",
                "5" to "代码评审",
            )
            templates.forEachIndexed { index, (id, name) ->
                val ms = System.currentTimeMillis()
                val conv = "cbam-tpl-$ms-$index"
                val req = "cbam-tpl-req-$ms-$index"
                val events = desktopTemplateUseSequence(conv, req, id, name)
                val r = GrowthService.reportDesktopEvents(
                    ctx.domain, ctx.token, ctx.uid, ctx.nickname, events, ctx.enterpriseId,
                )
                if (!r.ok) return@Action Outcome(false, "第 ${index + 1} 组模板事件上报失败：${r.error}")
                if (index < templates.size - 1) Thread.sleep(300L)
            }
            Outcome(true, "已上报 template_used ×5")
        },

        Action("playbook_prompt", "上报「灵感案例做同款」事件组") { ctx ->
            val ms = System.currentTimeMillis()
            val conv = "cbam-pb-$ms"
            val req = "cbam-pb-req-$ms"
            val events = desktopPlaybookPromptSequence(conv, req, "pm-gtm-launch-plan", "新产品上市 GTM 发布计划一页纸")
            val r = GrowthService.reportDesktopEvents(
                ctx.domain, ctx.token, ctx.uid, ctx.nickname, events, ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件上报失败：${r.error}") else Outcome(true, "已上报 playbook 事件组")
        },

        Action("create_canvas", "上报「设计创意画布创建」事件组") { ctx ->
            val ms = System.currentTimeMillis()
            val conv = "cbam-canvas-$ms"
            val req = "cbam-canvas-req-$ms"
            val events = desktopDesignCanvasSequence(conv, req)
            val r = GrowthService.reportDesktopEvents(
                ctx.domain, ctx.token, ctx.uid, ctx.nickname, events, ctx.enterpriseId,
            )
            if (!r.ok) Outcome(false, "事件上报失败：${r.error}") else Outcome(true, "已上报画布创建事件组")
        },
    )

    /** 查动作；无则返回 null（该任务需在客户端手动完成） */
    fun find(taskCode: String): Action? = actions.firstOrNull { it.taskCode == taskCode.trim() }

    /** 该任务是否支持插件内自动完成 */
    fun isAutoSupported(taskCode: String): Boolean = find(taskCode) != null

    // ---------------------------------------------------------------
    // 桌面端事件构造
    // ---------------------------------------------------------------

    private fun desktopEvent(code: String): JsonObject = JsonObject().apply { addProperty("eventCode", code) }

    /** 一次「桌面端成功对话」的完整事件链（6 个事件） */
    fun desktopChatSequence(
        conversationId: String,
        requestId: String,
        messageId: String,
        modelId: String,
        modelName: String,
    ): List<JsonObject> {
        val now = System.currentTimeMillis()
        val uuid = requestId
        return listOf(
            desktopEvent("agent_task_created").apply {
                addProperty("source", "LOCAL")
                addProperty("name", "working")
                addProperty("task_target", "local")
                addProperty("mode", "craft")
                addProperty("requestModelId", modelId)
                addProperty("requestModelName", modelName)
                addProperty("has_repo", false)
                addProperty("repo_type", "none")
                addProperty("workspace_type", "empty")
                addProperty("has_connector", false)
                add("connector_types", JsonArray())
                addProperty("has_mention", false)
                add("mention_types", JsonArray())
                addProperty("has_template", false)
                addProperty("action", "")
                addProperty("template_name", "")
                addProperty("has_expert", false)
                addProperty("expert_id", "")
                addProperty("expert_name", "")
                addProperty("expert_industry_id", "")
                addProperty("has_skill", false)
                add("skill_names", JsonArray())
                addProperty("conversationId", conversationId)
                addProperty("messageId", messageId)
                addProperty("buddyId", "")
                addProperty("buddyName", "")
            },
            desktopEvent("chat_message_send").apply {
                addProperty("messageId", "$messageId-assistant")
                addProperty("historyCount", 0)
                addProperty("isContextTruncated", false)
                addProperty("currentStepCount", 1)
                addProperty("traceId", uuid)
                addProperty("rootRequestId", requestId)
                addProperty("parentConversationId", conversationId)
                addProperty("agentName", "cli")
                addProperty("agentType", "main")
            },
            desktopEvent("chat_request_send").apply {
                addProperty("inputLength", 24)
                addProperty("isPlan", false)
                addProperty("isAutoExecuteTerminal", false)
                addProperty("isAutoModify", false)
                addProperty("codebaseEnable", false)
                addProperty("maxToken", 0)
                addProperty("maxSteps", 500)
                addProperty("temperature", 0)
                addProperty("maxRetries", 0)
                add("mentionContexts", JsonArray())
                add("knowledgeId", JsonArray())
                add("knowledgeName", JsonArray())
                addProperty("codebaseId", "")
                addProperty("mentionContextCount", 0)
                addProperty("command", "")
                addProperty("recommendId", "")
                addProperty("skillId", "")
                addProperty("skillCount", 0)
                addProperty("totalCount", 0)
                addProperty("traceId", uuid)
                addProperty("rootRequestId", requestId)
                addProperty("parentConversationId", conversationId)
                addProperty("agentName", "cli")
                addProperty("agentType", "main")
                addProperty("codebuddy.session_id", conversationId)
                addProperty("codebuddy.conversation_request_id", requestId)
            },
            desktopEvent("chat_message_response").apply {
                addProperty("messageId", "$messageId-assistant")
                addProperty("responseModelId", modelId)
                addProperty("inputToken", 120)
                addProperty("outputToken", 80)
                addProperty("totalToken", 200)
                addProperty("cachedTokens", 0)
                addProperty("cachedWriteTokens", 0)
                addProperty("cachedMissTokens", 0)
                addProperty("isSuccessful", true)
                addProperty("messageErrorCode", "")
                addProperty("finishReason", "stop")
                addProperty("firstTokenAt", now)
                addProperty("traceId", uuid)
                addProperty("conversationId", conversationId)
                addProperty("rootRequestId", requestId)
                addProperty("parentConversationId", conversationId)
                addProperty("agentName", "cli")
                addProperty("agentType", "main")
                addProperty("codebuddy.session_id", conversationId)
                addProperty("codebuddy.conversation_request_id", requestId)
            },
            desktopEvent("chat_message_status").apply {
                addProperty("messageId", "$messageId-assistant")
                addProperty("messageErrorCode", "0")
                addProperty("traceId", uuid)
                addProperty("rootRequestId", requestId)
                addProperty("parentConversationId", conversationId)
                addProperty("agentName", "cli")
                addProperty("agentType", "main")
            },
            desktopEvent("chat_request_response").apply {
                addProperty("mode", "craft")
                addProperty("toolCallCount", 0)
                addProperty("inputToken", 120)
                addProperty("outputToken", 80)
                addProperty("totalToken", 200)
                addProperty("cachedTokens", 0)
                addProperty("cachedWriteTokens", 0)
                addProperty("cachedMissTokens", 0)
                addProperty("isSuccessful", true)
                addProperty("messageErrorCode", "")
                addProperty("finishReason", "stop")
                addProperty("rootRequestId", requestId)
            },
        )
    }

    /** 「进入 Buddy 应用」五连事件 */
    fun desktopBuddyAppSequence(buddyId: String, buddyName: String): List<JsonObject> {
        fun ev(code: String, elementId: String?, elementName: String?, extra: Map<String, Any>): JsonObject =
            desktopEvent(code).apply {
                addProperty("mode", "LOCAL")
                addProperty("buddyId", buddyId)
                addProperty("buddyName", buddyName)
                elementId?.let { addProperty("elementId", it) }
                elementName?.let { addProperty("elementName", it) }
                extra.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> addProperty(k, v)
                        is Int -> addProperty(k, v)
                        is Long -> addProperty(k, v)
                        else -> addProperty(k, v.toString())
                    }
                }
            }

        return listOf(
            ev("buddyapp_discover_click", null, null, emptyMap()),
            ev("buddyapp_show", buddyId, buddyName, mapOf("position" to 2)),
            ev("buddyapp_enter_click", buddyId, buddyName, mapOf("position" to 2, "isFirstPage" to "1")),
            ev("buddyapp_auth_confirm_click", buddyId, buddyName, emptyMap()),
            ev("buddyapp_bindaccount_skip_click", buddyId, buddyName, emptyMap()),
        )
    }

    /** 「使用模板创建任务」事件组（模板事件 JOIN 一条完整 chat 链） */
    fun desktopTemplateUseSequence(
        conversationId: String,
        requestId: String,
        templateId: String,
        templateName: String,
    ): List<JsonObject> {
        val events = desktopChatSequence(conversationId, requestId, "msg-$templateId", "fast-model", "fast-model").toMutableList()
        events.add(desktopEvent("agent_task_created_with_template").apply {
            addProperty("mode", "working")
            addProperty("isCustomModel", false)
            addProperty("id", templateId)
            addProperty("name", templateName)
            addProperty("requestId", requestId)
        })
        events.add(desktopEvent("template_used").apply {
            addProperty("template_id", templateId)
            addProperty("task_mode", "working")
        })
        return events
    }

    /** 「灵感案例做同款」事件组 */
    fun desktopPlaybookPromptSequence(
        conversationId: String,
        requestId: String,
        caseId: String,
        caseName: String,
    ): List<JsonObject> {
        val events = desktopChatSequence(conversationId, requestId, "msg-pb", "fast-model", "fast-model").toMutableList()
        events.add(desktopEvent("web_element_click").apply {
            addProperty("pageName", "playbook_detail")
            addProperty("elementId", "playbook_ctaClick")
            addProperty("elementName", caseName)
            addProperty("source", "discover")
        })
        events.add(desktopEvent("playbook_cta_click").apply {
            addProperty("source", "discover")
            addProperty("position", 0)
            addProperty("id", caseId)
            addProperty("name", caseName)
            addProperty("type", "document")
            addProperty("categoryId", "")
            addProperty("categoryName", "")
        })
        events.add(desktopEvent("playbook_prompt_send").apply {
            addProperty("conversationId", conversationId)
            addProperty("requestId", requestId)
            addProperty("id", caseId)
            addProperty("name", caseName)
            addProperty("type", "document")
            addProperty("categoryId", "")
            addProperty("categoryName", "")
        })
        return events
    }

    /** 「设计创意画布创建」事件组 */
    fun desktopDesignCanvasSequence(conversationId: String, requestId: String): List<JsonObject> {
        val events = desktopChatSequence(conversationId, requestId, "msg-canvas", "fast-model", "fast-model").toMutableList()
        events.add(desktopEvent("wbx_design_canvas_task_create").apply {
            addProperty("conversationId", conversationId)
            addProperty("requestId", requestId)
            addProperty("source", "summon_keyword")
            addProperty("cost", 12000)
            addProperty("isSuccessful", true)
        })
        events.add(desktopEvent("wbx_design_canvas_open").apply {
            addProperty("conversationId", conversationId)
            addProperty("requestId", requestId)
            addProperty("id", "ardot-file-" + requestId.takeLast(8))
            addProperty("source", "summon_keyword")
            addProperty("type", "page")
            addProperty("cost", 13000)
            addProperty("isSuccessful", true)
        })
        return events
    }
}
