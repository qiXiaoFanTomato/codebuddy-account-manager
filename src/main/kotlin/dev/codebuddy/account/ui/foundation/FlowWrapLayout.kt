package dev.codebuddy.account.ui.foundation

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.SwingUtilities

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
