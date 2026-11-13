package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/** 跨列数取该值时占满整行 */
const val DASHBOARD_SPAN_FULL: Int = 0

/**
 * 标记卡片自带的交互控件（拖拽抓手 / 尺寸按钮）。
 *
 * 「整卡可点击」的安装会遍历所有子组件挂监听，遇到带此标记的组件必须跳过，
 * 否则点尺寸按钮会连带触发卡片本身的点击动作。
 */
const val DASHBOARD_CARD_ACTION_KEY: String = "codebuddy.dashboardCardAction"

/**
 * 看板卡片标识：决定标题文案与「管理卡片」菜单里的名字。
 *
 * 声明顺序即看板的默认排列顺序：今日消耗积分与关键指标左右并排占满首行
 * （设计稿里今日消耗积分整宽，看板布局要求改为与关键指标并排），
 * 其余半宽卡片顺次换行；插件自有卡片排在后面，可通过「管理卡片」隐藏。
 */
enum class DashboardCardId(val title: String) {
    HERO("今日消耗积分"),
    KPI("关键指标"),
    CHECKIN("今日签到"),
    MODEL_QUOTA("模型限额"),
    RATE_LIMIT("模型频率限制"),
    RANKING("额度排行"),
    EXPIRING("到期看板"),
    SCHEDULER("智能调度"),
    USAGE("消耗明细"),
    GROWTH_PLAN("成长计划与活跃"),
    GROWTH("成长中心"),
    ASSISTANT("增强助手"),
    EVENTS("事件记录"),
    ACTIVITY("对话活跃"),
    STATUS("数据状态"),
    GUIDE("使用说明"),
    ;

