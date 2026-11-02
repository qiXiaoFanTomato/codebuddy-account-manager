package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaGroup
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import java.awt.*
import javax.swing.*

/**
 * 额度明细：总量 + 分组 + 每条细项。
 *
 * 数据全部来自接口真实字段：
 *  - 分组 = PackageName（如「CodeBuddy个人体验版」「…运营裂变包」）
 *  - 细项 = 接口 Accounts 数组的一条（SubProductName / used / size / CycleEndTime）
 *
 * 账号下拉选「全部账号」时为多账号汇总视图（仅本地聚合展示，不改变实际扣费账号）。
 */
class QuotaDetailDialog(private val project: Project) : DialogWrapper(project) {

    private val library = AccountLibrary.getInstance()

    private val accountCombo = JComboBox<AccountChoice>()
    private val overviewTitle = JBLabel("正在读取额度…")
    private val overviewSub = JBLabel("")
    private val overviewBar = ProgressBar(0.0)
    private val bodyPanel = JPanel()
    private val statusLabel = JBLabel("")
    private val refreshBtn = JButton("刷新额度")

    private var suppressCombo = false

    init {
        title = "CodeBuddy 额度明细"
        setSize(960, 760)

        overviewTitle.font = overviewTitle.font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
        overviewSub.foreground = AccountUiColors.textMuted
        statusLabel.foreground = AccountUiColors.textMuted

        accountCombo.addActionListener {
            if (!suppressCombo) render()
        }
        refreshBtn.addActionListener { doRefresh() }

        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.border = JBUI.Borders.empty(8, 10)

        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())

