package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import java.awt.*
import javax.swing.*

/**
 * 额度明细（卡片式）。
 *
 * 数据全部来自接口真实字段：
 *  - 分组 = PackageName（如「CodeBuddy个人体验版」「…运营裂变包」）
 *  - 细项 = 接口 Accounts 数组的一条（SubProductName / used / size / CycleEndTime）
 *
 * 账号下拉选「全部账号」时每个账号一张卡片，纵向排列；卡片内按套餐分组折叠展示细项。
 */
class QuotaDetailDialog(private val project: Project) : DialogWrapper(project) {

    private val library = AccountLibrary.getInstance()

    private val accountCombo = JComboBox<AccountChoice>()
    private val overviewTitle = JBLabel("正在读取额度…")
    private val overviewSub = JBLabel("")
    private val overviewBar = QuotaBar(0.0)
    private val bodyPanel = JPanel()
    private val statusLabel = JBLabel("")
    private val refreshBtn = JButton("刷新额度")

    private var suppressCombo = false

    init {
        title = "CodeBuddy 额度明细"
        setSize(1000, 780)

        overviewTitle.font = overviewTitle.font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
        overviewSub.foreground = AccountUiColors.textMuted
        statusLabel.foreground = AccountUiColors.textMuted

        accountCombo.addActionListener {
            if (!suppressCombo) render()
        }
        refreshBtn.addActionListener { doRefresh() }

        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.background = AccountUiColors.panelBg
        bodyPanel.border = JBUI.Borders.empty(10, 12)

        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.panelBg

        // 顶部：账号选择 + 总览
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.background = AccountUiColors.surfaceBg
        top.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10)
        )

        val accountRow = JPanel(BorderLayout(8, 0))
        accountRow.isOpaque = false
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
        overview.isOpaque = false
        overview.border = JBUI.Borders.emptyTop(8)
        overview.add(overviewTitle)
        overview.add(Box.createVerticalStrut(JBUI.scale(4)))
        overview.add(overviewSub)

        val barRow = JPanel(BorderLayout(8, 0))
        barRow.isOpaque = false
        barRow.border = JBUI.Borders.emptyTop(6)
        barRow.add(overviewBar, BorderLayout.CENTER)

        top.add(accountRow)
        top.add(overview)
        top.add(barRow)

        val scroll = JBScrollPane(bodyPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        root.add(top, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
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
        val quotas = library.quotaCache.loadAll()
        val activeKey = library.currentActive()?.accountKey

        val targets = if (accountKey == null) {
            val saved = library.listSaved()
            if (saved.isNotEmpty()) saved else listOfNotNull(library.currentActive())
        } else {
            library.listSaved().filter { it.accountKey == accountKey }
                .ifEmpty { listOfNotNull(library.currentActive()) }
        }

        // 有缓存的才算「有数据」，查询失败也保留卡片（展示失败原因）
        val cards = targets.map { s -> s to quotas[s.accountKey] }

        // 总览：多账号时汇总
        val summaries = cards.mapNotNull { (_, q) -> q?.takeIf { it.error == null } }
        val size = summaries.sumOf { it.usableSize }
        val remain = summaries.sumOf { it.usableRemain }
        val totalItems = summaries.sumOf { it.packages.size }
        val usableItems = summaries.sumOf { it.usablePackages.size }
        val percent = if (size <= 0) 0.0 else (remain / size * 100.0)
        val unit = summaries.firstOrNull()?.unit ?: "credits"

        overviewTitle.text = if (summaries.isEmpty()) {
            "暂无额度数据"
        } else {
            "${if (accountKey == null) "所有账号合计" else cards.firstOrNull()?.first?.displayName ?: "当前账号"}：" +
                "剩余 ${num(remain)} / ${num(size)} $unit（${"%.0f".format(percent)}%）"
        }

        val nearest = summaries.asSequence()
            .flatMap { it.packages.asSequence() }
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
        overviewBar.percent = percent

        // 卡片：当前账号置顶，其余按剩余比例倒序
        bodyPanel.removeAll()
        if (cards.isEmpty()) {
            bodyPanel.add(hintLabel("账号库为空，先导入账号。"))
        } else {
            val ordered = cards.sortedWith(
                compareByDescending<Pair<CredentialSnapshot, QuotaSummary?>> { it.first.accountKey == activeKey }
                    .thenByDescending { it.second?.takeIf { q -> q.error == null }?.usableRemainPercent ?: -1.0 }
                    .thenBy { it.first.displayName }
            )
            for ((snapshot, summary) in ordered) {
                val card = QuotaAccountCard(snapshot, summary, snapshot.accountKey == activeKey)
                card.alignmentX = Component.LEFT_ALIGNMENT
                bodyPanel.add(card)
                bodyPanel.add(Box.createVerticalStrut(JBUI.scale(10)))
            }
            bodyPanel.add(Box.createVerticalGlue())
        }
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

    private fun hintLabel(text: String): JBLabel = JBLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(16, 6)
    }

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong()) else String.format("%,.2f", v)

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
