package dev.codebuddy.account.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.QuotaAlertSettings
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * 看板：账号 / 额度的总览视图（只读，不改变任何数据）。
 *
 * 四个区块：
 *  1. 关键指标：账号总数、额度总剩余、7 天内到期细项、低于告警阈值的账号数
 *  2. 账号额度排行：按未过期额度的剩余比例排序，点击回到账号列表
 *  3. 到期看板：30 天内仍有余量的细项，按到期时间升序
 *  4. 数据状态：最近刷新时间、缓存覆盖率、域名分布与快捷入口
 */
class DashboardPanel(
    private val onOpenList: () -> Unit,
    private val onRefreshAll: () -> Unit,
    private val onOpenQuotaDetail: () -> Unit,
    private val onOpenUsageDetail: () -> Unit,
    private val onOpenHistory: () -> Unit,
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

        content.add(kpiRow(saved, active, quotas, threshold))
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(rankingCard(saved, active, quotas))
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(expiringCard(saved, quotas))
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(statusCard(saved, active, quotas, threshold))
        content.add(Box.createVerticalGlue())

        content.revalidate()
        content.repaint()
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
            )
        )
        row.add(
            kpi(
                title = "额度总剩余",
                value = if (rows.isEmpty()) "—" else "${"%.0f".format(percent)}%",
                sub = if (rows.isEmpty()) "尚未查询" else "剩余 ${num(remain)} / ${num(size)} $unit",
                color = if (rows.isEmpty()) AccountUiColors.textFaint else QuotaCardUi.percentColor(percent),
            )
        )
        row.add(
            kpi(
                title = "7 天内到期",
                value = if (rows.isEmpty()) "—" else "${soon.size} 条",
                sub = if (soon.isEmpty()) "近期无到期额度" else "最近 ${QuotaTime.remainingText(soon.minOf { it.expireMillis })}",
                color = if (soon.isEmpty()) AccountUiColors.textMuted else AccountUiColors.warning,
            )
        )
        row.add(
            kpi(
                title = "低额度账号",
                value = "${lowAccounts} 个",
                sub = "告警阈值 $threshold%（设置可改）",
                color = if (lowAccounts > 0) AccountUiColors.danger else AccountUiColors.success,
            )
        )
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        row.alignmentX = Component.LEFT_ALIGNMENT
        return row
    }

    private fun kpi(title: String, value: String, sub: String, color: Color): JComponent =
        object : RoundedCard(
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

    // ---------------------------------------------------------------
    // 2. 账号额度排行
    // ---------------------------------------------------------------

    private fun rankingCard(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
    ): JComponent {
        val card = sectionCard("账号额度排行（按未过期额度剩余比例）")

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
        val row = HoverRow(AccountUiRadius.inner).apply {
            layout = BorderLayout(JBUI.scale(8), 0)
            toolTipText = "在账号列表中查看 ${s.displayName}"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onOpenList()
            })
        }

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
        return row
    }

    // ---------------------------------------------------------------
    // 3. 到期看板
    // ---------------------------------------------------------------

    private fun expiringCard(saved: List<CredentialSnapshot>, quotas: Map<String, QuotaSummary>): JComponent {
        val card = sectionCard("到期看板（30 天内，仍有余量）")

        val labels = saved.associate { it.accountKey to it.displayName }
        val now = System.currentTimeMillis()
        val entries = saved.asSequence()
            .flatMap { s ->
                (quotas[s.accountKey]?.takeIf { it.error == null }?.usablePackages ?: emptyList())
                    .asSequence()
                    .filter { it.remain > 0 && it.expireMillis != Long.MAX_VALUE }
                    .filter { it.expireMillis - now in 0..THIRTY_DAYS_MS }
                    .map { pkg -> Triple(s.accountKey, pkg, pkg.expireMillis - now) }
            }
            .sortedBy { it.third }
            .take(10)
            .toList()

        if (entries.isEmpty()) {
            card.add(hint("30 天内没有到期额度。"))
            return card
        }

        for ((accountKey, pkg, leftMs) in entries) {
            card.add(expiringRow(labels[accountKey] ?: accountKey.take(8), pkg, leftMs))
            card.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        return card
    }

    private fun expiringRow(accountLabel: String, pkg: QuotaPackage, leftMs: Long): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val days = (leftMs / 86_400_000L).toInt()
        val chipColor = when {
            days <= 1 -> AccountUiColors.danger
            days <= 3 -> AccountUiColors.warning
            else -> AccountUiColors.textMuted
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        left.add(Chip(if (days <= 0) "<1 天" else "$days 天", QuotaCardUi.itemBg, chipColor))
        left.add(JLabel(QuotaCardUi.shortName(pkg.displayGroup)).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textPrimary
        })
        left.add(JLabel(accountLabel).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textFaint
        })

        val right = JLabel("剩余 ${num(pkg.remain)} / ${num(pkg.size)} ${pkg.unit}").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = AccountUiColors.textSecondary
            horizontalAlignment = SwingConstants.RIGHT
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    // ---------------------------------------------------------------
    // 4. 数据状态
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

        card.add(infoRow("当前账号", active?.let { "${it.displayName}（${it.domainHost}）" } ?: "未检测到"))
        card.add(infoRow("最近刷新", if (latest <= 0) "尚未刷新" else timeFmt.format(Instant.ofEpochMilli(latest))))
        card.add(infoRow("额度缓存", "$cached / ${saved.size} 个账号"))
        card.add(infoRow("告警阈值", "$threshold%（Settings | Tools | CodeBuddy 账号管理器）"))
        card.add(
            infoRow(
                "认证域分布",
                domains.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" }
            )
        )
        card.add(Box.createVerticalStrut(JBUI.scale(6)))

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        actions.add(smallButton("刷新全部额度", onRefreshAll))
        actions.add(smallButton("额度明细", onOpenQuotaDetail))
        actions.add(smallButton("消耗明细", onOpenUsageDetail))
        actions.add(smallButton("历史对话", onOpenHistory))
        card.add(actions)
        return card
    }

    private fun infoRow(label: String, value: String): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
        }
        row.add(JLabel(label).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textMuted
            preferredSize = Dimension(JBUI.scale(90), preferredSize.height)
        }, BorderLayout.WEST)
        row.add(JLabel(value).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = AccountUiColors.textSecondary
        }, BorderLayout.CENTER)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
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

    private fun sectionCard(title: String): RoundedCard {
        val card = object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true
        ) {}
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.border = JBUI.Borders.empty(12, 14)
        card.add(JLabel(title).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = AccountUiColors.textPrimary
            alignmentX = Component.LEFT_ALIGNMENT
            // 左侧强调线：区段标题与卡片内容形成清晰分隔
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(AccountUiColors.accent, 0, 0, 0, 3),
                JBUI.Borders.empty(2, 7, 2, 4)
            )
        })
        card.add(Box.createVerticalStrut(JBUI.scale(8)))
        return card
    }

    private fun hint(text: String): JBLabel = JBLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(2, 0)
    }

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

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong()) else String.format("%,.2f", v)

    private companion object {
        const val SEVEN_DAYS_MS = 7L * 86_400_000L
        const val THIRTY_DAYS_MS = 30L * 86_400_000L
    }
}
