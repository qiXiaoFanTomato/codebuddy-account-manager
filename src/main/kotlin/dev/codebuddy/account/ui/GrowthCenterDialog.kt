package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.growth.GrowthActions
import dev.codebuddy.account.growth.GrowthService
import dev.codebuddy.account.growth.GrowthTracker
import dev.codebuddy.account.pet.CbEventCenter
import dev.codebuddy.account.pet.PetSettings
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** 复刻页面的绿色主色（按钮描边 / 文字） */
private val PLAN_GREEN: Color = Color(0x3F9E5F)
private val PLAN_GREEN_SOFT: Color = Color(0xEAF6EE)
private val PLAN_CARD_BORDER: Color = Color(0xE5E8EA)

/**
 * 成长计划窗口：1:1 复刻 WorkDaddy 成长中心「成长计划」页。
 *
 * 三块内容：
 *  - 成长任务：每条任务一行（标题 / 描述 / 进度与奖励 / 去完成）；
 *  - Buddy 旅行：今日旅行状态 + 开启盲盒 / 待抽奖次数；
 *  - 连续登录：连续天数、补登卡、三个档位的差距。
 *
 * 数据全部来自 [GrowthTracker]（任务）与 [GrowthTracker.details]（旅行 / 连登），
 * 打开窗口只读缓存并触发一次后台刷新，不会每次重绘都打接口。
 * 底部保留账号范围与批量动作（一键完成 / 旅行巡检 / 活跃上报 / 保活 / 兑换 / 补签 / 抽奖）。
 */
class GrowthCenterDialog(private val project: Project?) : DialogWrapper(project, false) {

    private val accountSelector = JComboBox<String>()

