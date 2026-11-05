package dev.codebuddy.account.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/**
 * 卡片网格视图：自适应多列排布 [AccountGridCard]，支持关键字筛选、分页与多选。
 *
 * 勾选状态按 accountKey 记录，翻页后仍然保留，供工具栏的批量操作读取。
 */
class AccountGridView : JPanel(BorderLayout()) {

    /** 网格容器：宽度跟随视口，高度由 [WrapGridLayout] 按实际行数计算 */
    private val container = object : JPanel(
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
    }

    private val scrollPane: JBScrollPane
    private var cards: List<AccountGridCard> = emptyList()

    private var rawList: List<CredentialSnapshot> = emptyList()
    private var filtered: List<CredentialSnapshot> = emptyList()
    private var activeKey: String? = null
    private var quotas: Map<String, QuotaSummary> = emptyMap()
    private var filterText: String = ""

    /** 勾选账号（跨页保持） */
    private val checkedKeys = linkedSetOf<String>()

    private var pageSizeValue: Int = 20
    private var pageIndexValue: Int = 0

    /** 勾选状态变化（含重建后的默认态） */
    var onSelectionChanged: (() -> Unit)? = null

    /** 页码 / 每页条数变化 */
    var onPageChanged: (() -> Unit)? = null

    /** 卡片底部操作转发 */
    var onAction: ((AccountGridCard, GridCardAction) -> Unit)? = null

    init {
        layout = BorderLayout()
        container.background = AccountUiColors.panelBg
        // 与 `.content` 的 22px 内边距对齐（取 4 基准阶梯上的 18 / 20）
        container.border = JBUI.Borders.empty(AccountUiSpacing.cardGap, AccountUiSpacing.xl)

        scrollPane = JBScrollPane(container).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            // 界面固定浅色：不让 IDE 深色主题把滚动区底色刷成深灰
            background = AccountUiColors.panelBg
            viewport.background = AccountUiColors.panelBg
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    fun setAccounts(
        list: List<CredentialSnapshot>,
        activeKey: String?,
        quotas: Map<String, QuotaSummary>,
    ) {
        this.rawList = list
        this.activeKey = activeKey
        this.quotas = quotas
        // 账号被删除后同步清理勾选
        val keys = list.map { it.accountKey }.toSet()
        checkedKeys.retainAll(keys)
        render()
    }

    /** 关键字筛选：名称 / UID / uin / 域名 / 手机号 / 别名 */
    fun setFilter(text: String) {
        if (filterText == text) return
        filterText = text
        pageIndexValue = 0
        render()
    }

    // ---------------------------------------------------------------
    // 分页
    // ---------------------------------------------------------------

    /** 过滤后的账号总数（不受分页影响） */
    fun totalCount(): Int = filtered.size

    fun pageSize(): Int = pageSizeValue

    fun pageIndex(): Int = pageIndexValue

    fun pageCount(): Int = if (filtered.isEmpty()) 1 else (filtered.size + pageSizeValue - 1) / pageSizeValue

    fun setPageSize(size: Int) {
        if (size <= 0 || size == pageSizeValue) return
        pageSizeValue = size
        pageIndexValue = 0
        render()
    }

    fun setPage(index: Int) {
        val target = index.coerceIn(0, pageCount() - 1)
        if (target == pageIndexValue) return
        pageIndexValue = target
        render()
    }

    // ---------------------------------------------------------------
    // 选择
    // ---------------------------------------------------------------

    fun selectedCards(): List<AccountGridCard> = cards.filter { it.checked }

    /** 跨页勾选的账号快照 */
    fun selectedSnapshots(): List<CredentialSnapshot> = filtered.filter { it.accountKey in checkedKeys }

    fun checkedCount(): Int = checkedKeys.size

    /** 无勾选时回退到当前激活账号，保证单账号操作可用 */
    fun selectedCard(): AccountGridCard? =
        selectedCards().firstOrNull() ?: cards.firstOrNull { it.isActive } ?: cards.firstOrNull()

    /** 全选作用于过滤后的全部账号（跨页） */
    fun selectAll(checked: Boolean) {
        if (checked) checkedKeys.addAll(filtered.map { it.accountKey }) else checkedKeys.clear()
        for (card in cards) card.checked = checked
        onSelectionChanged?.invoke()
    }

    fun scrollToTop() {
        scrollPane.verticalScrollBar.value = 0
    }

    // ---------------------------------------------------------------
    // 渲染
    // ---------------------------------------------------------------

    private fun render() {
        container.removeAll()

        filtered = if (filterText.isBlank()) rawList else rawList.filter { it.matchesKeyword(filterText) }
        val pages = pageCount()
        if (pageIndexValue > pages - 1) pageIndexValue = pages - 1
        if (pageIndexValue < 0) pageIndexValue = 0

        val from = pageIndexValue * pageSizeValue
        val pageItems = filtered.drop(from).take(pageSizeValue)

        val newCards = pageItems.map { snapshot ->
            AccountGridCard(
                snapshot = snapshot,
                isActive = snapshot.accountKey == activeKey,
                quota = quotas[snapshot.accountKey],
                onCheckedChanged = { card, checked ->
                    if (checked) checkedKeys.add(card.snapshot.accountKey)
                    else checkedKeys.remove(card.snapshot.accountKey)
                    onSelectionChanged?.invoke()
                },
                onAction = { card, action -> onAction?.invoke(card, action) },
            ).apply {
                checked = snapshot.accountKey in checkedKeys
                alignmentX = Component.LEFT_ALIGNMENT
            }
        }
        for (card in newCards) container.add(card)

        if (rawList.isNotEmpty() && filtered.isEmpty()) container.add(emptyHint())
        cards = newCards

        container.revalidate()
        container.repaint()
        onSelectionChanged?.invoke()
        onPageChanged?.invoke()
    }

    private fun emptyHint(): JLabel = UiHint("没有匹配的账号").apply {
        border = JBUI.Borders.empty(16, 6)
    }
}