    companion object {
        fun of(name: String): DashboardCardId? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 卡片尺寸档位：统一列数 × 高度档位，全局生效，不按卡片单独设置。
 *
 * 三档都占 2 格宽（4 格网格里一行两张），靠高度区分：
 * - 2×1 宽扁，适合「主指标 + 一两行」；
 * - 2×2 近方，适合 KPI 与短列表；
 * - 2×4 高，适合逐条明细。
 *
 * [heightDp] 是整屏卡片的统一高度下限；内容更高时整屏一起抬高
 * （见 [DashboardCardGridLayout]），保证任何时刻所有卡片等高。
 */
enum class DashboardCardSize(val label: String, val columns: Int, val heightDp: Int) {

    SMALL_2X1("2×1", 2, 196),
    MEDIUM_2X2("2×2", 2, 292),
    TALL_2X4("2×4", 2, 532),
    ;

    companion object {

        /** 默认档位 */
        val DEFAULT: DashboardCardSize get() = MEDIUM_2X2

        /** 解析设置里存的档位名（容忍存成 label 的旧数据） */
        fun of(name: String?): DashboardCardSize? =
            entries.firstOrNull { it.name == name || it.label == name }
    }
}

/**
 * 看板卡片网格：按可用宽度分格，所有卡片同宽（2 格）、同高。
 *
 * 与 [WrapGridLayout] 的差别：
 * - 列数向下取偶数，2 格宽的卡片刚好铺满每行，不会留半列空位；
 * - 卡片高度取「全屏最高卡片」，一次布局后所有卡片等高，
 *   内容少的卡片多出来的空间由卡片内部的 glue 吃掉。
 */
class DashboardCardGridLayout(
    private val hgap: Int,
    private val vgap: Int,
    private val minCellWidth: Int,
) : LayoutManager {

    /** 上一次布局的可用宽度：首选高度计算时容器宽度可能还没被赋值，用它兜底 */
    private var lastAvailableWidth: Int = 0

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit

    override fun removeLayoutComponent(comp: Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val available = (parent.width - insets.left - insets.right).coerceAtLeast(0)
            if (available > 0 && available != lastAvailableWidth) {
                lastAvailableWidth = available
                // 宽度变了格数与换行都会变 → 高度需要重算
                SwingUtilities.invokeLater { parent.revalidate() }
            }
            val columns = columnCount(available)
            val cellWidth = cellWidth(available, columns)
            val visible = visibleChildren(parent)
            // 统一高度：取全屏最高的那张卡，剩下的卡片一起对齐到这个高度
            val uniformHeight = uniformHeight(visible)

            var x = insets.left
            var y = insets.top
            var used = 0
            for (comp in visible) {
                val span = spanOf(comp, columns)
                if (used > 0 && used + span > columns) {
                    y += uniformHeight + vgap
                    x = insets.left
                    used = 0
                }
                val w = spanWidth(cellWidth, span)
                comp.setBounds(x, y, w, uniformHeight)
                x += w + hgap
                used += span
            }
        }
    }

    private fun measure(parent: Container): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            // 首次布局时容器宽度可能还是 0，退回上一次布局宽度，再退回四格估算
            val available = (parent.width - insets.left - insets.right).takeIf { it > 0 }
                ?: lastAvailableWidth.takeIf { it > 0 }
                ?: (minCellWidth * 4 + hgap * 3)
            val columns = columnCount(available)
            val visible = visibleChildren(parent)
            val uniformHeight = uniformHeight(visible)

            // 行数：每行铺满 columns 格
            var spanSum = 0
            visible.forEach { spanSum += spanOf(it, columns) }
            val rows = if (visible.isEmpty()) 0 else (spanSum + columns - 1) / columns

            val height = if (rows <= 0) 0 else rows * uniformHeight + (rows - 1) * vgap
            return Dimension(
                insets.left + insets.right + available,
                insets.top + insets.bottom + height,
            )
        }
    }

    /** 整屏统一高度：所有卡片首选高度的最大值 */
    private fun uniformHeight(visible: List<Component>): Int =
        visible.maxOfOrNull { it.preferredSize.height } ?: 0

    /**
     * 格数：可用宽度能排下几个 [minCellWidth]，并向下取偶数。
     *
     * 卡片统一 2 格宽，列数取偶后每行刚好铺满，不会出现半列空位；
     * 极窄（不足 2 格）时退回 1 列，卡片仍是满宽等高。
     */
    fun columnCount(available: Int): Int {
        if (available <= 0) return 1
        val raw = ((available + hgap) / (minCellWidth + hgap)).coerceAtLeast(1)
        return if (raw >= 4) raw / 2 * 2 else minOf(raw, 2)
    }

    /** 单格宽度 */
    fun cellWidth(available: Int, columns: Int): Int =
        if (columns <= 1) available else (available - hgap * (columns - 1)) / columns

    private fun spanWidth(cellWidth: Int, span: Int): Int =
        cellWidth * span + hgap * (span - 1)

    private fun spanOf(comp: Component, columns: Int): Int {
        val raw = (comp as? JComponent)?.getClientProperty(DashboardCard.SPAN_KEY) as? Int
            ?: DASHBOARD_SPAN_FULL
        return if (raw <= 0) columns else raw.coerceIn(1, columns)
    }

    private fun visibleChildren(parent: Container): List<Component> =
        (0 until parent.componentCount)
            .map { parent.getComponent(it) }
            .filter { it.isVisible }

    companion object {
        /**
         * 单格最小宽度（dp）。
         *
         * 取 160 是为了让常规工具窗稳定成 4 格：半行卡片两两并排、
         * 整行卡片独占一行，与三档尺寸（2 格宽）正好对齐。
         */
        const val MIN_CELL_WIDTH: Int = 160
    }
}

/**
 * 可换行的横向流式布局：按可用宽度逐个摆放，放不下就换行。
 *
 * 按钮行专用的轻量布局。首选尺寸按上一次布局的可用宽度估算换行行数，
 * 所以窄卡片里的多枚胶囊按钮不会被挤到卡片外面。
 */
