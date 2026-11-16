package dev.codebuddy.account.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants


/**
 * 公共界面组件库：分区标题 / 指标块 / Hero 指标条 / 按钮 / 表格样式 / 状态文案。
 *
 * 目的是把此前散落在各页面里的重复实现收敛到一处，
 * 保证全插件的标题、指标、按钮、表格、状态五类元素只有一套画法。
 */
object AccountUiKit {

    /** 统一分区标题：标题（+ 副标题）+ 右侧延伸细线 + 操作区 */
    fun sectionHeader(title: String, subtitle: String? = null, actions: JComponent? = null): JComponent =
        SectionHeader(title, subtitle, actions)

    /**
     * 字段行：标签左对齐、值右对齐。
     *
     * 卡片里所有「标签 → 值」都走这里，左边缘与右边缘因此在整屏范围内对齐，
     * 不会因为标签字数不同而参差。
     */
    fun fieldLine(label: String, value: JComponent, labelWidth: Int? = null): JComponent =
        FieldLine(label, value, labelWidth)

    /** 右对齐的数值文本：等宽数字，刷新时不左右跳动 */
    fun valueText(
        text: String,
        tone: Color = AccountUiColors.textSecondary,
        mono: Boolean = true,
    ): JLabel = JLabel(text).apply {
        // 数值里可能混中文（「剩余 20%」），按文本选字体，避免等宽字渲染成方框
        font = if (mono) AccountUiType.forText(text, AccountUiType.label, Font.PLAIN)
        else baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
        foreground = tone
        horizontalAlignment = SwingConstants.RIGHT
    }

    /** 状态标签：圆点 + 文案（颜色与文字双通道，不只靠颜色传达状态） */
    fun statusTag(text: String, tone: Color): JComponent = StatusTag(text, tone)

    /** 统一辅助说明文字 */
    fun hint(text: String): JLabel = UiHint(text)

    /** 统一指标块：主数值默认走黄铜读数 */
    fun kpi(label: String, value: String, hint: String? = null, tone: Color = AccountUiColors.brass): JComponent =
        KpiTile(label, value, hint, tone)

    /** 统一 Hero 指标条 */
    fun hero(
        title: String,
        value: String,
        unit: String? = null,
        metrics: List<Pair<String, String>> = emptyList(),
    ): JComponent = HeroPanel(title, value, unit, metrics)

    /** 统一按钮 */
    fun button(
        text: String,
        variant: JellyButtonVariant = JellyButtonVariant.SECONDARY,
        icon: Icon? = null,
        tip: String? = null,
        onClick: () -> Unit,
    ): JComponent = JellyButton(text, variant, icon, tip, onClick)

    /** 分区标题右侧的小动作（替代 ActionLink） */
    fun action(text: String, onClick: () -> Unit): JComponent = ActionPill(text, onClick)

    /**
     * 字段标签：语义色弱底 + 同色文字，圆角 4（不再一律胶囊）。
     *
     * 只给「计数 / 数值」这类需要一眼扫到的短字段用；
     * 行内状态优先用 [statusTag]（圆点 + 文案），视觉更轻。
     */
    fun fieldPill(text: String, tone: Color): JComponent =
        Chip(text, Jelly.mix(AccountUiColors.cardBg, tone, 0.10f), tone, AccountUiRadius.chip)

    /** 行内字段排布：水平排列并垂直居中（FlowLayout 会让胶囊顶到行首，文字视觉偏移） */
    fun fieldRow(vararg comps: JComponent): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEachIndexed { index, comp ->
            if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(6)))
            comp.alignmentY = Component.CENTER_ALIGNMENT
            add(comp)
        }
    }

    /** 统一表格样式 */
    fun styleTable(table: JBTable, widths: IntArray) = AccountTableStyle.apply(table, widths)

    /**
     * 统一下拉框样式：浅色档案纸底 + 墨蓝文字。
     *
     * 界面固定浅色，IDE 深色主题下 LAF 的下拉框会变成深底浅字，
     * 这里连渲染器一起换掉，弹出列表也保持浅色。
     */
    fun styleCombo(combo: JComboBox<*>, label: ((Any?) -> String)? = null): JComboBox<*> = combo.apply {
        background = AccountUiColors.cardBg
        foreground = AccountUiColors.textPrimary
        font = AccountUiType.ui(AccountUiType.label)
        isOpaque = true
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = label?.invoke(value) ?: value?.toString().orEmpty()
                background = if (isSelected) AccountUiColors.surfaceBg else AccountUiColors.cardBg
                foreground = AccountUiColors.textPrimary
                font = AccountUiType.ui(AccountUiType.label)
                border = JBUI.Borders.empty(2, 6)
                return this
            }
        }
    }

    /**
     * 滚动区浅色化。
     *
     * IDE 深色主题下 viewport 默认铺深灰，界面又固定用浅色档案纸，
     * 不显式改回来的话滚动区会变成「深底 + 深字」，内容整块读不出来。
     */
    fun styleScroll(scroll: JBScrollPane, bg: Color = AccountUiColors.panelBg): JBScrollPane = scroll.apply {
        border = JBUI.Borders.empty()
        background = bg
        isOpaque = true
        viewport.background = bg
        viewport.isOpaque = true
    }

    /** 主导航分段控件 */
    fun segmented(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): JComponent =
        SegmentedTabs(items, selectedIndex, onSelect)

    /** 额度状态文案与语义色（此前在两处各写一份） */
    fun quotaStatus(summary: QuotaSummary?): QuotaStatus.State = QuotaStatus.of(summary)

    /** 数字格式化：千分位 + 最多两位小数（此前在三处各写一份） */
    fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) String.format("%,d", v.toLong()) else String.format("%,.2f", v)
}

/** 账号下拉项（此前三个弹窗各写一份） */
data class AccountChoice(
    val key: String?,
    val label: String,
) {
    override fun toString(): String = label
}

/** 额度状态：文案 + 语义色 */
object QuotaStatus {

    data class State(val text: String, val color: Color)

    fun of(summary: QuotaSummary?): State = when {
        summary == null -> State("未刷新", AccountUiColors.textFaint)
        summary.error != null -> State("查询失败", AccountUiColors.danger)
        summary.packages.isEmpty() -> State("无套餐", AccountUiColors.textMuted)
        summary.usableRemain <= 0 -> State("已用尽", AccountUiColors.danger)
        summary.usableRemainPercent <= 10 -> State("紧张", AccountUiColors.danger)
        summary.usableRemainPercent <= 30 -> State("偏低", AccountUiColors.warning)
        else -> State("正常", AccountUiColors.success)
    }
}

/** 账号关键字匹配（此前在列表与网格里各写一份） */
fun CredentialSnapshot.matchesKeyword(keyword: String): Boolean {
    if (keyword.isBlank()) return true
    return displayName.contains(keyword, ignoreCase = true) ||
        uid.contains(keyword, ignoreCase = true) ||
        uin.contains(keyword, ignoreCase = true) ||
        domain.contains(keyword, ignoreCase = true) ||
        domainHost.contains(keyword, ignoreCase = true) ||
        phoneNumber.contains(keyword, ignoreCase = true) ||
        alias.contains(keyword, ignoreCase = true)
}


/** 小组件：判断点是否落在组件内（按下后松手才算点击） */
internal object SwingUtilities2 {
    fun contains(component: JComponent, point: Point): Boolean =
        component.isShowing && component.contains(point)
}
