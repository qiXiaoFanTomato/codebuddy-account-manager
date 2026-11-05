package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/**
 * 内嵌的「额度明细」视图：一个账号一张 `.quota-card`，卡片内按套餐分组折叠细项。
 *
 * 对应 design/plugin-ui.html 的 `#view-quota`：
 * - 视图标题与汇总读数由主面板的 `.view-head` 承担（[metaLabel]）；
 * - 本视图只负责账号筛选、刷新入口与卡片列表，不再单独弹窗。
 */
class QuotaView : JPanel(BorderLayout()) {

    private val library = AccountLibrary.getInstance()

    private val accountCombo = JComboBox<AccountChoice>()

    private val refreshBtn: JellyButton =
        JellyButton("刷新额度", JellyButtonVariant.SECONDARY, null, "重新查询额度") { doRefresh() }

    /** 视图标题右侧的汇总读数（由主面板放进 `.view-head`） */
    val metaLabel: JLabel = JLabel("正在读取额度…").apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textSecondary
    }

    /** 账号额度卡横向铺开：与账号卡片网格同一套列宽 */
    private val listPanel = object : JPanel(
        WrapGridLayout(
            JBUI.scale(AccountUiSpacing.gridGap),
            JBUI.scale(AccountUiSpacing.gridGap),
            JBUI.scale(AccountUiSpacing.gridMinColumnWidth),
        )
    ), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }.apply { isOpaque = false }

    /** 数据变化后通知外部（状态栏读数 / 卡片视图） */
    var onChanged: (() -> Unit)? = null

    private var suppressCombo = false

    init {
        background = AccountUiColors.panelBg

        accountCombo.addActionListener {
            if (!suppressCombo) reload()
        }

        val scroll = JBScrollPane(listPanel).apply {
            border = JBUI.Borders.empty(AccountUiSpacing.cardGap, AccountUiSpacing.cardPadH, AccountUiSpacing.cardGap, AccountUiSpacing.cardPadH)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            background = AccountUiColors.panelBg
            viewport.background = AccountUiColors.panelBg
        }
        add(scroll, BorderLayout.CENTER)
        reload()
    }

    /** 标题行右侧：账号筛选 + 刷新 */
    fun headerControls(): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        AccountUiKit.styleCombo(accountCombo)
        accountCombo.preferredSize = Dimension(JBUI.scale(220), JBUI.scale(28))
        row.add(JLabel("账号").apply {
            font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textMuted
        })
        row.add(accountCombo)
        row.add(refreshBtn)
        return row
    }

    /** 按账号库与额度缓存重建卡片（EDT 调用） */
    fun reload() {
        val saved = library.listSaved()
        val active = library.currentActive()
        val quotas = library.quotaCache.loadAll()
        val selectedKey = (accountCombo.selectedItem as? AccountChoice)?.key

        syncCombo(saved, active, selectedKey)

        val targets = when (val key = (accountCombo.selectedItem as? AccountChoice)?.key) {
            null -> saved.ifEmpty { listOfNotNull(active) }
            else -> saved.filter { it.accountKey == key }.ifEmpty { listOfNotNull(active) }
        }

        val summaries = targets.mapNotNull { quotas[it.accountKey]?.takeIf { q -> q.error == null } }
        metaLabel.text = buildMeta(targets, summaries)

        listPanel.removeAll()
        if (targets.isEmpty()) {
            listPanel.add(
                JLabel("账号库为空，先从 auth 目录导入账号").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
                    foreground = AccountUiColors.textFaint
                    border = JBUI.Borders.empty(16, 4)
                }
            )
        } else {
            val ordered = targets.sortedWith(
                compareByDescending<CredentialSnapshot> { it.accountKey == active?.accountKey }
                    .thenByDescending { quotas[it.accountKey]?.takeIf { q -> q.error == null }?.usableRemainPercent ?: -1.0 }
                    .thenBy { it.displayName }
            )
            for (snapshot in ordered) {
                val card = QuotaAccountCard(
                    snapshot = snapshot,
                    summary = quotas[snapshot.accountKey],
                    active = snapshot.accountKey == active?.accountKey,
                )
                card.alignmentX = Component.LEFT_ALIGNMENT
                listPanel.add(card)
            }
        }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun syncCombo(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        selectedKey: String?,
    ) {
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
    }

    private fun buildMeta(targets: List<CredentialSnapshot>, summaries: List<QuotaSummary>): String {
        if (targets.isEmpty()) return "暂无账号"
        if (summaries.isEmpty()) return "${targets.size} 个账号 · 尚未查询额度"
        val size = summaries.sumOf { it.usableSize }
        val remain = summaries.sumOf { it.usableRemain }
        val unit = summaries.firstOrNull()?.unit?.ifBlank { "credits" } ?: "credits"
        val percent = if (size <= 0) 0.0 else remain / size * 100.0
        val items = summaries.sumOf { it.packages.size }
        return "${targets.size} 个账号 · 剩余 ${AccountUiKit.formatNumber(remain)} / " +
            "${AccountUiKit.formatNumber(size)} $unit（${"%.0f".format(percent)}%）· 细项 $items 条"
    }

    private fun doRefresh() {
        val targets = when (val key = (accountCombo.selectedItem as? AccountChoice)?.key) {
            null -> library.listSaved()
            else -> library.listSaved().filter { it.accountKey == key }
        }
        if (targets.isEmpty()) {
            metaLabel.text = "账号库为空"
            return
        }

        refreshBtn.isEnabled = false
        metaLabel.text = "正在查询 ${targets.size} 个账号…"
        ApplicationManager.getApplication().executeOnPooledThread {
            var ok = 0
            var fail = 0
            for (s in targets) {
                val q = library.refreshQuota(s.accountKey)
                if (q == null || q.error != null) fail++ else ok++
            }
            ApplicationManager.getApplication().invokeLater {
                refreshBtn.isEnabled = true
                AccountEvents.fireQuotaChanged()
                reload()
                onChanged?.invoke()
                if (fail > 0) metaLabel.text = "刷新完成：成功 $ok 个，失败 $fail 个"
            }
        }
    }
}