    /** 下拉项文案 → 账号 key */
    private val accountKeyByLabel = LinkedHashMap<String, String>()

    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AccountUiColors.panelBg
        border = JBUI.Borders.empty(AccountUiSpacing.cardGap, AccountUiSpacing.cardPadH)
    }

    private val updatedLabel = JLabel("").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        horizontalAlignment = SwingConstants.RIGHT
    }

    private val statusLabel = JLabel("就绪").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
    }

    /** 待抽奖次数：接口只提供 chances，多账号时合计；null 表示尚未查询 */
    private var lotteryChances: Int? = null
    private var lotteryLoading = false

    /** 首次进入的「今天可以做什么」提示：本次会话点过「知道了」后不再显示 */
    private var showBrief = true

    /** 上次渲染的滚动位置，刷新后还原，避免每次刷新跳回顶部 */
    private var lastScrollValue = 0

    init {
        title = "成长计划"
        setCancelButtonText("关闭")
        init()
        buildAccountSelector()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val scroll = AccountUiKit.styleScroll(JBScrollPane(content), AccountUiColors.panelBg).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
        scroll.verticalScrollBar.addAdjustmentListener { lastScrollValue = it.value }
        scroll.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(620))
        return scroll
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    // ---------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------

    private fun reload() {
        render()
        GrowthTracker.ensureFresh { onEdt { render() } }
        GrowthTracker.ensureFreshDetails { onEdt { render() } }
        CheckinTracker.ensureFresh { onEdt { render() } }
        loadLottery()
    }

    private fun render() {
        content.removeAll()
        val taskState = GrowthTracker.state()
        val detailState = GrowthTracker.details()

        updatedLabel.text = "刷新于 " + DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(if (taskState.fetchedAt > 0) taskState.fetchedAt else System.currentTimeMillis()))

        content.add(headerRow())
        content.add(Box.createVerticalStrut(JBUI.scale(8)))
        // 每次打开窗口首次渲染时给一份「今天可以做什么」，点「知道了」后本次会话不再出现
        if (showBrief) {
            content.add(briefCard(taskState, detailState))
            content.add(Box.createVerticalStrut(JBUI.scale(12)))
        }
        content.add(claimProgress(taskState))
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
        content.add(taskCard(taskState))
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
        content.add(travelCard(detailState))
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
        content.add(streakCard(detailState))
        content.add(Box.createVerticalGlue())

        content.revalidate()
        content.repaint()
        (SwingUtilities.getAncestorOfClass(JBScrollPane::class.java, content) as? JBScrollPane)
            ?.verticalScrollBar?.value = lastScrollValue
    }

    /** 顶部：页面标题 + 右侧刷新时刻（对应页头 `.page-head`） */
    private fun headerRow(): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        }
        row.add(
            JLabel("成长计划").apply {
                font = AccountUiType.ui(AccountUiType.scaled(20), Font.BOLD)
                foreground = AccountUiColors.textPrimary
            },
            BorderLayout.WEST,
        )
        row.add(updatedLabel, BorderLayout.EAST)
        return row
    }

    /** 标题下的细进度条：已完成任务占比（对应页头下方的进度线） */
    private fun claimProgress(state: GrowthTracker.State): JComponent {
        val percent = if (state.totalTasks <= 0) 0.0 else state.claimedTasks.toDouble() / state.totalTasks * 100.0
        return ProgressLine(percent).apply { alignmentX = Component.LEFT_ALIGNMENT }
    }

    // ---------------------------------------------------------------
    // 今天可以做什么（首次进入提示）
    // ---------------------------------------------------------------

    private fun briefCard(taskState: GrowthTracker.State, detailState: GrowthTracker.DetailState): JComponent {
        val card = PlanCard()
        card.add(
            cardHeader(
                "今天可以做什么",
                PlanActionButton("知道了") {
                    showBrief = false
                    render()
                },
            )
        )
        card.add(Box.createVerticalStrut(JBUI.scale(6)))
        for (line in briefItems(taskState, detailState)) {
            card.add(briefRow(line))
        }
        return card
    }

    private fun briefRow(text: String): JComponent = JLabel("· $text").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textSecondary
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(3)
        toolTipText = text
    }

    /** 今日待办清单：只读缓存，不发请求 */
    private fun briefItems(taskState: GrowthTracker.State, detailState: GrowthTracker.DetailState): List<String> {
        val out = ArrayList<String>()
        val settings = AccountSettings.getInstance()

        if (settings.checkinEnabled) {
            val checkin = CheckinTracker.state()
            val total = checkin.items.size
            val missing = total - checkin.checkedCount
            when {
                !checkin.loaded -> out += "签到：正在读取今日签到状态…"
                total == 0 -> out += "签到：账号库里还没有保存的账号"
                missing > 0 -> out += "签到：还有 $missing 个账号未签，点「一键签到」"
                else -> out += "签到：$total 个账号今日已签完"
            }
        } else {
            out += "签到：设置里已关闭"
        }

        when {
            taskState.claimableTasks > 0 -> out += "成长任务：${taskState.claimableTasks} 个可领奖，点「一键完成」"
            taskState.doingTasks > 0 -> out += "成长任务：${taskState.doingTasks} 个进行中，点「活跃上报」推进一步"
            !taskState.loaded -> out += "成长任务：正在读取任务列表…"
            else -> out += "成长任务：暂时没有可自动完成的任务"
        }

        val travels = detailState.items.mapNotNull { it.travel?.takeIf { t -> t.ok } }
        val arrived = travels.count { it.state == "arrived" }
        val idle = travels.count { it.state == "idle" && !it.dailyLimitReached }
        val traveling = travels.count { it.state == "traveling" }
        when {
            arrived > 0 -> out += "Buddy 旅行：$arrived 只已到站，点「旅行巡检」领奖"
            idle > 0 -> out += "Buddy 旅行：$idle 只可派出，点「旅行巡检」"
            traveling > 0 -> {
                val eta = travels.firstOrNull { it.state == "traveling" }?.etaText()
                out += "Buddy 旅行：$traveling 只在途" + (eta?.let { "，$it" } ?: "")
            }
            !detailState.loaded -> out += "Buddy 旅行：正在读取状态…"
            else -> out += "Buddy 旅行：今日已完成"
        }

        out += if (settings.growthEnabled) {
            "成长中心：已开启，任务与旅行可自动执行"
        } else {
            "成长中心：设置里已关闭，任务与旅行不会自动执行"
        }

        out += petBrief()
        CbEventCenter.activeLimits().firstOrNull()?.let { out += "模型限制：${it.detail()}" }
        return out
    }

    /** 权限确认代点开关：主人最容易忘记开的一项，直接说清当前状态与后果 */
    private fun petBrief(): String = try {
        val pet = PetSettings.getInstance()
        when {
            pet.cbNoDisturbEnabled -> "权限弹窗代点：已开启免打扰，出现「允许 / 拒绝」时自动点「允许」"
            pet.cbAutoApproveEnabled -> "权限弹窗代点：已开启超时自动同意（等 ${pet.cbAutoApproveMinutes} 分钟）"
            else -> "权限弹窗代点：未开启，需要主人手动点「允许」（可在桌宠设置里开启）"
        }
    } catch (_: Throwable) {
        "权限弹窗代点：状态未知（桌宠未启动）"
    }

    // ---------------------------------------------------------------
    // 成长任务
    // ---------------------------------------------------------------

    private fun taskCard(state: GrowthTracker.State): JComponent {
        val card = PlanCard()
        card.add(
            cardHeader(
                "成长任务",
                JLabel("${state.claimedTasks} / ${state.totalTasks}").apply {
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                },
            )
        )
        card.add(Box.createVerticalStrut(JBUI.scale(10)))

        if (state.items.isEmpty()) {
            card.add(hint(if (state.loading) "正在读取成长任务…" else "暂无任务数据，点「刷新」读取。"))
            return card
        }

        val multiAccount = state.items.size > 1
        var first = true
        for (item in state.items) {
            for (task in item.tasks) {
                if (!first) card.add(HairLine())
                first = false
                card.add(taskRow(item, task, multiAccount))
            }
        }
        if (state.failedCount > 0) {
            card.add(Box.createVerticalStrut(JBUI.scale(6)))
            card.add(hint("${state.failedCount} 个账号读取失败：多为 token 失效或接口不可用"))
        }
        return card
    }

    private fun taskRow(
        item: GrowthTracker.AccountGrowth,
        task: GrowthService.GrowthTask,
        multiAccount: Boolean,
    ): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border = JBUI.Borders.empty(9, 0)
        }

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val titleRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        titleRow.add(
            JLabel(task.title.ifBlank { task.taskCode }).apply {
                font = AccountUiType.forText(task.title, AccountUiType.label, Font.BOLD)
                foreground = AccountUiColors.textPrimary
            }
        )
        if (multiAccount) {
            titleRow.add(Box.createHorizontalStrut(JBUI.scale(6)))
            titleRow.add(AccountUiKit.fieldPill(item.displayName, AccountUiColors.textMuted))
        }
        titleRow.add(Box.createHorizontalGlue())
        left.add(titleRow)
        left.add(Box.createVerticalStrut(JBUI.scale(3)))

        val desc = task.taskDesc.ifBlank { task.description }
        left.add(
            JLabel(desc.ifBlank { "—" }).apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = AccountUiColors.textMuted
                toolTipText = desc.ifBlank { null }
                alignmentX = Component.LEFT_ALIGNMENT
            }
        )
        left.add(Box.createVerticalStrut(JBUI.scale(5)))

        val meta = JPanel(FlowWrapLayout(JBUI.scale(10), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        meta.add(metaText("进度 ${task.progressCurrent} / ${task.progressTarget}"))
        meta.add(metaText("奖励"))
        if (task.rewardCredit > 0) meta.add(AccountUiKit.fieldPill("${trim(task.rewardCredit)} Credits", AccountUiColors.textMuted))
        if (task.rewardEnergy > 0) meta.add(AccountUiKit.fieldPill("${trim(task.rewardEnergy)} 能量", AccountUiColors.textMuted))
        meta.add(metaText("状态 ${task.state.label}"))
        left.add(meta)

        row.add(left, BorderLayout.CENTER)
        row.add(taskButton(item, task), BorderLayout.EAST)
        return row
    }

    private fun metaText(text: String): JComponent = JLabel(text).apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textFaint
        verticalAlignment = SwingConstants.CENTER
    }

    /** 任务按钮：可自动完成的直接执行，其余打开跳转链接或提示需在客户端完成 */
    private fun taskButton(item: GrowthTracker.AccountGrowth, task: GrowthService.GrowthTask): JComponent {
        if (task.state == GrowthService.TaskState.CLAIMED) {
            return JLabel("已领取").apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = AccountUiColors.textFaint
            }
        }
        val text = when {
            task.claimable -> "领取"
            task.state == GrowthService.TaskState.COMPLETED -> "领取"
            else -> "去完成"
        }
        val button = PlanActionButton(text) { onTaskAction(item, task) }
        button.alignmentY = Component.CENTER_ALIGNMENT
        return button
    }

    private fun onTaskAction(item: GrowthTracker.AccountGrowth, task: GrowthService.GrowthTask) {
        when {
            task.claimable || GrowthActions.isAutoSupported(task.taskCode) -> {
                status("执行中：${task.title}")
                GrowthTracker.completeTask(item.accountKey, task.taskCode) { summary ->
                    status(summary)
                    reload()
                }
            }

            !task.jumpUrl.isNullOrBlank() -> {
                try {
                    Desktop.getDesktop().browse(URI.create(task.jumpUrl))
                    status("已在浏览器打开「${task.title}」")
                } catch (t: Throwable) {
                    status("无法打开链接：${task.jumpUrl}")
                }
            }

            else -> status("「${task.title}」需在客户端完成")
        }
    }

    // ---------------------------------------------------------------
    // Buddy 旅行
    // ---------------------------------------------------------------

    private fun travelCard(details: GrowthTracker.DetailState): JComponent {
        val card = PlanCard()
        val rows = details.items.filter { it.travel?.ok == true }
        val traveling = rows.count { it.travel?.state == "traveling" }
        val arrived = rows.count { it.travel?.state == "arrived" }
        val idle = rows.count { it.travel?.state == "idle" && it.travel?.dailyLimitReached != true }
        val done = rows.count { it.travel?.dailyLimitReached == true }
        val stateText = when {
            !details.loaded -> "读取中…"
            rows.isEmpty() -> "暂无旅行状态"
            traveling > 0 -> "$traveling 只在途" + if (done > 0) "，$done 只今日已完成" else ""
            arrived > 0 -> "$arrived 只已到站，待领奖"
            idle > 0 -> "$idle 只可派出"
            else -> "今日旅行已完成"
        }
        card.add(
            cardHeader(
                "Buddy 旅行",
                JLabel(stateText).apply {
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                },
            )
        )
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        if (rows.isEmpty()) {
            card.add(
                hint(
                    if (details.loaded) "接口未返回旅行状态，点右上「刷新」重试。"
                    else "正在读取旅行状态…"
                )
            )
            return card
        }

        // 按账号逐行展示：多账号时只看第一个账号的状态，会与底部巡检结果对不上
        var first = true
        for (item in rows) {
            if (!first) card.add(HairLine())
            first = false
            card.add(travelRow(item))
        }
        card.add(HairLine())
        card.add(
            infoRow(
                name = "待抽奖",
                value = lotteryChances?.let { "$it 次" } ?: if (lotteryLoading) "…" else "—",
                tip = "成长中心抽奖次数（多账号合计）",
                valueTone = AccountUiColors.textPrimary,
            )
        )
        return card
    }

    /** 单个账号的旅行状态行：在途优先显示回来时间，其次才是「今日已完成」 */
    private fun travelRow(item: GrowthTracker.AccountDetail): JComponent {
        val travel = item.travel
        val value = when {
            travel == null || !travel.ok -> "状态读取失败"
            travel.state == "traveling" -> travel.etaText() ?: "在途中"
            travel.state == "arrived" -> "已到站，待领奖"
            travel.dailyLimitReached -> "今日已完成"
            travel.state == "idle" -> "可派出"
            else -> travel.stateLabel
        }
        val tone = when {
            travel == null || !travel.ok -> AccountUiColors.textMuted
            travel.state == "traveling" -> AccountUiColors.textSecondary
            travel.state == "arrived" -> AccountUiColors.success
            travel.dailyLimitReached -> AccountUiColors.textMuted
            else -> AccountUiColors.textPrimary
        }
        val tip = travel?.let {
            buildString {
                append("Buddy ").append(it.stateLabel)
                it.etaText()?.let { eta -> append("（").append(eta).append("）") }
                if (it.dailyLimitReached) append("，今日已派过")
                if (it.rewardCredit > 0) append("，奖励 ").append(trim(it.rewardCredit)).append(" credits")
            }
        } ?: "状态未知"
        return infoRow(
            name = item.displayName.ifBlank { item.accountKey },
            value = value,
            tip = tip,
            valueTone = tone,
        )
    }

    /** 通用两列信息行：左账号/标签，右状态值 */
    private fun infoRow(
        name: String,
        value: String,
        tip: String,
        valueTone: Color = AccountUiColors.textPrimary,
    ): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
            border = JBUI.Borders.empty(6, 0)
            toolTipText = tip
        }
        row.add(
            JLabel(name).apply {
                font = AccountUiType.ui(AccountUiType.label)
                foreground = AccountUiColors.textPrimary
            },
            BorderLayout.WEST,
        )
        row.add(
            JLabel(value).apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = valueTone
                horizontalAlignment = SwingConstants.RIGHT
            },
            BorderLayout.EAST,
        )
        return row
    }

    private fun loadLottery() {
        if (lotteryLoading) return
        lotteryLoading = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = AccountSettings.getInstance()
            val library = AccountLibrary.getInstance()
            val tokens = library.accessTokenMap()
            var total = 0
            var ok = false
            for (s in library.listSaved()) {
                val token = tokens[s.accountKey] ?: continue
                val st = GrowthService.lotteryState(s.domain, token, s.uid, settings.growthEnterpriseId)
                if (st.ok) {
                    total += st.chances
                    ok = true
                }
            }
            val result = if (ok) total else null
            onEdt {
                lotteryLoading = false
                lotteryChances = result
                render()
            }
        }
    }

    // ---------------------------------------------------------------
    // 连续登录
    // ---------------------------------------------------------------

    private fun streakCard(details: GrowthTracker.DetailState): JComponent {
        val card = PlanCard()
        val entry = details.items
            .mapNotNull { it.streak?.takeIf { s -> s.ok } }
            .maxByOrNull { it.days }
        card.add(
            cardHeader(
                "连续登录 ${entry?.days ?: 0} 天",
                JLabel(if (entry != null) "补登卡 ${entry.makeupCards} 张" else "暂无连登数据").apply {
                    font = AccountUiType.ui(AccountUiType.caption)
                    foreground = AccountUiColors.textMuted
                },
            )
        )
        card.add(Box.createVerticalStrut(JBUI.scale(8)))

        val tiers = entry?.tiers ?: emptyList()
        if (tiers.isEmpty()) {
            card.add(hint("点「刷新」读取连续登录档位。"))
            return card
        }
        var first = true
        for (tier in tiers) {
            if (!first) card.add(HairLine())
            first = false
            card.add(tierRow(tier, entry?.days ?: 0))
        }
        return card
    }

    private fun tierRow(tier: GrowthService.RedeemTier, currentDays: Int): JComponent {
        val remain = (tier.days - currentDays).coerceAtLeast(0)
        val row = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
            border = JBUI.Borders.empty(6, 0)
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { isOpaque = false }
        left.add(
            JLabel(tierName(tier.tier)).apply {
                font = AccountUiType.ui(AccountUiType.label)
                foreground = AccountUiColors.textPrimary
            }
        )
        left.add(
            JLabel("${tier.days} 天").apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = AccountUiColors.textMuted
            }
        )
        row.add(left, BorderLayout.WEST)
        row.add(
            JLabel(if (remain <= 0) "已解锁" else "还差 $remain 天").apply {
                font = AccountUiType.ui(AccountUiType.caption)
                foreground = if (remain <= 0) AccountUiColors.success else AccountUiColors.textMuted
                horizontalAlignment = SwingConstants.RIGHT
            },
            BorderLayout.EAST,
        )
        return row
    }

    /** 档位中文名：接口 tier 为 7d / 14d / 28d，与网页档位一致 */
    private fun tierName(tier: String): String = when (tier) {
        "7d" -> "入门档"
        "14d" -> "进阶档"
        "28d" -> "巅峰档"
        else -> tier.ifBlank { "档位" }
    }

    // ---------------------------------------------------------------
    // 底部：账号范围 + 批量动作
    // ---------------------------------------------------------------

    /**
     * 顶部工具栏：账号范围 + 批量动作 + 状态行。
     *
     * 原来放内容最底部，卡片一多就要滚到底才能操作、也看不到执行结果；
     * 现在固定在对话框北侧，不随内容滚动。
     */
    override fun createNorthPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = AccountUiColors.panelBg
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(10, AccountUiSpacing.cardPadH, 8, AccountUiSpacing.cardPadH),
                JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            )
        }
        panel.add(actionBar())
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(statusLabel)
        return panel
    }

    private fun actionBar(): JComponent {
        val wrap = JPanel(FlowWrapLayout(JBUI.scale(6), JBUI.scale(6))).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        accountSelector.preferredSize = Dimension(JBUI.scale(150), accountSelector.preferredSize.height)
        wrap.add(AccountUiKit.styleCombo(accountSelector) { it?.toString().orEmpty() })
        wrap.add(actionButton("一键签到") { runCheckin() })
        wrap.add(actionButton("一键完成") { runAction { cb -> GrowthTracker.completeAll(selectedKeys(), cb) } })
        wrap.add(actionButton("旅行巡检") { runAction { cb -> GrowthTracker.travelAll(selectedKeys(), cb) } })
        wrap.add(actionButton("活跃上报") { runAction { cb -> GrowthTracker.activityAll(selectedKeys(), cb) } })
        wrap.add(actionButton("全部保活") { runAction { cb -> GrowthTracker.keepaliveAll(selectedKeys(), cb) } })
        wrap.add(actionButton("连登兑换") { runBackground { redeemStreak() } })
        wrap.add(actionButton("补签昨日") { runAction { cb -> GrowthTracker.makeupAll(selectedKeys(), cb) } })
        wrap.add(actionButton("抽奖") { runBackground { drawLottery() } })

        val bar = object : JPanel(BorderLayout(JBUI.scale(8), 0)) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        bar.add(wrap, BorderLayout.CENTER)
        bar.add(actionButton("刷新") { reload() }, BorderLayout.EAST)
        return bar
    }

    /** 一键签到：所有账号每日签到（实验特性，接口非官方） */
    private fun runCheckin() {
        if (!AccountSettings.getInstance().checkinEnabled) {
            status("签到已在设置中关闭（Settings | Tools | CodeBuddy 账号管理器）")
            return
        }
        status("执行中：一键签到…")
        CheckinTracker.checkinAll { summary ->
            status(summary)
            render()
        }
    }

    private fun buildAccountSelector() {
        val labels = ArrayList<String>()
        accountKeyByLabel[ALL_ACCOUNTS_LABEL] = ALL_ACCOUNTS
        labels += ALL_ACCOUNTS_LABEL
        AccountLibrary.getInstance().listSaved().forEach { snapshot ->
            var label = snapshot.displayName.ifBlank { snapshot.accountKey }
            if (accountKeyByLabel.containsKey(label)) label = "$label (${snapshot.accountKey})"
            accountKeyByLabel[label] = snapshot.accountKey
            labels += label
        }
        accountSelector.model = DefaultComboBoxModel(labels.toTypedArray())
        accountSelector.selectedIndex = 0
    }

    private fun actionButton(text: String, onClick: () -> Unit): JComponent =
        JellyButton(text, JellyButtonVariant.SECONDARY, null, text, onClick)

    /** 当前选中账号（空集合 = 全部账号） */
    private fun selectedKeys(): Set<String> {
        val label = accountSelector.selectedItem as? String ?: return emptySet()
        val key = accountKeyByLabel[label] ?: ALL_ACCOUNTS
        return if (key == ALL_ACCOUNTS) emptySet() else setOf(key)
    }

    private fun runAction(start: ((String) -> Unit) -> Unit) {
        if (!AccountSettings.getInstance().growthEnabled) {
            status("成长中心已在设置中关闭")
            return
        }
        status("执行中…")
        start { summary ->
            status(summary)
            reload()
        }
    }

    private fun runBackground(block: () -> String) {
        if (!AccountSettings.getInstance().growthEnabled) {
            status("成长中心已在设置中关闭")
            return
        }
        status("执行中…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val summary = try {
                block()
            } catch (t: Throwable) {
                "执行失败：${t.message ?: t.javaClass.simpleName}"
            }
            onEdt {
                status(summary)
                reload()
            }
        }
    }

    /** 连登兑换：兑换所有已解锁（连续登录天数足够）的档位 */
    private fun redeemStreak(): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val tokens = library.accessTokenMap()
        val keys = selectedKeys()
        var ok = 0
        var fail = 0
        val notes = ArrayList<String>()
        for (s in library.listSaved()) {
            if (keys.isNotEmpty() && s.accountKey !in keys) continue
            val token = tokens[s.accountKey] ?: continue
            val st = GrowthService.streak(s.domain, token, s.uid, settings.growthEnterpriseId)
            if (!st.ok) {
                fail++
                notes.add("${s.displayName}：${st.error}")
                continue
            }
            for (tier in st.tiers) {
                if (tier.days > st.days) continue
                val r = GrowthService.redeem(s.domain, token, s.uid, tier.tier, settings.growthEnterpriseId)
                if (r.ok) ok++ else {
                    fail++
                    notes.add("${s.displayName} ${tier.tier}：${r.error}")
                }
            }
        }
        return "兑换 $ok 次" + if (fail > 0) "，失败 $fail 次（${notes.take(2).joinToString("；")}）" else ""
    }

    /** 抽奖一次 */
    private fun drawLottery(): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val tokens = library.accessTokenMap()
        val keys = selectedKeys()
        var ok = 0
        var fail = 0
        val notes = ArrayList<String>()
        for (s in library.listSaved()) {
            if (keys.isNotEmpty() && s.accountKey !in keys) continue
            val token = tokens[s.accountKey] ?: continue
            val r = GrowthService.lotteryDraw(s.domain, token, s.uid, settings.growthEnterpriseId)
            if (r.ok) ok++ else {
                fail++
                notes.add("${s.displayName}：${r.error}")
            }
        }
        return "抽奖 $ok 次" + if (fail > 0) "，失败 $fail 次（${notes.take(2).joinToString("；")}）" else ""
    }

    // ---------------------------------------------------------------
    // 小工具
    // ---------------------------------------------------------------

    private fun cardHeader(title: String, trailing: JComponent): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(7), 0)).apply { isOpaque = false }
        left.add(DotBadge())
        left.add(
            JLabel(title).apply {
                font = AccountUiType.ui(AccountUiType.title, Font.BOLD)
                foreground = AccountUiColors.textPrimary
            }
        )
        row.add(left, BorderLayout.WEST)
        row.add(trailing, BorderLayout.EAST)
        return row
    }

    private fun hint(text: String): JComponent = JLabel(text).apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun status(text: String) {
        statusLabel.text = text
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any())
    }

    private companion object {
        const val ALL_ACCOUNTS = "__all__"
        const val ALL_ACCOUNTS_LABEL = "全部账号"
    }

    // ---------------------------------------------------------------
    // 局部组件：白卡 / 圆点 / 绿色描边按钮 / 统计格
    // ---------------------------------------------------------------

    /** 白色圆角卡片：对应截图里的 `.card` */
    private class PlanCard : JPanel() {
        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(14, 16)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val shape = Jelly.round(
                    0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(),
                    JBUI.scale(8).toFloat(),
                )
                g2.color = AccountUiColors.cardBg
                g2.fill(shape)
                g2.color = PLAN_CARD_BORDER
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** 细进度条：灰底 + 绿色已完成段 */
    private class ProgressLine(private val percent: Double) : JComponent() {
        init {
            isOpaque = false
        }

        override fun getPreferredSize(): Dimension = Dimension(0, JBUI.scale(4))

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(4))

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(0xEEF0F1)
                g2.fill(Jelly.round(0f, 0f, width.toFloat(), height.toFloat(), height / 2f))
                val filled = (width * percent.coerceIn(0.0, 100.0) / 100.0).toFloat()
                if (filled > 0f) {
                    g2.color = PLAN_GREEN
                    g2.fill(Jelly.round(0f, 0f, filled, height.toFloat(), height / 2f))
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** 卡片标题前的灰绿圆点 */
    private class DotBadge : JComponent() {
        init {
            isOpaque = false
            alignmentY = Component.CENTER_ALIGNMENT
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(9), JBUI.scale(18))

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(0xA9B4AC)
                val d = JBUI.scale(7)
                g2.fillOval(0, (height - d) / 2, d, d)
            } finally {
                g2.dispose()
            }
        }
    }

    /** 描边小按钮：白底 + 绿框 + 绿字，悬停浅绿底（对应 `.btn-finish`） */
    private class PlanActionButton(text: String, onClick: () -> Unit) : JComponent() {
        private val label = text
        private var hovering = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = text
            font = AccountUiType.ui(AccountUiType.caption)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovering = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovering = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) onClick()
                }
            })
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            return Dimension(fm.stringWidth(label) + JBUI.scale(26), fm.height + JBUI.scale(10))
        }

        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val shape = Jelly.round(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), JBUI.scale(6).toFloat())
                g2.color = if (hovering) PLAN_GREEN_SOFT else AccountUiColors.cardBg
                g2.fill(shape)
                g2.color = PLAN_GREEN
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
                g2.font = font
                g2.color = PLAN_GREEN
                val fm = g2.fontMetrics
                g2.drawString(label, (width - fm.stringWidth(label)) / 2, (height - fm.height) / 2 + fm.ascent)
            } finally {
                g2.dispose()
            }
        }
    }

}
