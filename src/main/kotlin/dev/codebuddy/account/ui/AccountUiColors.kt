package dev.codebuddy.account.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager

/**
 * 界面配色集中定义。
 *
 * 全部使用 [JBColor]（浅色 / 深色成对），跟随 IDE 主题；
 * 品牌蓝只用于选中、徽标等高亮语义，不用于大面积背景。
 *
 * 层次约定（浅色）：面板底色 < 工具栏底色 < 卡片底色，
 * 卡片用「更亮的底 + 更淡的边 + 轻阴影」浮起，而不是靠重描边。
 */
object AccountUiColors {

    /** 面板底色（列表 / 看板的画布） */
    val panelBg = JBColor(Color(0xFBFBFC), Color(0x1E1F22))

    /** 工具栏 / 状态栏底色 */
    val surfaceBg = JBColor(Color(0xF5F6F8), Color(0x2B2D30))

    /** 卡片常规底色（比画布亮一档） */
    val cardBg = JBColor(Color(0xFFFFFF), Color(0x27282C))

    /** 卡片 hover 底色 */
    val cardHoverBg = JBColor(Color(0xF6F8FB), Color(0x303237))

    /** 当前激活账号卡片底色 */
    val cardActiveBg = JBColor(Color(0xF3F8FF), Color(0x22303F))

    /** 选中态卡片底色 */
    val cardSelectedBg = JBColor(Color(0xEAF2FE), Color(0x26364A))

    /** 常规边框 */
    val border = JBColor(Color(0xE4E7EC), Color(0x3A3D41))

    /** 弱分隔线（卡片内部分行） */
    val divider = JBColor(Color(0xEFF1F5), Color(0x33363B))

    /** 卡片轻阴影（低透明度，用于底部浮起） */
    val cardShadow = JBColor(Color(15, 23, 42, 14), Color(0, 0, 0, 60))

    /** 激活账号边框 */
    val borderActive = JBColor(Color(0xBFDBFE), Color(0x3D6EA5))

    /** 品牌蓝：选中边框、徽标 */
    val accent = JBColor(Color(0x3B82F6), Color(0x4A9EFF))

    /** 品牌蓝的柔和底色（强调条背景、强调块） */
    val accentSoft = JBColor(Color(0xE8F1FF), Color(0x1D2E45))

    /** 主文本 */
    val textPrimary = JBColor(Color(0x111827), Color(0xE6E6E6))

    /** 次文本 */
    val textSecondary = JBColor(Color(0x374151), Color(0xD1D5DB))

    /** 弱文本 */
    val textMuted = JBColor(Color(0x5F6B7A), Color(0xA4ACB8))

    /** 最弱文本（占位、未刷新），保持 ≥3:1 对比度 */
    val textFaint = JBColor(Color(0x8A929E), Color(0x8B939E))

    /** 正常 / 有效 */
    val success = JBColor(Color(0x059669), Color(0x34D399))

    /** 警告 / access token 过期 */
    val warning = JBColor(Color(0xD97706), Color(0xFBBF24))

    /** 危险 / 过期、额度极低 */
    val danger = JBColor(Color(0xDC2626), Color(0xF87171))

    /** 进度条轨道 */
    val barTrack = JBColor(Color(0xEDF0F4), Color(0x3A3D42))

    /** 进度条充盈（正常） */
    val barFill = JBColor(Color(0x10B981), Color(0x34D399))

    /** 进度条充盈（高亮渐变起点，用于细微立体感） */
    val barFillTop = JBColor(Color(0x34D399), Color(0x4ADE80))

    /** 个人账号标签 */
    val tagPersonalBg = JBColor(Color(0xE0F2FE), Color(0x1B3A4B))
    val tagPersonalFg = JBColor(Color(0x0369A1), Color(0x7DD3FC))

    /** 企业账号标签 */
    val tagEnterpriseBg = JBColor(Color(0xFEF3C7), Color(0x453A1B))
    val tagEnterpriseFg = JBColor(Color(0x92400E), Color(0xFCD34D))

    /** 认证域标签（codebuddy） */
    val tagDomainBg = tagPersonalBg
    val tagDomainFg = tagPersonalFg

    /** 认证域标签（workbuddy） */
    val tagDomainAltBg = JBColor(Color(0xF3E8FF), Color(0x3B2E4F))
    val tagDomainAltFg = JBColor(Color(0x7C3AED), Color(0xC4B5FD))

    /** 头像配色池 */
    val avatarPalette = arrayOf(
        Color(0x3B82F6), Color(0x8B5CF6), Color(0xEC4899), Color(0xF59E0B),
        Color(0x10B981), Color(0x06B6D4), Color(0xEF4444), Color(0x6366F1),
    )
}

/**
 * 圆角尺寸阶梯（未落地 UI 缩放，使用处需经 JBUI.scale）。
 *
 * - [card]：外层卡片 / 指标块
 * - [inner]：内层块、标签容器
 * - [chip]：小徽标
 */
object AccountUiRadius {
    const val card = 12
    const val inner = 8
    const val chip = 9
}

/**
 * 自绘 [javax.swing.JComponent] 的基础字体。
 *
 * JComponent 自身没有默认字体，构造期还未挂到父容器上，`getFont()` 返回 null，
 * 直接 `font.deriveFont(...)` 会 NPE，因此统一用 LAF 的 Label 字体兜底。
 */
internal fun baseLabelFont(): Font =
    UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))