class FlowWrapLayout(
    private val hgap: Int,
    private val vgap: Int,
) : LayoutManager {

    private var lastAvailable: Int = 0

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit

    override fun removeLayoutComponent(comp: Component?) = Unit

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val available = (parent.width - insets.left - insets.right).coerceAtLeast(0)
            if (available > 0 && available != lastAvailable) {
                lastAvailable = available
                // 宽度变化会影响换行行数 → 让卡片重算高度
                SwingUtilities.invokeLater { parent.revalidate() }
            }
            val limit = if (available > 0) available else Int.MAX_VALUE

            // 先按换行分组，再逐行摆放：同一行里的按钮 / 输入框高度不同，
            // 统一在行内垂直居中，避免顶部对齐造成的参差
            val visible = (0 until parent.componentCount)
                .map { parent.getComponent(it) }
                .filter { it.isVisible }

            val rows = mutableListOf<MutableList<Component>>()
            var current = mutableListOf<Component>()
            var x = 0
            for (comp in visible) {
                val w = comp.preferredSize.width
                if (x > 0 && x + w > limit) {
                    rows.add(current)
                    current = mutableListOf()
                    x = 0
                }
                current.add(comp)
                x += w + hgap
            }
            if (current.isNotEmpty()) rows.add(current)

            var y = insets.top
            for (rowItems in rows) {
                val rowHeight = rowItems.maxOf { it.preferredSize.height }
                var cx = 0
                for (comp in rowItems) {
                    val size = comp.preferredSize
                    comp.setBounds(
                        insets.left + cx,
                        y + (rowHeight - size.height) / 2,
                        size.width,
                        size.height,
                    )
                    cx += size.width + hgap
                }
                y += rowHeight + vgap
            }
        }
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val limit = lastAvailable.takeIf { it > 0 } ?: Int.MAX_VALUE

            var x = 0
            var height = 0
            var rowHeight = 0
            var maxWidth = 0
            for (i in 0 until parent.componentCount) {
                val comp = parent.getComponent(i)
                if (!comp.isVisible) continue
                val size = comp.preferredSize
                if (x > 0 && x + size.width > limit) {
                    height += rowHeight + vgap
                    maxWidth = maxOf(maxWidth, x - hgap)
                    x = 0
                    rowHeight = 0
                }
                x += size.width + hgap
                rowHeight = maxOf(rowHeight, size.height)
            }
            height += rowHeight
            maxWidth = maxOf(maxWidth, (x - hgap).coerceAtLeast(0))
            val width = if (lastAvailable > 0) lastAvailable else maxWidth
            return Dimension(insets.left + insets.right + width, insets.top + insets.bottom + height)
        }
    }

    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)
}

/**
 * 看板卡片外壳：素墨卡片 + 标题栏（可选）+ 内容区。
 *
 * 编辑能力由外壳统一提供，且只在 [setEditing] 打开编辑模式后可用，业务侧只管往 [body] 里塞内容：
 * - 标题栏拖拽抓手 [dragHandle] 按住拖动 → 重排顺序（DnD 由 [CardTransferHandler] 完成）；
 * - 尺寸档位由看板统一指定（[size]），卡片自身不再单独切换；
 * - [dropLine] 是拖拽落点提示，由 [CardDropTracker] 在拖拽过程中设置。
 */
