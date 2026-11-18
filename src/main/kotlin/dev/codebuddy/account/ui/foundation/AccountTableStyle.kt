package dev.codebuddy.account.ui.foundation

import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.tokens.AccountUiColors
import dev.codebuddy.account.ui.tokens.AccountUiType
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Font
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer



/**
 * 表格统一样式：表头、斑马纹、行高、网格线，外加鼠标所在行的轻高亮。
 *
 * 行 hover 是表格里最划算的一处交互：一屏几十行时，
 * 光标停在哪一行不用再靠水平漂移去对齐。
 */
object AccountTableStyle {

    fun apply(table: JBTable, widths: IntArray) {
        table.rowHeight = JBUI.scale(28)
        table.showHorizontalLines = true
        table.showVerticalLines = false
        table.gridColor = AccountUiColors.tableGrid
        table.intercellSpacing = Dimension(0, 0)
        table.border = JBUI.Borders.empty()
        table.background = AccountUiColors.tableBodyBg
        table.foreground = AccountUiColors.textPrimary
        table.selectionBackground = AccountUiColors.brassSoft
        table.selectionForeground = AccountUiColors.textPrimary
        table.fillsViewportHeight = true
        // 关掉平台自带的「鼠标所在行」底色：JBTable.prepareRenderer 会在单元格渲染器之后
        // 再刷一层主题 hover 色（深色主题是 Gray3 #393B40），盖掉本表的浅色纸面，
        // 深墨文字压在灰黑底上就看不见了。本表已有自己的 hover 高亮，平台的这层不要。
        table.putClientProperty(RenderingUtil.PAINT_HOVERED_BACKGROUND, false)

        val header = table.tableHeader
        // 账本式表头：等宽小字 + 1.5px 墨蓝底线
        header.background = AccountUiColors.tableHeaderBg
        header.foreground = AccountUiColors.textSecondary
        // 表头是中文列名，等宽字体没有中文字形
        header.font = AccountUiType.ui(AccountUiType.caption)
        header.border = JBUI.Borders.customLine(AccountUiColors.ink, 0, 0, 2, 0)
        header.reorderingAllowed = false
        // JBTableHeader 的默认渲染器按 LAF 取色：深色主题下表头会变成深底浅字，
        // 与固定浅色的纸面冲突。这里换成显式着色的渲染器，保证任何主题下表头都是抽屉面。
        header.defaultRenderer = accountHeaderRenderer()

        widths.forEachIndexed { index, width ->
            if (index < table.columnModel.columnCount) {
                table.columnModel.getColumn(index).preferredWidth = JBUI.scale(width)
            }
        }

        installRowHover(table)
        installRowRenderer(table)
    }

    /** 表头渲染器：抽屉面底 + 次级文字，不跟随 IDE 深浅主题 */
    private fun accountHeaderRenderer(): TableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            tbl: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column).also {
            isOpaque = true
            background = AccountUiColors.tableHeaderBg
            foreground = AccountUiColors.textSecondary
            font = AccountUiType.ui(AccountUiType.caption)
            horizontalAlignment = SwingConstants.LEFT
            border = headerCellBorder
        }
    }

    /** 表头单元格内边距 */
    private val headerCellBorder = JBUI.Borders.empty(0, 8)

    /** 鼠标所在行高亮：行号记在客户端属性里，由渲染器读取 */
    private fun installRowHover(table: JTable) {
        table.putClientProperty(HOVER_ROW_KEY, -1)
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                if (row != (table.getClientProperty(HOVER_ROW_KEY) as? Int)) {
                    table.putClientProperty(HOVER_ROW_KEY, row)
                    table.repaint()
                }
            }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if ((table.getClientProperty(HOVER_ROW_KEY) as? Int ?: -1) != -1) {
                    table.putClientProperty(HOVER_ROW_KEY, -1)
                    table.repaint()
                }
            }
        })
    }

    private const val HOVER_ROW_KEY = "codebuddy.account.hoverRow"

    /** 单元格内边距：渲染时复用同一个实例，避免每个格子新建 Border */
    private val cellBorder = JBUI.Borders.empty(0, 8)

    private fun installRowRenderer(table: JTable) {
        val delegate = table.getDefaultRenderer(Any::class.java)
        table.setDefaultRenderer(
            Any::class.java,
            object : TableCellRenderer {
                override fun getTableCellRendererComponent(
                    tbl: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int,
                ): Component {
                    val comp = delegate.getTableCellRendererComponent(
                        tbl, value, isSelected, hasFocus, row, column
                    )
                    if (comp is JComponent) {
                        val hoverRow = tbl?.getClientProperty(HOVER_ROW_KEY) as? Int ?: -1
                        // 不用斑马纹：行 hover 已经足够定位，横向满色块反而干扰读数字
                        comp.background = when {
                            isSelected -> AccountUiColors.brassSoft
                            row == hoverRow -> AccountUiColors.cardHoverBg
                            else -> AccountUiColors.tableBodyBg
                        }
                        comp.foreground = AccountUiColors.textPrimary
                        (comp as? JLabel)?.border = cellBorder
                    }
                    return comp
                }
            }
        )
    }
}


/** 让 JTable 也用统一配色（供未走 [AccountTableStyle] 的表格调用） */
fun JTable.applyAccountTableColors() {
    background = AccountUiColors.tableBodyBg
    foreground = AccountUiColors.textPrimary
    gridColor = AccountUiColors.tableGrid
    rowHeight = JBUI.scale(28)
    showVerticalLines = false
    intercellSpacing = Dimension(0, 0)
    tableHeader.background = AccountUiColors.tableHeaderBg
    tableHeader.foreground = AccountUiColors.textSecondary
}

