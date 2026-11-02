package dev.codebuddy.account.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 界面配色集中定义。
 *
 * 全部使用 [JBColor]（浅色 / 深色成对），跟随 IDE 主题；
 * 品牌蓝只用于选中、徽标等高亮语义，不用于大面积背景。
 */
object AccountUiColors {

    /** 面板底色 */
    val panelBg = JBColor(Color(0xFFFFFF), Color(0x1E1F22))

    /** 工具栏 / 状态栏底色 */
    val surfaceBg = JBColor(Color(0xF7F8FA), Color(0x2B2D30))

    /** 卡片常规底色 */
    val cardBg = JBColor(Color(0xFAFAFA), Color(0x2B2D30))

    /** 卡片 hover 底色 */
    val cardHoverBg = JBColor(Color(0xF3F4F6), Color(0x33363B))

    /** 当前激活账号卡片底色 */
    val cardActiveBg = JBColor(Color(0xF0F7FF), Color(0x24303F))

    /** 选中态卡片底色 */
    val cardSelectedBg = JBColor(Color(0xE8F0FE), Color(0x2D3A4F))

    /** 常规边框 */
    val border = JBColor(Color(0xE5E7EB), Color(0x3A3D41))

    /** 激活账号边框 */
    val borderActive = JBColor(Color(0x93C5FD), Color(0x3D6EA5))

    /** 品牌蓝：选中边框、徽标 */
    val accent = JBColor(Color(0x3B82F6), Color(0x4A9EFF))

    /** 主文本 */
    val textPrimary = JBColor(Color(0x111827), Color(0xE6E6E6))

    /** 次文本 */
    val textSecondary = JBColor(Color(0x374151), Color(0xD1D5DB))

    /** 弱文本 */
    val textMuted = JBColor(Color(0x6B7280), Color(0x9CA3AF))

    /** 最弱文本（占位、未刷新） */
    val textFaint = JBColor(Color(0x9CA3AF), Color(0x6B7280))

    /** 正常 / 有效 */
    val success = JBColor(Color(0x059669), Color(0x34D399))

    /** 警告 / access token 过期 */
    val warning = JBColor(Color(0xD97706), Color(0xFBBF24))

    /** 危险 / 过期、额度极低 */
    val danger = JBColor(Color(0xDC2626), Color(0xF87171))

    /** 进度条轨道 */
    val barTrack = border

    /** 进度条充盈（正常） */
    val barFill = JBColor(Color(0x10B981), Color(0x34D399))

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