class DashboardCard(
    val id: DashboardCardId,
    title: String,
    subtitle: String? = null,
    showHeader: Boolean = true,
    size: DashboardCardSize = DashboardCardSize.DEFAULT,
    /** 跨列数：默认与尺寸档位一致；[DASHBOARD_SPAN_FULL] 表示独占整行（HTML 的 `.dash-card--wide`） */
    private val span: Int = size.columns,
    private val onRemove: ((DashboardCard) -> Unit)? = null,
) : RoundedCard(AccountUiColors.cardBg, AccountUiColors.border, AccountUiRadius.card, elevated = true) {

    /** 卡片内容区：调用方往里 add 业务组件（贴顶） */
    val body = FullWidthColumn()

    /** 底部动作条：固定在卡片底边，与内容区之间留一个区块间距 */
    val actions = CardActionBar()

    /** 明细行容量：随尺寸档位变化，内容按这个数裁剪 */
    val listRows: Int get() = CardMetrics.listRows(size)

    /** 拖拽抓手：挂在标题栏右侧 */
    val dragHandle: JComponent = CardDragHandle(this)

    /** 统一的尺寸档位：由看板设置决定，所有卡片相同 */
    val size: DashboardCardSize = size

    /** 编辑态进度：0 常态，1 编辑中，驱动强调色描边淡入淡出 */
    private val editAnim = JellyValue(this, 0.0)

    /** 编辑模式下才出现的控件（抓手 + 移除按钮） */
    private val editControls = mutableListOf<JComponent>()

    /** 是否处于编辑模式：拖拽与移除只在编辑态可用 */
    var editing: Boolean = false
        private set

    /**
     * 入场 / 退场进度：1 为完整显示。
     *
     * 增删卡片时由看板逐帧驱动，[paint] 里对整块组件（含子组件）做裁剪与位移，
     * 避免为每个子组件单独做透明度动画。
     */
    var reveal: Double = 1.0
        set(value) {
            val next = value.coerceIn(0.0, 1.0)
            if (field == next) return
            field = next
            repaint()
        }

    /** 拖拽中：描边转强调色，给被拖起的卡片一个明确的状态 */
    var dragging: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            accentStroke = if (value) AccountUiColors.accent else null
            repaint()
        }

    /** 落点提示：[DROP_BEFORE] 插到本卡之前，[DROP_AFTER] 之后，null 无 */
    var dropLine: Int? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        putClientProperty(SPAN_KEY, spanCells())
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(AccountUiSpacing.cardPadV, AccountUiSpacing.cardPadH)

        if (showHeader) {
            val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
            editControls += dragHandle
            headerActions.add(dragHandle)
            if (onRemove != null) {
                val remove = CardIconButton("✕", "移除卡片") { onRemove.invoke(this) }
                editControls += remove
                headerActions.add(remove)
            }
            val header = SectionHeader(title, subtitle, headerActions)
            // 标题栏高度固定：卡片被档位拉高时多出来的空间留给内容，不能被标题栏吞掉
            header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
            add(header)
            // 标题与内容之间用细线分隔，卡片的「表头 / 内容」两段一眼可分
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))
            add(HairLine())
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.md)))
        }
        add(body)
        // 内容贴顶、动作贴底：档位拉高后的多余空间落在两者之间
        add(javax.swing.Box.createVerticalGlue())
        add(actions)
        setEditing(false, animate = false)
    }

    /**
     * 切换编辑模式：抓手与移除按钮的显隐立即生效，
     * 强调色描边用补间淡入淡出，避免整屏卡片同时硬切。
     */
    fun setEditing(enabled: Boolean, animate: Boolean = true) {
        val changed = editing != enabled
        editing = enabled
        // 初始态也要设一遍：editing 初值就是 false，短路会让抓手常驻可见
        editControls.forEach { it.isVisible = enabled }
        if (!changed) return
        if (animate) {
            editAnim.animateTo(if (enabled) 1.0 else 0.0, 200)
        } else {
            editAnim.jumpTo(if (enabled) 1.0 else 0.0)
        }
        revalidate()
        repaint()
    }

    /** 增删过渡：裁剪 + 整数像素位移，整块卡片一起滑入 / 收起 */
    override fun paint(g: Graphics) {
        if (reveal >= 0.999) {
            super.paint(g)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            val shown = (height * reveal).toInt().coerceAtLeast(0)
            val shift = ((1.0 - reveal) * JBUI.scale(12)).toInt()
            g2.clipRect(0, 0, width, shown)
            g2.translate(0, shift)
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * 卡片首选高度：档位高度与内容高度取大者。
     *
     * 宽度由网格布局决定；高度先按内容算，网格再取全屏最大值统一，
     * 所以内容多的卡不会裁内容，内容少的卡会补白到同一高度。
     */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, maxOf(base.height, JBUI.scale(size.heightDp)))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val edit = editAnim.value.toFloat()
            if (edit > 0.01f) {
                g2.color = Jelly.alpha(AccountUiColors.accent, 0.8f * edit)
                g2.stroke = BasicStroke(1.5f)
                g2.draw(
                    Jelly.round(
                        1f, 1f, (width - 2).toFloat(), (height - 2).toFloat(),
                        JBUI.scale(AccountUiRadius.card).toFloat(),
                    )
                )
            }
            val edge = dropLine ?: return
            val thickness = JBUI.scale(3).toFloat()
            val y = if (edge == DROP_AFTER) height - thickness else 0f
            g2.color = AccountUiColors.accent
            g2.fill(
                Jelly.round(
                    JBUI.scale(2).toFloat(),
                    y,
                    (width - JBUI.scale(4)).toFloat(),
                    thickness,
                    thickness / 2f,
                )
            )
        } finally {
            g2.dispose()
        }
    }

    /** 当前跨格数：默认取档位列数（所有卡片同宽），整宽卡片返回 [DASHBOARD_SPAN_FULL] */
    private fun spanCells(): Int = span

    companion object {
        /** 跨列数存放在 clientProperty 里，布局不依赖具体类型 */
        const val SPAN_KEY: String = "dashboardCardSpan"

        /** 落点在本卡上半 → 插到之前 */
        const val DROP_BEFORE: Int = 0

        /** 落点在本卡下半 → 插到之后 */
        const val DROP_AFTER: Int = 1
    }
}

