package dev.codebuddy.account.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * 卡片式账号列表，支持按域名分组与关键字筛选。
 *
 * 分组标题仅在「按域名分组」模式下显示；其他模式为平铺列表。
 * 当前激活账号始终排在最前（即使开启了分组）。
 */
class AccountCardList : JPanel() {

    private val container = JPanel()
    private val scrollPane: JBScrollPane
    private var cards: List<AccountCard> = emptyList()
    var onSelectionChanged: (() -> Unit)? = null

    /** 最近一次渲染数据，筛选时复用，避免重复排序 */
    private var rawList: List<CredentialSnapshot> = emptyList()
    private var activeKey: String? = null
    private var quotas: Map<String, QuotaSummary> = emptyMap()
    private var grouped: Boolean = false
    private var filterText: String = ""

    init {
        layout = BorderLayout()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.background = AccountUiColors.panelBg
        container.border = JBUI.Borders.empty(10, 12)

        scrollPane = JBScrollPane(container).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * 刷新列表。
     *
     * @param grouped true 时按域名插入分组标题
     */
    fun setAccounts(
        list: List<CredentialSnapshot>,
        activeUid: String?,
        quotas: Map<String, QuotaSummary>,
        grouped: Boolean = false,
    ) {
        rawList = list
        activeKey = activeUid
        this.quotas = quotas
        this.grouped = grouped
        render()
    }

    /** 关键字筛选：名称 / UID / uin / 域名 / 手机号 */
    fun setFilter(text: String) {
        if (filterText == text) return
        filterText = text
        render()
    }

    private fun render() {
        val previouslySelected = selectedUid()
        container.removeAll()
        val newCards = mutableListOf<AccountCard>()

        val visible = if (filterText.isBlank()) rawList else rawList.filter { matches(it, filterText) }

        fun addCard(s: CredentialSnapshot) {
            val card = AccountCard(s, s.accountKey == activeKey, quotas[s.accountKey])
            card.alignmentX = Component.LEFT_ALIGNMENT
            card.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = select(card)
                override fun mouseEntered(e: MouseEvent) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    card.hover = true
                }

                override fun mouseExited(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                    card.hover = false
                }
            })
            container.add(card)
            container.add(Box.createVerticalStrut(JBUI.scale(8)))
            newCards.add(card)
        }

        if (grouped) {
            val active = visible.filter { it.accountKey == activeKey }
            val rest = visible.filter { it.accountKey != activeKey }

            if (active.isNotEmpty()) {
                container.add(sectionHeader("当前激活"))
                active.forEach { addCard(it) }
                container.add(Box.createVerticalStrut(JBUI.scale(8)))
            }

            val groups = AccountSorter.groupByDomain(rest)
            for ((domain, items) in groups) {
                container.add(sectionHeader("$domain（${items.size}）"))
                items.forEach { addCard(it) }
                container.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        } else {
            visible.forEach { addCard(it) }
        }

        if (rawList.isNotEmpty() && visible.isEmpty()) {
            container.add(emptyHint())
        }

        cards = newCards
        container.add(Box.createVerticalGlue())

        // 恢复之前的选择
        val toSelect = cards.firstOrNull { it.snapshot.accountKey == previouslySelected }
            ?: cards.firstOrNull { it.isActive }
            ?: cards.firstOrNull()
        toSelect?.let { it.selected = true }

        container.revalidate()
        container.repaint()
        onSelectionChanged?.invoke()
    }

    private fun matches(s: CredentialSnapshot, keyword: String): Boolean =
        s.displayName.contains(keyword, ignoreCase = true) ||
            s.uid.contains(keyword, ignoreCase = true) ||
            s.uin.contains(keyword, ignoreCase = true) ||
            s.domain.contains(keyword, ignoreCase = true) ||
            s.domainHost.contains(keyword, ignoreCase = true) ||
            s.phoneNumber.contains(keyword, ignoreCase = true) ||
            s.alias.contains(keyword, ignoreCase = true)

    private fun sectionHeader(text: String): JLabel = JLabel(text).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        foreground = AccountUiColors.textMuted
        // 左侧 3px 强调线，替代纯文字标题，视觉上区分分组
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AccountUiColors.accent, 0, 0, 0, 3),
            JBUI.Borders.empty(3, 6, 5, 4)
        )
    }

    private fun emptyHint(): JLabel = JLabel("没有匹配的账号").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = AccountUiColors.textFaint
        border = JBUI.Borders.empty(16, 6)
    }

    fun selectedCard(): AccountCard? = cards.firstOrNull { it.selected }

    private fun selectedUid(): String? = selectedCard()?.snapshot?.accountKey

    private fun select(card: AccountCard) {
        for (c in cards) c.selected = (c === card)
        onSelectionChanged?.invoke()
    }

    fun scrollToTop() {
        scrollPane.verticalScrollBar.value = 0
    }
}
