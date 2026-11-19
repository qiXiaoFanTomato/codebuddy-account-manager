package dev.codebuddy.account.ui.views

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.ui.components.AccountGridCard
import dev.codebuddy.account.ui.components.GridCardAction
import dev.codebuddy.account.ui.components.matchesKeyword
import dev.codebuddy.account.ui.components.UiHint
import dev.codebuddy.account.ui.foundation.WrapGridLayout
import dev.codebuddy.account.ui.tokens.AccountUiColors
import dev.codebuddy.account.ui.tokens.AccountUiSpacing
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Font
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities



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

    /** 卡片实例缓存：accountKey → 卡片，刷新 / 翻页时复用，避免整表重建 */
    private val cardCache = HashMap<String, AccountGridCard>()

    private var rawList: List<CredentialSnapshot> = emptyList()
    private var filtered: List<CredentialSnapshot> = emptyList()
    private var activeKey: String? = null
    private var quotas: Map<String, QuotaSummary> = emptyMap()
    private var filterText: String = ""

    /** 已写凭据但还没重启生效的账号：卡片显示「待重启」 */
    var pendingRestartKey: String? = null

    /** 卡片右键菜单（x, y 为卡片内坐标） */
    var onContextMenu: ((AccountGridCard, Int, Int) -> Unit)? = null

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

    /** 跨页勾选的账号快照（按当前排序顺序） */
    fun selectedSnapshots(): List<CredentialSnapshot> = filtered.filter { it.accountKey in checkedKeys }

    fun checkedCount(): Int = checkedKeys.size

    /** 是否有任何勾选（工具栏动作据此决定是单条还是批量，或提示先勾选） */
    fun hasSelection(): Boolean = checkedKeys.isNotEmpty()

    /** 清空勾选（动作执行完或用户主动取消时调用） */
    fun clearSelection() {
        if (checkedKeys.isEmpty()) return
        checkedKeys.clear()
        for (card in cards) card.checked = false
        onSelectionChanged?.invoke()
    }

    /**
     * 当前页里第一个被勾选的卡片。
     *
     * 只用于「卡片上的动作」（卡片自己带 snapshot，不依赖它）；工具栏动作一律走
     * [selectedSnapshots]，不再隐式回退到激活账号——以前那种回退会让「什么都没选就点删除」
     * 静默作用于第一张卡。
     */
    fun selectedCard(): AccountGridCard? = selectedCards().firstOrNull()

    /**
     * 程序化翻转某张卡的勾选。
     *
     * [AccountGridCard.checked] 的 setter 只改 JCheckBox 状态，不触发 ActionListener，
     * 所以复选框回调之外改勾选必须走这里，否则勾选集与界面会不一致。
     */
    fun toggleChecked(card: AccountGridCard) {
        val key = card.snapshot.accountKey
        if (key in checkedKeys) {
            checkedKeys.remove(key)
            card.checked = false
        } else {
            checkedKeys.add(key)
            card.checked = true
        }
        onSelectionChanged?.invoke()
    }

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
        // 重建前记住滚动位置：刷新额度 / 删除账号后仍停在用户刚才看的地方
        val scrollBefore = scrollPane.verticalScrollBar.value

        filtered = if (filterText.isBlank()) rawList else rawList.filter { it.matchesKeyword(filterText) }
        val pages = pageCount()
        if (pageIndexValue > pages - 1) pageIndexValue = pages - 1
        if (pageIndexValue < 0) pageIndexValue = 0

        val from = pageIndexValue * pageSizeValue
        val pageItems = filtered.drop(from).take(pageSizeValue)

        // 账号被删除后同步清理缓存，避免卡片实例一直挂在内存里
        cardCache.keys.retainAll(filtered.map { it.accountKey }.toSet())

        val newCards = pageItems.map { cardFor(it) }
        container.removeAll()
        for (card in newCards) container.add(card)

        if (rawList.isNotEmpty() && filtered.isEmpty()) container.add(emptyHint())
        cards = newCards

        container.revalidate()
        container.repaint()
        // 布局完成后再恢复：此时滚动条最大值才是新的
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = scrollBefore.coerceIn(0, (bar.maximum - bar.visibleAmount).coerceAtLeast(0))
        }
        onSelectionChanged?.invoke()
        onPageChanged?.invoke()
    }

    /** 按 accountKey 复用卡片：数据没变直接返回原实例，变了就地 [AccountGridCard.rebind] */
    private fun cardFor(snapshot: CredentialSnapshot): AccountGridCard {
        val key = snapshot.accountKey
        val active = key == activeKey
        val pending = key == pendingRestartKey
        val quota = quotas[key]

        val existing = cardCache[key]
        if (existing != null) {
            if (existing.snapshot != snapshot || existing.isActive != active ||
                existing.quota != quota || existing.pendingRestart != pending
            ) {
                existing.rebind(snapshot, active, quota, pending)
            }
            existing.checked = key in checkedKeys
            existing.alignmentX = Component.LEFT_ALIGNMENT
            return existing
        }

        val card = AccountGridCard(
            snapshot = snapshot,
            isActive = active,
            pendingRestart = pending,
            quota = quota,
            onCheckedChanged = { c, checked ->
                if (checked) checkedKeys.add(c.snapshot.accountKey)
                else checkedKeys.remove(c.snapshot.accountKey)
                onSelectionChanged?.invoke()
            },
            onAction = { c, action -> onAction?.invoke(c, action) },
        ).apply {
            checked = key in checkedKeys
            alignmentX = Component.LEFT_ALIGNMENT
        }
        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 双击 = 切换该账号（会走确认框，不是"一点就切"）
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    onAction?.invoke(card, GridCardAction.SWITCH)
                }
            }

            override fun mousePressed(e: MouseEvent) = showPopup(e)

            override fun mouseReleased(e: MouseEvent) = showPopup(e)

            private fun showPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                onContextMenu?.invoke(card, e.x, e.y)
            }
        })
        cardCache[key] = card
        return card
    }

    private fun emptyHint(): JLabel = UiHint("没有匹配的账号").apply {
        border = JBUI.Borders.empty(16, 6)
    }
}