/**
 * 拖拽落点跟踪：同一时刻只高亮一张卡片。
 *
 * Swing 的 TransferHandler 不会在离开某个组件时回调目标方，
 * 所以「清掉上一个高亮」必须由这里集中处理。
 */
class CardDropTracker {

    private var current: DashboardCard? = null
    private var after: Boolean = false

    /** 把落点提示挪到 [target] 的顶部 / 底部 */
    fun highlight(target: DashboardCard, atEnd: Boolean) {
        if (current === target && after == atEnd) return
        current?.takeIf { it !== target }?.dropLine = null
        current = target
        after = atEnd
        target.dropLine = if (atEnd) DashboardCard.DROP_AFTER else DashboardCard.DROP_BEFORE
    }

    /** 清除提示（松手 / 取消 / 落在空白处） */
    fun clear() {
        current?.dropLine = null
        current = null
    }
}

/**
 * 卡片拖拽排序的传输处理器：既当拖起源也当落点。
 *
 * 传输内容就是卡片名（String），只在看板内部流转，不接受外部拖入；
 * 落在目标卡上半 / 下半分别表示插到它前 / 后，由 [onMove] 落库并重排。
 */
class CardTransferHandler(
    private val card: DashboardCard,
    private val tracker: CardDropTracker,
    private val onMove: (from: String, to: String, after: Boolean) -> Unit,
) : TransferHandler() {

    override fun getSourceActions(c: JComponent): Int = MOVE

    override fun createTransferable(c: JComponent): Transferable = StringSelection(card.id.name)

    override fun canImport(support: TransferHandler.TransferSupport): Boolean {
        if (!card.editing || !support.isDrop || support.component !== card) return false
        val from = sourceId(support) ?: return false
        // 只认本看板的卡片名，避免编辑器里拖来的文本也被当成落点
        if (DashboardCardId.of(from) == null || from == card.id.name) {
            tracker.clear()
            return false
        }
        support.dropAction = MOVE
        tracker.highlight(card, isDropAfter(support))
        return true
    }

    override fun importData(support: TransferHandler.TransferSupport): Boolean {
        if (!card.editing) return false
        val from = sourceId(support) ?: return false
        val after = isDropAfter(support)
        tracker.clear()
        if (DashboardCardId.of(from) == null || from == card.id.name) return false
        onMove(from, card.id.name, after)
        return true
    }

    override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
        card.dragging = false
        tracker.clear()
    }

    /** 传输内容只接受本看板发出的卡片名 */
    private fun sourceId(support: TransferHandler.TransferSupport): String? {
        val transferable = support.transferable ?: return null
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        return runCatching { transferable.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
    }

    /** 落点在卡片中线以下 = 插到这张卡之后 */
    private fun isDropAfter(support: TransferHandler.TransferSupport): Boolean {
        val point: Point = support.dropLocation?.dropPoint ?: return true
        return point.y > card.height / 2
    }
}

