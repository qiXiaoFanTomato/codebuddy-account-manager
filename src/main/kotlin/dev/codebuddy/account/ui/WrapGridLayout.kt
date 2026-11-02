package dev.codebuddy.account.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * 自适应网格布局：按可用宽度计算列数，每列等宽，行高取该行最高的卡片。
 *
 * 与 FlowLayout 的差别：换行后会把父容器首选高度撑到实际行数，
 * 放进滚动面板时不会被压成一行。
 */
class WrapGridLayout(
    private val hgap: Int,
    private val vgap: Int,
    private val minColumnWidth: Int,
) : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit

    override fun removeLayoutComponent(comp: Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = measure(parent)

    override fun minimumLayoutSize(parent: Container): Dimension = measure(parent)

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val available = (parent.width - insets.left - insets.right).coerceAtLeast(0)
            val columns = columnCount(available)
            val columnWidth = widthPerColumn(available, columns)
            val visible = visibleChildren(parent)
            if (visible.isEmpty()) return

            var index = 0
            var y = insets.top
            while (index < visible.size) {
                val rowItems = visible.subList(index, minOf(index + columns, visible.size))
                var rowHeight = 0
                for (comp in rowItems) {
                    comp.setSize(columnWidth, comp.preferredSize.height)
                    rowHeight = maxOf(rowHeight, comp.preferredSize.height)
                }
                for ((col, comp) in rowItems.withIndex()) {
                    comp.setBounds(insets.left + col * (columnWidth + hgap), y, columnWidth, rowHeight)
                }
                y += rowHeight + vgap
                index += rowItems.size
            }
        }
    }

    private fun measure(parent: Container): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            // 宽度为 0 时（首次布局）按 3 列估算，避免出现 0 高度的空容器
            val available = (parent.width - insets.left - insets.right).takeIf { it > 0 }
                ?: (minColumnWidth * 3 + hgap * 2)
            val columns = columnCount(available)
            val columnWidth = widthPerColumn(available, columns)
            val visible = visibleChildren(parent)

            var height = 0
            var index = 0
            while (index < visible.size) {
                val rowItems = visible.subList(index, minOf(index + columns, visible.size))
                var rowHeight = 0
                for (comp in rowItems) {
                    comp.setSize(columnWidth, comp.preferredSize.height)
                    rowHeight = maxOf(rowHeight, comp.preferredSize.height)
                }
                height += rowHeight
                if (index + rowItems.size < visible.size) height += vgap
                index += rowItems.size
            }
            return Dimension(insets.left + insets.right + available, insets.top + insets.bottom + height)
        }
    }

    private fun columnCount(available: Int): Int =
        if (available <= 0) 1 else ((available + hgap) / (minColumnWidth + hgap)).coerceAtLeast(1)

    private fun widthPerColumn(available: Int, columns: Int): Int =
        if (columns <= 1) available else (available - hgap * (columns - 1)) / columns

    private fun visibleChildren(parent: Container): List<Component> =
        (0 until parent.componentCount)
            .map { parent.getComponent(it) }
            .filter { it.isVisible }
}
