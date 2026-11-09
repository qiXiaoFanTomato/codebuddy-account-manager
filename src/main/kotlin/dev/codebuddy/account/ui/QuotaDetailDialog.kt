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
class QuotaDetailDialog(
    private val project: Project,
    private val mode: Mode = Mode.ALL,
) : DialogWrapper(project) {

    /** 视图模式：全部额度 / 只看 7 天内到期的临期额度（按账号成卡） */
    enum class Mode { ALL, EXPIRING }

    private val library = AccountLibrary.getInstance()

    private val accountCombo = JComboBox<AccountChoice>()
    private val overviewTitle = JBLabel("正在读取额度…")
    private val overviewSub = JBLabel("")
    private val overviewBar = QuotaBar(0.0)
    private val bodyPanel = JPanel()
    private val statusLabel = JBLabel("")
    private val refreshBtn: JellyButton =
        JellyButton("刷新额度", JellyButtonVariant.PRIMARY, null, "重新查询额度") { doRefresh() }
    private lateinit var topBar: DialogTopBar

    private var suppressCombo = false

    init {
        title = if (mode == Mode.EXPIRING) "CodeBuddy 临期额度" else "CodeBuddy 额度明细"
        setSize(1000, 780)

        overviewTitle.font = overviewTitle.font.deriveFont(Font.BOLD, AccountUiType.title)
        // JBLabel 默认前景取自 LAF（深色主题是浅白），纸面上会白字白底
        overviewTitle.foreground = AccountUiColors.textPrimary
        overviewSub.foreground = AccountUiColors.textMuted
        statusLabel.foreground = AccountUiColors.textMuted
        AccountUiKit.styleCombo(accountCombo) { (it as? AccountChoice)?.label ?: "" }

        accountCombo.addActionListener {
            if (!suppressCombo) render()
        }
        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.background = AccountUiColors.panelBg
        bodyPanel.border = JBUI.Borders.empty(AccountUiSpacing.cardGap, AccountUiSpacing.cardPadH)

        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.panelBg

        // 顶部：账号选择 + 状态（统一顶栏样式）；临期视图固定看全部账号，不放下拉
        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        filterRow.isOpaque = false
        if (mode == Mode.ALL) {
            filterRow.add(JBLabel("账号").apply { foreground = AccountUiColors.textSecondary })
            accountCombo.preferredSize = Dimension(JBUI.scale(280), accountCombo.preferredSize.height)
            filterRow.add(accountCombo)
        } else {
            filterRow.add(JBLabel("只显示 7 天内到期、仍有剩余的额度").apply {
                foreground = AccountUiColors.textSecondary
            })
        }
        filterRow.add(refreshBtn)

        val top = DialogTopBar(dialogTitle(), "", filterRow)
        topBar = top

        val scroll = AccountUiKit.styleScroll(JBScrollPane(bodyPanel)).apply {
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
        val now = System.currentTimeMillis()
        val cards = targets.map { s ->
            val summary = quotas[s.accountKey]
            s to if (mode == Mode.EXPIRING) expiringOnly(summary, now) else summary
        }
        // 临期视图只保留真有临期细项的账号，其余账号不占版面
        val visibleCards = if (mode == Mode.EXPIRING) {
            cards.filter { (_, q) -> q?.takeIf { it.error == null }?.packages?.isNotEmpty() == true }
        } else {
            cards
        }

        // 总览：多账号时汇总
        val summaries = visibleCards.mapNotNull { (_, q) -> q?.takeIf { it.error == null } }
        val size = summaries.sumOf { it.usableSize }
        val remain = summaries.sumOf { it.usableRemain }
        val totalItems = summaries.sumOf { it.packages.size }
        val usableItems = summaries.sumOf { it.usablePackages.size }
        val percent = if (size <= 0) 0.0 else (remain / size * 100.0)
        val unit = summaries.firstOrNull()?.unit ?: "credits"

        overviewTitle.text = when {
            summaries.isEmpty() && mode == Mode.EXPIRING -> "近 7 天没有到期额度"
            summaries.isEmpty() -> "暂无额度数据"
            mode == Mode.EXPIRING ->
                "${visibleCards.size} 个账号有临期额度：" +
                    "合计剩余 ${num(remain)} / ${num(size)} $unit"
            else ->
                "${if (accountKey == null) "所有账号合计" else cards.firstOrNull()?.first?.displayName ?: "当前账号"}：" +
                    "剩余 ${num(remain)} / ${num(size)} $unit（${"%.0f".format(percent)}%）"
        }

        val nearest = summaries.asSequence()
            .flatMap { it.packages.asSequence() }
            .filter { it.usable && it.remain > 0 && it.expireMillis != Long.MAX_VALUE }
            .minByOrNull { it.expireMillis }
        overviewSub.text = buildString {
            if (mode == Mode.EXPIRING) {
                append("临期细项 $totalItems 条")
            } else {
                append("细项 $totalItems 条，其中仍有效 $usableItems 条")
            }
            if (nearest != null) {
                append("　|　最近到期：${nearest.displayGroup}（${QuotaTime.remainingText(nearest.expireMillis)}）")
            }
            if (summaries.isEmpty()) {
                append("　|　点击「刷新额度」从接口获取")
            }
        }
        overviewBar.percent = percent

        if (::topBar.isInitialized) {
            topBar.update(dialogTitle(), statusLabel.text.ifBlank { overviewSub.text })
        }

        // 卡片：临期视图按最近到期升序（越急越前）；普通视图当前账号置顶、其余按剩余比例倒序
        bodyPanel.removeAll()
        bodyPanel.add(overviewCard())
        bodyPanel.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.cardGap)))
        if (cards.isEmpty()) {
            bodyPanel.add(hintLabel("账号库为空，先导入账号。"))
        } else if (visibleCards.isEmpty()) {
            bodyPanel.add(hintLabel("近 7 天内没有到期额度（已过期或长期有效的细项不在此列出）。"))
            bodyPanel.add(Box.createVerticalGlue())
        } else {
            val ordered = if (mode == Mode.EXPIRING) {
                visibleCards.sortedWith(
                    compareBy<Pair<CredentialSnapshot, QuotaSummary?>> {
                        it.second?.nextExpiring?.expireMillis ?: Long.MAX_VALUE
                    }.thenBy { it.first.displayName }
                )
            } else {
                visibleCards.sortedWith(
                    compareByDescending<Pair<CredentialSnapshot, QuotaSummary?>> { it.first.accountKey == activeKey }
                        .thenByDescending { it.second?.takeIf { q -> q.error == null }?.usableRemainPercent ?: -1.0 }
                        .thenBy { it.first.displayName }
                )
            }
            for ((snapshot, summary) in ordered) {
                val card = QuotaAccountCard(
                    snapshot, summary, snapshot.accountKey == activeKey,
                    expandGroups = mode == Mode.EXPIRING,
                )
                card.alignmentX = Component.LEFT_ALIGNMENT
                bodyPanel.add(card)
                bodyPanel.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.cardGap)))
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

    /** 概览卡：总览标题 + 说明 + 总进度条，作为内容区第一屏 */
    private fun overviewCard(): JComponent {
        val card = object : RoundedCard(
            AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true
        ) {}
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.border = JBUI.Borders.empty(AccountUiSpacing.cardPadV, AccountUiSpacing.cardPadH)

        card.add(SectionHeader("额度总览"))
        card.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))
        card.add(overviewTitle)
        card.add(Box.createVerticalStrut(JBUI.scale(2)))
        card.add(overviewSub)
        card.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))
        card.add(overviewBar)
        return card
    }

    private fun num(v: Double): String = AccountUiKit.formatNumber(v)

    private fun dialogTitle(): String =
        if (mode == Mode.EXPIRING) "CodeBuddy 临期额度" else "CodeBuddy 额度明细"

    companion object {

        /** 临期窗口：与看板「7 天内到期」指标同一口径 */
        private const val EXPIRING_WINDOW_MS = 7L * 24 * 3600 * 1000

        /**
         * 只保留临期细项：仍有剩余、未过期、且 7 天内到期。
         *
         * 返回副本而不是改原缓存，额度缓存要留给其它视图用全量数据。
         */
        private fun expiringOnly(summary: QuotaSummary?, now: Long): QuotaSummary? {
            if (summary == null || summary.error != null) return summary
            return summary.copy(
                packages = summary.packages.filter {
                    it.usable && it.remain > 0 && it.expireMillis - now in 0..EXPIRING_WINDOW_MS
                }
            )
        }

        fun show(project: Project) {
            QuotaDetailDialog(project).show()
        }

        /** 只看临期额度（看板「7 天内到期」卡片点击入口） */
        fun showExpiring(project: Project) {
            QuotaDetailDialog(project, Mode.EXPIRING).show()
        }
    }
}
