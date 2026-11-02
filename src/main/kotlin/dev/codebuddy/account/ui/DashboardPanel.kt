package dev.codebuddy.account.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.history.HistoryActivity
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.AutoRefreshService
import dev.codebuddy.account.service.QuotaAlertSettings
import dev.codebuddy.account.usage.UsageRecord
import dev.codebuddy.account.usage.UsageTodayTracker
import dev.codebuddy.account.usage.formatCredit
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * 看板：账号 / 额度 / 消耗的总览视图（只读，不改变任何数据）。
 *
 * 六个区块，所有区块都能点击跳到对应功能页：
 *  1. 关键指标：账号总数、额度总剩余、7 天内到期细项、低于告警阈值的账号数
 *  2. 今日指标：今日消耗积分、今日对话（后台异步拉取，有缓存）
 *  3. 消耗明细卡片：今日合计、模型分布与最近几条请求
 *  4. 账号额度排行：按未过期额度的剩余比例排序
 *  5. 到期看板：30 天内仍有余量的细项，按套餐类型合并后展示
 *  6. 数据状态：最近刷新时间、缓存覆盖率、域名分布与快捷入口
 */
class DashboardPanel(
    private val onOpenList: () -> Unit,
    private val onRefreshAll: () -> Unit,
    private val onOpenQuotaDetail: () -> Unit,
    private val onOpenUsageDetail: () -> Unit,
    private val onOpenHistory: () -> Unit,
    private val onOpenReport: () -> Unit,
    private val onCheckin: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : JPanel(BorderLayout()) {

    private val library = AccountLibrary.getInstance()
    private val content = JPanel()
    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    init {
        background = AccountUiColors.panelBg

        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.background = AccountUiColors.panelBg
        content.border = JBUI.Borders.empty(10, 12)

        val scroll = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)
    }

    /** 数据变化后重建看板（EDT 调用） */
    fun update() {
        val saved = library.listSaved()
        val active = library.currentActive()
        val quotas = library.quotaCache.loadAll()
        val threshold = QuotaAlertSettings.getInstance().getState().thresholdPercent

        content.removeAll()
        if (saved.isEmpty()) {
            content.add(emptyHint())
            content.add(Box.createVerticalGlue())
            content.revalidate()
            content.repaint()
            return
        }

        content.add(statusCard(saved, active, quotas, threshold))
        content.add(strut())
        content.add(kpiRow(saved, active, quotas, threshold))
        content.add(strut())
        content.add(todayHeroRow(quotas, saved.size))
        content.add(strut())
        content.add(checkinCard())
        content.add(strut())
        content.add(schedulerCard())
        content.add(strut())
        content.add(usageCard(saved))
        content.add(strut())
        content.add(rankingCard(saved, active, quotas))
        content.add(strut())
        content.add(expiringCard(saved, quotas))
        content.add(Box.createVerticalGlue())

        content.revalidate()
        content.repaint()

        // 今日消耗 / 今日对话 / 今日签到：只在缓存过期时后台刷新，回来后再重建一次
        UsageTodayTracker.ensureFresh { update() }
        HistoryActivity.ensureFresh { update() }
        if (AccountSettings.getInstance().checkinEnabled) {
            CheckinTracker.ensureFresh { update() }
        }
    }

    // ---------------------------------------------------------------
    // 1. 关键指标
    // ---------------------------------------------------------------

    private fun kpiRow(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
        threshold: Int,
    ): JComponent {
        val rows = saved.mapNotNull { s -> quotas[s.accountKey]?.takeIf { it.error == null }?.let { s to it } }
        val size = rows.sumOf { it.second.usableSize }
        val remain = rows.sumOf { it.second.usableRemain }
        val percent = if (size <= 0) 0.0 else remain / size * 100.0
        val unit = rows.firstOrNull()?.second?.unit ?: "credits"

        val expiredTokens = saved.count { it.isRefreshTokenExpired }
        val usableAccounts = saved.count { !it.isRefreshTokenExpired }

        val soon = rows.asSequence()
            .flatMap { it.second.usablePackages.asSequence() }
            .filter { it.remain > 0 && it.expireMillis != Long.MAX_VALUE }
            .filter { it.expireMillis - System.currentTimeMillis() in 0..SEVEN_DAYS_MS }
            .toList()

        val lowAccounts = rows.count { it.second.usableRemainPercent < threshold }

        val row = JPanel(GridLayout(1, 4, JBUI.scale(8), 0)).apply { isOpaque = false }
        row.add(
            kpi(
                title = "账号总数",
                value = saved.size.toString(),
                sub = "可用 $usableAccounts · Token 过期 $expiredTokens",
                color = AccountUiColors.accent,
                tip = "点击查看账号卡片视图",
                onClick = onOpenList,
            )
        )
        row.add(
            kpi(
                title = "额度总剩余",
                value = if (rows.isEmpty()) "—" else "${"%.0f".format(percent)}%",
                sub = if (rows.isEmpty()) "尚未查询" else "剩余 ${num(remain)} / ${num(size)} $unit",
                color = if (rows.isEmpty()) AccountUiColors.textFaint else QuotaCardUi.percentColor(percent),
                tip = "点击查看额度明细",
                onClick = onOpenQuotaDetail,
            )
        )
        row.add(
            kpi(
                title = "7 天内到期",
                value = if (rows.isEmpty()) "—" else "${soon.size} 条",
                sub = if (soon.isEmpty()) "近期无到期额度" else "最近 ${QuotaTime.remainingText(soon.minOf { it.expireMillis })}",
                color = if (soon.isEmpty()) AccountUiColors.textMuted else AccountUiColors.warning,
                tip = "点击查看到期明细",
                onClick = onOpenQuotaDetail,
            )
        )
        row.add(
            kpi(
                title = "低额度账号",
                value = "${lowAccounts} 个",
                sub = "告警阈值 $threshold%（设置可改）",
                color = if (lowAccounts > 0) AccountUiColors.danger else AccountUiColors.success,
                tip = "点击查看各账号额度",
                onClick = onOpenQuotaDetail,
            )
        )
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        row.alignmentX = Component.LEFT_ALIGNMENT
        return row
    }

    private fun kpi(
        title: String,
        value: String,
        sub: String,
        color: Color,
        tip: String,
        onClick: () -> Unit,
    ): JComponent {
        val card = object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true
        ) {
            init {
                border = JBUI.Borders.empty(12, 14)
                preferredSize = Dimension(JBUI.scale(150), JBUI.scale(104))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(104))
                // 标题行：语义色圆点 + 弱化标题，让数值成为唯一视觉焦点
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
                    add(JLabel("●").apply {
                        font = font.deriveFont(Font.PLAIN, JBUI.scale(8).toFloat())
                        foreground = color
                    })
                    add(JLabel(title).apply {
                        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                        foreground = AccountUiColors.textMuted
                    })
                })
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(JLabel(value).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(24).toFloat())
                    foreground = color
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createVerticalStrut(JBUI.scale(2)))
                add(JLabel(sub).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                    foreground = AccountUiColors.textFaint
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }
        card.alignmentX = Component.LEFT_ALIGNMENT
        installCardClick(card, tip, onClick)
        return card
    }

    // ---------------------------------------------------------------
    // 2. 今日指标
    // ---------------------------------------------------------------

    /**
     * 今日主指标：左侧是加大号的「今日消耗积分」（含剩余积分），右侧是「今日对话」。
     *
     * 消耗是用户每天最关心的数字，因此单独占一半宽度并用 38px 字号，
     * 剩余积分放在同一张卡片的右侧做次级强调。
     */
    private fun todayHeroRow(quotas: Map<String, QuotaSummary>, accountCount: Int): JComponent {
        val usage = UsageTodayTracker.state()
        val history = HistoryActivity.state()
        val today = LocalDate.now()
        val todayConversations = history.conversations.filter { sameDay(it.lastMessageAt, today) }
        val conversationAccounts = todayConversations.map { it.sourceUid }.distinct().size

        val remain = quotas.values
            .filter { it.error == null }
            .sumOf { it.usableRemain }
        val unit = quotas.values.firstOrNull { it.error == null }?.unit ?: "credits"
        val quotaAccounts = quotas.values.count { it.error == null }

        val row = JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply { isOpaque = false }
        row.add(
            hero(
                leftTitle = "今日消耗积分",
                leftValue = when {
                    usage.loaded -> formatCredit(usage.totalCredit)
                    usage.loading -> "…"
                    else -> "—"
                },
                leftSub = when {
                    usage.loaded && usage.error != null -> "credits · ${usage.totalRequests} 次请求 · 部分账号读取失败"
                    usage.loaded -> "credits · ${usage.totalRequests} 次请求 · ${usage.accountCount} 个账号"
                    usage.loading -> "正在读取今日消耗…"
                    else -> "等待刷新"
                },
                rightTitle = "剩余积分",
                rightValue = if (quotaAccounts > 0) num(remain) else "—",
                rightSub = if (quotaAccounts > 0) "$unit · $quotaAccounts / $accountCount 个账号已刷新" else "尚未刷新额度",
                valueColor = if (usage.loaded && usage.totalCredit > 0) AccountUiColors.accent else AccountUiColors.textMuted,
                tip = "点击查看消耗明细",
                onClick = onOpenUsageDetail,
            )
        )
        row.add(
            kpi(
                title = "今日对话",
                value = when {
                    history.loaded -> "${todayConversations.size} 个"
                    history.loading -> "…"
                    else -> "—"
                },
                sub = when {
                    history.error != null -> history.error
                    history.loaded -> "涉及 $conversationAccounts 个账号"
                    history.loading -> "正在扫描历史对话…"
                    else -> "等待扫描"
                },
                color = if (todayConversations.isNotEmpty()) AccountUiColors.success else AccountUiColors.textMuted,
                tip = "点击查看今日日报",
                onClick = onOpenReport,
            )
        )
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        row.alignmentX = Component.LEFT_ALIGNMENT
        return row
    }

    /** 加大号数字卡片：左侧主指标 + 右侧次指标 */
    private fun hero(
        leftTitle: String,
        leftValue: String,
        leftSub: String,
        rightTitle: String,
        rightValue: String,
        rightSub: String,
        valueColor: Color,
        tip: String,
        onClick: () -> Unit,
    ): JComponent {
        val card = object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true
        ) {}
        card.border = JBUI.Borders.empty(14, 16)

        val body = FullWidthRow(BorderLayout(JBUI.scale(16), 0))
        body.add(
            heroBlock(leftTitle, leftValue, leftSub, valueColor, JBUI.scale(38), Component.LEFT_ALIGNMENT),
            BorderLayout.WEST
        )
        body.add(
            heroBlock(
                rightTitle, rightValue, rightSub, AccountUiColors.success, JBUI.scale(22), Component.RIGHT_ALIGNMENT
            ),
            BorderLayout.EAST
        )
        card.add(body)
        card.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(132))
        installCardClick(card, tip, onClick)
        return card
    }

    private fun heroBlock(
        title: String,
        value: String,
        sub: String,
        color: Color,
        valueSize: Int,
        align: Float,
    ): JComponent {
        val box = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = align
        }
        box.add(JLabel(title).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textMuted
            alignmentX = align
        })
        box.add(Box.createVerticalStrut(JBUI.scale(2)))
        box.add(JLabel(value).apply {
            font = font.deriveFont(Font.BOLD, valueSize.toFloat())
            foreground = color
            alignmentX = align
        })
        box.add(Box.createVerticalStrut(JBUI.scale(2)))
        box.add(JLabel(sub).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textFaint
            alignmentX = align
        })
        return box
    }

    // ---------------------------------------------------------------
    // 2.5 今日签到
    // ---------------------------------------------------------------

    private fun checkinCard(): JComponent {
        val settings = AccountSettings.getInstance()
        val st = CheckinTracker.state()
        val card = sectionCard(
            "今日签到",
            "一键签到" to onCheckin,
            "刷新" to { CheckinTracker.ensureFresh(force = true) { update() } },
        )

        if (!settings.checkinEnabled) {
            card.add(hint("签到已在设置中关闭（实验特性：接口非官方，可在设置页调整路径或关闭）。"))
            return card
        }
        if (st.running) card.add(hint("正在执行签到…"))
        if (!st.loaded) {
            card.add(hint("尚未查询今日签到状态，点击右上角「一键签到」或「刷新」。"))
            return card
        }
        if (st.items.isEmpty()) {
            card.add(hint("账号库为空，请先导入账号。"))
            return card
        }

        val creditText = if (st.totalCredit > 0) " · 今日获得 ${num(st.totalCredit)} credits" else ""
        val allDone = st.checkedCount == st.items.size
        card.add(JLabel("已签到 ${st.checkedCount} / ${st.items.size} 个账号$creditText").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = if (allDone) AccountUiColors.success else AccountUiColors.textPrimary
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
        })

        for (item in st.items.take(8)) {
            card.add(checkinRow(item))
            card.add(Box.createVerticalStrut(JBUI.scale(3)))
        }
        if (st.items.size > 8) {
            card.add(hint("另有 ${st.items.size - 8} 个账号未展示"))
        }
        if (st.failedCount > 0) {
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
            card.add(hint("${st.failedCount} 个账号失败：多为 token 失效或接口不可用，可在设置页改接口路径"))
        }
        return card
    }

    private fun checkinRow(item: CheckinTracker.AccountCheckin): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        row.border = JBUI.Borders.empty(1, 0)

        val (statusText, statusColor) = when {
            item.failed != null -> "查询失败" to AccountUiColors.danger
            item.result?.already == true -> "今日已签到" to AccountUiColors.success
            item.result?.success == true -> "签到成功" to AccountUiColors.success
            item.status?.checked == true -> "今日已签到" to AccountUiColors.success
            item.status?.checked == false -> "未签到" to AccountUiColors.warning
            else -> "状态未知" to AccountUiColors.textMuted
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        left.add(JLabel(item.displayName).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textPrimary
        })
        left.add(JLabel(statusText).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = statusColor
        })

        val credit = item.credit
        val failed = item.failed
        val rightText = when {
            failed != null -> failed.take(48)
            credit != null && credit > 0 -> "+${num(credit)} credits"
            else -> ""
        }
        val right = JLabel(rightText).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = if (failed != null) AccountUiColors.danger else AccountUiColors.textSecondary
            horizontalAlignment = SwingConstants.RIGHT
            toolTipText = failed
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        return row
    }

    // ---------------------------------------------------------------
    // 2.6 智能调度
    // ---------------------------------------------------------------

    /** 智能调度状态卡：开关、热切换可用性、阈值与冷却 */
    private fun schedulerCard(): JComponent {
        val card = sectionCard("智能调度", "去设置 ›" to onOpenSettings)
        val st = AutoRefreshService.getInstance().autoSwitchState()

        val headline = when {
            !st.enabled -> "未开启"
            !st.hotSwitchAvailable -> "已开启 · 热切换不可用"
            else -> "已开启"
        }
        val color = when {
            !st.enabled -> AccountUiColors.textMuted
            !st.hotSwitchAvailable -> AccountUiColors.warning
            else -> AccountUiColors.success
        }
        card.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            add(JLabel("●").apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(8).toFloat())
                foreground = color
            })
            add(JLabel(headline).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
                foreground = color
            })
        })

        val sub = when {
            !st.enabled ->
                "当前账号额度低于 $st.thresholdPercent% 时不会自动切换；在设置中打开「自动调度」"

            !st.hotSwitchAvailable ->
                "热切换不可用（需 CodeBuddy 插件支持），触发时只会提醒手动切换"

            else -> {
                val cool = if (st.coolingDown) {
                    val minutes = (st.remainingCooldownMs / 60_000L).toInt() + 1
                    " · 冷却中（约 $minutes 分钟后可再触发）"
                } else ""
                val last = if (st.lastSwitchAt > 0) {
                    " · 上次调度 ${timeFmt.format(Instant.ofEpochMilli(st.lastSwitchAt))}"
                } else ""
                "当前账号剩余 < ${st.thresholdPercent}% 时自动切到最优账号$cool$last"
            }
        }
        card.add(JLabel(sub).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textMuted
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(4)
        })
        card.add(hint("调度由后台「自动刷新」任务触发，取决于刷新间隔（默认 15 分钟）。"))
        return card
    }

    // ---------------------------------------------------------------
    // 3. 消耗明细卡片
    // ---------------------------------------------------------------

    private fun usageCard(saved: List<CredentialSnapshot>): JComponent {
        val card = sectionCard(
            "消耗明细 · 今日",
            "刷新" to { UsageTodayTracker.ensureFresh(force = true) { update() } },
            "查看全部 ›" to onOpenUsageDetail,
        )
        val st = UsageTodayTracker.state()
        val labels = saved.associate { it.accountKey to it.displayName }

        val headline = when {
            st.loaded -> "合计 ${formatCredit(st.totalCredit)} credits · ${st.totalRequests} 次请求"
            st.loading -> "正在读取今日消耗…"
            else -> "尚未读取今日消耗"
        }
        card.add(JLabel(headline).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = if (st.loaded) AccountUiColors.textPrimary else AccountUiColors.textMuted
            alignmentX = Component.LEFT_ALIGNMENT
        })

        val models = st.byModel().take(4)
        if (models.isNotEmpty()) {
            card.add(JLabel(
                "模型：" + models.joinToString("　·　") { m ->
                    "${m.model.ifBlank { "-" }} ${formatCredit(m.credit)}（${m.count} 次）"
                }
            ).apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                foreground = AccountUiColors.textFaint
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(3)
            })
        }
        if (st.loaded && st.error != null) {
            card.add(JLabel("部分账号读取失败：${st.error}").apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                foreground = AccountUiColors.danger
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(3)
            })
        }

        val rows = st.latestWithAccount(6)
        if (rows.isEmpty()) {
            card.add(hint(
                when {
                    st.loading -> "正在读取…"
                    st.loaded -> "今天还没有消耗记录"
                    else -> "点击「刷新」读取今天各账号的消耗明细"
                }
            ))
        } else {
            card.add(Box.createVerticalStrut(JBUI.scale(6)))
            for ((accountKey, record) in rows) {
                card.add(usageRow(labels[accountKey] ?: accountKey.take(8), record))
                card.add(Box.createVerticalStrut(JBUI.scale(3)))
            }
            if (st.truncated) {
                card.add(hint("单个账号超过 $UsageTodayTracker.MAX_ROWS_SHOWN 条，仅统计最近一页"))
            }
        }
        installCardClick(card, "点击查看完整消耗明细", onOpenUsageDetail)
        return card
    }

    private fun usageRow(accountLabel: String, record: UsageRecord): JComponent {
        val row = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        row.border = JBUI.Borders.empty(1, 0)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        left.add(JLabel(clock(record.requestTime)).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textMuted
        })
        left.add(JLabel(record.model.ifBlank { "-" }).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textPrimary
        })
        left.add(JLabel(accountLabel).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textFaint
        })

        val right = JLabel("${formatCredit(record.credit)} credits").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textSecondary
            horizontalAlignment = SwingConstants.RIGHT
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        return row
    }

    // ---------------------------------------------------------------
    // 4. 账号额度排行
    // ---------------------------------------------------------------

    private fun rankingCard(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
    ): JComponent {
        val card = sectionCard("账号额度排行（按未过期额度剩余比例）", "额度明细 ›" to onOpenQuotaDetail)

        val ordered = saved
            .map { s -> s to quotas[s.accountKey] }
            .sortedWith(
                compareByDescending<Pair<CredentialSnapshot, QuotaSummary?>> {
                    it.second?.takeIf { q -> q.error == null }?.usableRemainPercent ?: -1.0
                }.thenBy { it.first.displayName }
            )

        for ((s, q) in ordered.take(8)) {
            card.add(rankRow(s, q, s.accountKey == active?.accountKey))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        if (ordered.size > 8) {
            card.add(hint("另有 ${ordered.size - 8} 个账号未展示"))
        }
        return card
    }

    private fun rankRow(s: CredentialSnapshot, quota: QuotaSummary?, active: Boolean): JComponent {
        val row = HoverRow(AccountUiRadius.inner)
        row.layout = BorderLayout(JBUI.scale(8), 0)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(230), JBUI.scale(20))
        }
        left.add(JLabel(s.displayName).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textPrimary
        })
        if (active) left.add(Chip("当前", AccountUiColors.accent, Color.WHITE))
        left.add(JLabel(s.domainHost).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textFaint
        })

        val ok = quota?.takeIf { it.error == null }
        val bar = QuotaBar(ok?.usableRemainPercent ?: 0.0)
        val right = JLabel(
            when {
                quota == null -> "未刷新"
                quota.error != null -> "查询失败"
                ok == null || ok.packages.isEmpty() -> "无套餐"
                else -> "${"%.0f".format(ok.usableRemainPercent)}%　" +
                    "${num(ok.usableRemain)} / ${num(ok.usableSize)} ${ok.unit}"
            }
        ).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = when {
                quota == null -> AccountUiColors.textFaint
                quota.error != null -> AccountUiColors.danger
                ok == null -> AccountUiColors.textMuted
                else -> AccountUiColors.textSecondary
            }
            horizontalAlignment = SwingConstants.RIGHT
        }
        right.preferredSize = Dimension(JBUI.scale(180), right.preferredSize.height)

        // 进度条套一层，留出上下间距，避免在整行高度里被拉粗
        val barWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0)
            add(bar, BorderLayout.CENTER)
        }

        row.add(left, BorderLayout.WEST)
        row.add(barWrap, BorderLayout.CENTER)
        row.add(right, BorderLayout.EAST)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        installCardClick(row, "在账号卡片视图中查看 ${s.displayName}", onOpenList)
        return row
    }

    // ---------------------------------------------------------------
    // 5. 到期看板（同类型合并）
    // ---------------------------------------------------------------

    /** 同一套餐类型在多个账号 / 多个细项上的聚合结果 */
    private class ExpiringGroup(val label: String) {
        val accounts = LinkedHashSet<String>()
        var count = 0
        var remain = 0.0
        var size = 0.0
        var unit = ""
        var nearest = Long.MAX_VALUE
    }

    private fun expiringCard(saved: List<CredentialSnapshot>, quotas: Map<String, QuotaSummary>): JComponent {
        val card = sectionCard("到期看板（30 天内，已按套餐类型合并）", "额度明细 ›" to onOpenQuotaDetail)

        val now = System.currentTimeMillis()
        val groups = LinkedHashMap<String, ExpiringGroup>()

        for (s in saved) {
            val packages = quotas[s.accountKey]?.takeIf { it.error == null }?.usablePackages ?: continue
            for (pkg in packages) {
                if (pkg.remain <= 0 || pkg.expireMillis == Long.MAX_VALUE) continue
                val left = pkg.expireMillis - now
                if (left < 0 || left > THIRTY_DAYS_MS) continue

                val key = pkg.displayGroup.ifBlank { pkg.name.ifBlank { "其他" } }
                val group = groups.getOrPut(key) { ExpiringGroup(QuotaCardUi.shortName(key)) }
                group.accounts.add(s.accountKey)
                group.count++
                group.remain += pkg.remain
                group.size += pkg.size
                if (group.unit.isBlank()) group.unit = pkg.unit
                if (pkg.expireMillis < group.nearest) group.nearest = pkg.expireMillis
            }
        }

        if (groups.isEmpty()) {
            card.add(hint("30 天内没有到期额度。"))
            return card
        }

        val ordered = groups.values.sortedBy { it.nearest }
        for (group in ordered.take(8)) {
            card.add(expiringRow(group, now))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        if (ordered.size > 8) {
            card.add(hint("另有 ${ordered.size - 8} 类未展示"))
        }
        return card
    }

    private fun expiringRow(group: ExpiringGroup, now: Long): JComponent {
        val row = HoverRow(AccountUiRadius.inner)
        row.layout = BorderLayout(JBUI.scale(8), 0)

        val days = ((group.nearest - now) / 86_400_000L).toInt()
        val chipColor = when {
            days <= 1 -> AccountUiColors.danger
            days <= 3 -> AccountUiColors.warning
            else -> AccountUiColors.textMuted
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        left.add(Chip(if (days <= 0) "<1 天" else "$days 天", QuotaCardUi.itemBg, chipColor))
        left.add(JLabel(group.label).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textPrimary
        })
        left.add(JLabel("${group.accounts.size} 个账号 · ${group.count} 条").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textFaint
        })

        val right = JLabel("合计剩余 ${num(group.remain)} / ${num(group.size)} ${group.unit}").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textSecondary
            horizontalAlignment = SwingConstants.RIGHT
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        installCardClick(row, "在额度明细中查看 ${group.label}", onOpenQuotaDetail)
        return row
    }

    // ---------------------------------------------------------------
    // 6. 数据状态
    // ---------------------------------------------------------------

    private fun statusCard(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
        threshold: Int,
    ): JComponent {
        val card = sectionCard("数据状态")

        val cached = saved.count { quotas[it.accountKey]?.error == null && quotas[it.accountKey]?.packages?.isNotEmpty() == true }
        val latest = quotas.values.map { it.fetchedAt }.maxOrNull() ?: 0L
        val domains = saved.groupingBy { it.domainHost }.eachCount()
        val domainText = domains.entries.sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key} ${it.value}" }

        // 两列对齐：标签列固定宽度由 GridBagLayout 自行取最宽项，值列撑满剩余宽度
        val body = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        var rowIndex = 0
        fun kv(
            label: String,
            value: String,
            valueColor: Color = AccountUiColors.textSecondary,
            tip: String? = null,
        ) {
            body.add(
                JLabel(label).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    foreground = AccountUiColors.textMuted
                },
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = rowIndex
                    anchor = GridBagConstraints.WEST
                    weightx = 0.0
                    insets = JBUI.insets(2, 0, 2, JBUI.scale(16))
                }
            )
            body.add(
                JLabel(value).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    foreground = valueColor
                    toolTipText = tip
                },
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = rowIndex
                    anchor = GridBagConstraints.WEST
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(2, 0, 2, 0)
                }
            )
            rowIndex++
        }

        kv("当前账号", active?.let { "${it.displayName}（${it.domainHost}）" } ?: "未检测到")
        kv("最近刷新", if (latest <= 0) "尚未刷新" else timeFmt.format(Instant.ofEpochMilli(latest)))
        kv("额度缓存", "$cached / ${saved.size} 个账号")
        kv("今日消耗", usageStatusText(), AccountUiColors.accent)
        kv(
            "告警阈值",
            "$threshold%（Settings | Tools | CodeBuddy 账号管理器）",
            tip = "低额度提醒阈值，可在设置中调整",
        )
        kv("认证域分布", domainText, tip = domainText)

        body.maximumSize = Dimension(Int.MAX_VALUE, body.preferredSize.height)
        card.add(body)
        card.add(Box.createVerticalStrut(JBUI.scale(9)))
        card.add(thinDivider())
        card.add(Box.createVerticalStrut(JBUI.scale(7)))

        // 操作按钮在卡片右下角排队
        val actionRow = FullWidthRow(BorderLayout())
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        buttons.add(smallButton("刷新全部额度", onRefreshAll))
        buttons.add(smallButton("额度明细", onOpenQuotaDetail))
        buttons.add(smallButton("消耗明细", onOpenUsageDetail))
        buttons.add(smallButton("历史对话", onOpenHistory))
        buttons.add(smallButton("对话日报", onOpenReport))
        actionRow.add(buttons, BorderLayout.EAST)
        card.add(actionRow)
        return card
    }

    private fun usageStatusText(): String {
        val st = UsageTodayTracker.state()
        return when {
            st.loaded && st.error != null ->
                "合计 ${formatCredit(st.totalCredit)} credits（${st.accountCount} 个账号，部分失败）"
            st.loaded -> "合计 ${formatCredit(st.totalCredit)} credits · ${st.totalRequests} 次请求"
            st.loading -> "正在读取…"
            else -> "尚未读取"
        }
    }

    /** 1px 分隔线：卡片内上半部分是信息、下半部分是操作 */
    private fun thinDivider(): JComponent = JPanel().apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        preferredSize = Dimension(0, 1)
        minimumSize = Dimension(0, 1)
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        border = JBUI.Borders.customLine(AccountUiColors.divider, 1, 0, 0, 0)
    }

    private fun smallButton(text: String, action: () -> Unit): JButton = JButton(text).apply {
        isFocusPainted = false
        margin = JBUI.insets(3, 10)
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        addActionListener { action() }
    }

    // ---------------------------------------------------------------
    // 通用
    // ---------------------------------------------------------------

    /**
     * 整块卡片可点击：把点击 / 悬停装到卡片及其所有子组件上，
     * 否则点在 JLabel 上不会冒泡到卡片，看起来像「点不动」。
     */
    private fun installCardClick(card: JComponent, tip: String, action: () -> Unit) {
        card.toolTipText = card.toolTipText ?: tip
        val hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val rounded = card as? RoundedCard
        rounded?.let { if (it.hoverFill == null) it.hoverFill = AccountUiColors.cardHoverBg }

        fun hover(value: Boolean) {
            rounded?.setHovering(value)
        }

        fun walk(component: Component) {
            // 按钮 / 链接自己有动作，不再叠卡片点击，否则点「刷新」会连带打开明细窗口
            if (component is AbstractButton) return
            component.cursor = hand
            component.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) action()
                }

                override fun mouseEntered(e: MouseEvent) = hover(true)

                override fun mouseExited(e: MouseEvent) {
                    // 在子组件之间移动也会触发 exited，用卡片坐标判断是否真的离开
                    val point = SwingUtilities.convertPoint(component, e.point, card)
                    if (!card.contains(point.x, point.y)) hover(false)
                }
            })
            if (component is Container) {
                for (child in component.components) walk(child)
            }
        }
        walk(card)
    }

    private fun sectionCard(title: String, vararg actions: Pair<String, () -> Unit>): RoundedCard {
        val card = object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true
        ) {}
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.border = JBUI.Borders.empty(12, 14)

        val header = FullWidthRow(BorderLayout(JBUI.scale(8), 0))
        header.add(JLabel(title).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = AccountUiColors.textPrimary
            // 左侧强调线：区段标题与卡片内容形成清晰分隔
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(AccountUiColors.accent, 0, 3, 0, 0),
                JBUI.Borders.empty(2, 6, 2, 4)
            )
        }, BorderLayout.WEST)

        if (actions.isNotEmpty()) {
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false }
            for ((text, action) in actions) {
                right.add(ActionLink(text) { action() })
            }
            header.add(right, BorderLayout.EAST)
        }

        card.add(header)
        card.add(Box.createVerticalStrut(JBUI.scale(8)))
        return card
    }

    private fun hint(text: String): JBLabel = JBLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(2, 0)
    }

    private fun strut(): Component = Box.createVerticalStrut(JBUI.scale(10))

    private fun emptyHint(): JComponent {
        val box = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(40, 10)
        }
        box.add(JLabel("看板暂无数据").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
            foreground = AccountUiColors.textSecondary
        })
        box.add(Box.createVerticalStrut(JBUI.scale(6)))
        box.add(JLabel("先从 auth 目录导入账号，再刷新额度。").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textFaint
        })
        return box
    }

    /** 会话时间是否为今天（ISO-8601，解析失败视为不是） */
    private fun sameDay(iso: String, day: LocalDate): Boolean {
        if (iso.isBlank()) return false
        return try {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() == day
        } catch (_: Exception) {
            false
        }
    }

    /** "2026-11-02 14:40:00" -> "14:40" */
    private fun clock(requestTime: String): String {
        val index = requestTime.indexOf(' ')
        if (index < 0) return requestTime.take(5)
        return requestTime.substring(index + 1).take(5)
    }

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong()) else String.format("%,.2f", v)

    private companion object {
        const val SEVEN_DAYS_MS = 7L * 86_400_000L
        const val THIRTY_DAYS_MS = 30L * 86_400_000L
    }
}