/**
 * 卡片标题栏上的圆形小按钮（移除卡片等）。
 *
 * 平时只有淡淡图标，hover 时浮出胶囊底色，点击区固定 18dp，不会被旁边的标题挤到。
 */
class CardIconButton(
    private val text: String,
    tooltip: String,
    private val onClick: () -> Unit,
) : JComponent() {

    private val hoverAnim = JellyValue(this, 0.0)
    private var hovering = false
    private val size = JBUI.scale(20)

    init {
        isOpaque = false
        isFocusable = true
        putClientProperty(DASHBOARD_CARD_ACTION_KEY, true)
        font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.caption)
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovering = true
                hoverAnim.animateTo(1.0, 140)
            }

            override fun mouseExited(e: MouseEvent) {
                hovering = false
                hoverAnim.animateTo(0.0, 180)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (hovering) onClick()
            }
        })
    }

    override fun getPreferredSize(): Dimension = Dimension(size, size)
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            Jelly.textHints(g2)
            val hover = hoverAnim.value.toFloat()
            val arc = JBUI.scale(AccountUiRadius.chip).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            if (hover > 0.02f) {
                g2.color = Jelly.alpha(AccountUiColors.surfaceHi, hover)
                g2.fill(shape)
                g2.color = Jelly.alpha(AccountUiColors.border, hover)
                g2.draw(shape)
            }
            g2.font = font
            g2.color = Jelly.mix(AccountUiColors.textFaint, AccountUiColors.textPrimary, hover)
            val fm = g2.fontMetrics
            val tx = (width - fm.stringWidth(text)) / 2
            val ty = (height - fm.height) / 2 + fm.ascent
            g2.drawString(text, tx, ty)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 拖拽抓手：六点网格，按住后拖动超过阈值即发起卡片拖拽。
 *
 * 用独立抓手而不是整卡可拖，是为了不和「点卡片打开明细」抢手势。
 */
private class CardDragHandle(private val card: DashboardCard) : JComponent() {

    private val hoverAnim = JellyValue(this, 0.0)
    private var pressPoint: Point? = null
    private val side = JBUI.scale(20)

    init {
        isOpaque = false
        putClientProperty(DASHBOARD_CARD_ACTION_KEY, true)
        toolTipText = "按住拖动调整卡片顺序"
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        preferredSize = Dimension(side, side)
        minimumSize = preferredSize
        maximumSize = preferredSize

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hoverAnim.animateTo(1.0, 140)
            }

            override fun mouseExited(e: MouseEvent) {
                hoverAnim.animateTo(0.0, 180)
                pressPoint = null
            }

            override fun mousePressed(e: MouseEvent) {
                pressPoint = e.point
            }

            override fun mouseReleased(e: MouseEvent) {
                pressPoint = null
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (!card.editing) return
                val start = pressPoint ?: return
                // 阈值：避免「按下即抖」被当成拖拽
                if (e.point.distance(start) < JBUI.scale(5)) return
                pressPoint = null
                val handler = card.transferHandler ?: return
                card.dragging = true
                handler.exportAsDrag(this@CardDragHandle, e, TransferHandler.MOVE)
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Jelly.mix(AccountUiColors.textFaint, AccountUiColors.accent, hoverAnim.value.toFloat())
            val dot = JBUI.scale(2)
            val step = JBUI.scale(6)
            val left = (width - step - dot) / 2
            val top = (height - step * 2 - dot) / 2
            for (row in 0..2) {
                for (col in 0..1) {
                    g2.fillOval(left + col * step, top + row * step, dot, dot)
                }
            }
        } finally {
            g2.dispose()
        }
    }
}