        // 顶部：账号选择 + 总览
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10)
        )

        val accountRow = JPanel(BorderLayout(8, 0))
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        left.isOpaque = false
        left.add(JBLabel("账号:"))
        accountCombo.preferredSize = Dimension(JBUI.scale(300), accountCombo.preferredSize.height)
        left.add(accountCombo)
        left.add(refreshBtn)
        accountRow.add(left, BorderLayout.WEST)
        accountRow.add(statusLabel, BorderLayout.CENTER)

        val overview = JPanel()
        overview.layout = BoxLayout(overview, BoxLayout.Y_AXIS)
        overview.border = JBUI.Borders.emptyTop(8)
        overview.add(overviewTitle)
        overview.add(Box.createVerticalStrut(JBUI.scale(4)))
        overview.add(overviewSub)

        val barRow = JPanel(BorderLayout(8, 0))
        barRow.border = JBUI.Borders.emptyTop(6)
        barRow.add(overviewBar, BorderLayout.CENTER)

        top.add(accountRow)
        top.add(overview)
        top.add(barRow)

        root.add(top, BorderLayout.NORTH)
        root.add(JBScrollPane(bodyPanel), BorderLayout.CENTER)
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    // ---------------------------------------------------------------
    // 数据
    // ---------------------------------------------------------------

    private fun reload() {
        val saved = library.listSaved()
        val active = library.currentActive()
        val selectedKey = (accountCombo.selectedItem as? AccountChoice)?.key

        suppressCombo = true
        accountCombo.removeAllItems()
        accountCombo.addItem(AccountChoice(null, "全部账号（汇总）"))
        for (s in saved) {
            accountCombo.addItem(AccountChoice(s.accountKey, "${s.displayName}（${s.domainHost}）"))
        }
        if (saved.isEmpty() && active != null) {
            accountCombo.addItem(AccountChoice(active.accountKey, "${active.displayName}（${active.domainHost}）"))
        }
        val items = (0 until accountCombo.itemCount).map { accountCombo.getItemAt(it) }
        accountCombo.selectedItem = items.firstOrNull { it.key == selectedKey } ?: items.first()
        suppressCombo = false

        render()
    }

    private fun selectedAccounts(): List<CredentialSnapshot> {
        val saved = library.listSaved()
        val key = (accountCombo.selectedItem as? AccountChoice)?.key
        if (key == null) {
            if (saved.isNotEmpty()) return saved
            return listOfNotNull(library.currentActive())
        }
        return saved.filter { it.accountKey == key }
    }

    private fun render() {
        val accountKey = (accountCombo.selectedItem as? AccountChoice)?.key
        val labels = library.listSaved().associate { it.accountKey to it.displayName }
        val quotas = library.quotaCache.loadAll()

        val targets = if (accountKey == null) {
            val saved = library.listSaved()
            if (saved.isNotEmpty()) saved else listOfNotNull(library.currentActive())
        } else {
            library.listSaved().filter { it.accountKey == accountKey }
                .ifEmpty { listOfNotNull(library.currentActive()) }
        }

        val summaries = targets.mapNotNull { s -> quotas[s.accountKey]?.takeIf { it.error == null }?.let { s to it } }

        // 总览：多账号时汇总
        val size = summaries.sumOf { it.second.usableSize }
        val remain = summaries.sumOf { it.second.usableRemain }
        val totalItems = summaries.sumOf { it.second.packages.size }
        val usableItems = summaries.sumOf { it.second.usablePackages.size }
        val percent = if (size <= 0) 0.0 else (remain / size * 100.0)
        val unit = summaries.firstOrNull()?.second?.unit ?: "credits"

        overviewTitle.text = if (summaries.isEmpty()) {
            "暂无额度数据"
        } else {
            "${if (accountKey == null) "所有账号合计" else labels[accountKey] ?: "当前账号"}：" +
                "剩余 ${num(remain)} / ${num(size)} $unit（${"%.0f".format(percent)}%）"
        }

        val nearest = summaries.asSequence()
            .flatMap { it.second.packages.asSequence() }
            .filter { it.usable && it.remain > 0 && it.expireMillis != Long.MAX_VALUE }
            .minByOrNull { it.expireMillis }
        overviewSub.text = buildString {
            append("细项 $totalItems 条，其中仍有效 $usableItems 条")
            if (nearest != null) {
                append("　|　最近到期：${nearest.displayGroup}（${QuotaTime.remainingText(nearest.expireMillis)}）")
            }
            if (summaries.isEmpty()) {
                append("　|　点击「刷新额度」从接口获取")
            }
        }
        overviewBar.percent = percent / 100.0
        overviewBar.repaint()

        // 明细
        bodyPanel.removeAll()
        if (summaries.isEmpty()) {
            bodyPanel.add(hintLabel("没有额度缓存，先点「刷新额度」。"))
        } else {
            for ((snapshot, summary) in summaries) {
                bodyPanel.add(accountHeader(snapshot, summary))
                for (group in summary.groups()) {
                    bodyPanel.add(groupPanel(group))
                    bodyPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
                }
                bodyPanel.add(Box.createVerticalStrut(JBUI.scale(10)))
            }
        }
        bodyPanel.add(Box.createVerticalGlue())
        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    private fun doRefresh() {
        val targets = selectedAccounts()
        if (targets.isEmpty()) {
            statusLabel.text = "账号库为空"
            return
        }
        refreshBtn.isEnabled = false
        statusLabel.text = "正在查询 ${targets.size} 个账号…"
        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = 0
            var fail = 0
            for (s in targets) {
                val q = library.refreshQuota(s.accountKey)
                if (q == null || q.error != null) fail++ else ok++
            }
            ApplicationManager.getApplication().invokeLater {
                refreshBtn.isEnabled = true
                statusLabel.text = if (fail == 0) "刷新完成（$ok 个）" else "成功 $ok 个，失败 $fail 个"
                AccountEvents.fireQuotaChanged()
                reload()
            }
        }
    }

    // ---------------------------------------------------------------
    // 组件
    // ---------------------------------------------------------------

    /** 账号标题行：名称 + 该账号总剩余 */
    private fun accountHeader(snapshot: CredentialSnapshot, summary: QuotaSummary): JPanel {
        val panel = JPanel(BorderLayout(8, 0))
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.border = JBUI.Borders.empty(6, 0, 4, 0)

        val title = JBLabel("${snapshot.displayName}（${snapshot.domainHost}）").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = AccountUiColors.textPrimary
        }

        val total = JBLabel(
            "合计 剩余 ${num(summary.usableRemain)} / ${num(summary.usableSize)} " +
                "（${"%.0f".format(summary.usableRemainPercent)}%）"
        ).apply {
            foreground = AccountUiColors.textMuted
        }

        panel.add(title, BorderLayout.WEST)
        panel.add(total, BorderLayout.EAST)
        return panel
    }

    /** 一个套餐分组：组头可折叠，展开后逐条显示细项 */
    private fun groupPanel(group: QuotaGroup): JPanel {
        val wrapper = JPanel(BorderLayout())
        wrapper.alignmentX = Component.LEFT_ALIGNMENT

        val items = JPanel()
        items.layout = BoxLayout(items, BoxLayout.Y_AXIS)
        items.isVisible = false
        items.border = JBUI.Borders.emptyLeft(20)

        val baseTitle = "${group.name}（${group.packages.size} 条）　" +
            "${group.usageText()} ${group.unit}　${"%.0f".format(group.remainPercent)}%"
        val header = JButton("▸ $baseTitle").apply {
            isFocusPainted = false
            horizontalAlignment = SwingConstants.LEFT
            margin = JBUI.insets(4, 6)
            background = AccountUiColors.cardBg
            addActionListener {
                val expanded = !items.isVisible
                items.isVisible = expanded
                text = (if (expanded) "▾ " else "▸ ") + baseTitle
                wrapper.revalidate()
                wrapper.repaint()
            }
        }

        for (pkg in group.packages.sortedBy { it.expireMillis }) {
            items.add(packageRow(pkg))
        }

        wrapper.add(header, BorderLayout.NORTH)
        wrapper.add(items, BorderLayout.CENTER)
        return wrapper
    }

    /** 单条细项：名称 | 已用/总量 | 到期时间 */
    private fun packageRow(pkg: QuotaPackage): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.border = JBUI.Borders.empty(3, 8)

        val name = JBLabel(shortName(pkg.subProductName.ifBlank { pkg.name })).apply {
            foreground = AccountUiColors.textSecondary
            preferredSize = Dimension(JBUI.scale(320), preferredSize.height)
        }

        val usage = JBLabel(pkg.usageText()).apply {
            foreground = if (pkg.expired) AccountUiColors.textFaint else AccountUiColors.success
        }

        val expireText = when {
            pkg.expired -> "已过期"
            pkg.cycleEnd.isNotBlank() -> "到期 ${pkg.cycleEnd}"
            else -> ""
        }
        val expire = JBLabel(expireText).apply {
            foreground = if (pkg.expired) AccountUiColors.danger else AccountUiColors.textMuted
        }

        row.add(name, BorderLayout.WEST)
        row.add(usage, BorderLayout.CENTER)
        row.add(expire, BorderLayout.EAST)
        return row
    }

    private fun hintLabel(text: String): JBLabel = JBLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(16, 6)
    }

    private fun shortName(name: String): String =
        if (name.length <= 26) name else name.take(25) + "…"

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong()) else String.format("%,.2f", v)

    /** 轻量进度条（不依赖 IDE 内部组件） */
    private class ProgressBar(var percent: Double) : JComponent() {
        init {
            preferredSize = Dimension(0, JBUI.scale(8))
            minimumSize = Dimension(0, JBUI.scale(8))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val h = height.toFloat()
                val w = width.toFloat()
                g2.color = AccountUiColors.barTrack
                g2.fillRoundRect(0, 0, w.toInt(), h.toInt(), h.toInt(), h.toInt())

                val pct = percent.coerceIn(0.0, 1.0)
                if (pct <= 0.0) return
                g2.color = when {
                    pct <= 0.10 -> AccountUiColors.danger
                    pct <= 0.30 -> AccountUiColors.warning
                    else -> AccountUiColors.barFill
                }
                g2.fillRoundRect(0, 0, (w * pct).toInt(), h.toInt(), h.toInt(), h.toInt())
            } finally {
                g2.dispose()
            }
        }
    }

    /** 账号下拉项 */
    private data class AccountChoice(val key: String?, val label: String) {
        override fun toString(): String = label
    }

    companion object {
        fun show(project: Project) {
            QuotaDetailDialog(project).show()
        }
    }
}
