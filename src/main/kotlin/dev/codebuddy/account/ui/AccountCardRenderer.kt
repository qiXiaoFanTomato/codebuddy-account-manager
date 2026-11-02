package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * 账号卡片：左侧圆形头像 + 右侧信息 + 配额进度条。
 *
 * 所有颜色统一取自 [AccountUiColors]，随 IDE 主题切换。
 */
class AccountCard(
    val snapshot: CredentialSnapshot,
    val isActive: Boolean,
    val quota: QuotaSummary?,
) : JPanel() {

    var selected: Boolean = false
        set(value) { field = value; repaint() }

    /** 鼠标悬停态（由 AccountCardList 维护） */
    var hover: Boolean = false
        set(value) { field = value; repaint() }

    private val avatarColor: Color = avatarColorFor(snapshot.uid)
    private val initial: String = (snapshot.displayName.firstOrNull() ?: '?').uppercaseChar().toString()

    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(96))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(96))
        border = JBUI.Borders.empty(6, 10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val w = width
            val h = height
            val arc = JBUI.scale(12)

            // 背景（优先级：选中 > 悬停 > 激活 > 常规）
            val bg = when {
                selected -> AccountUiColors.cardSelectedBg
                hover -> AccountUiColors.cardHoverBg
                isActive -> AccountUiColors.cardActiveBg
                else -> AccountUiColors.cardBg
            }
            g2.color = bg
            g2.fill(RoundRectangle2D.Float(2f, 2f, (w - 4).toFloat(), (h - 4).toFloat(), arc.toFloat(), arc.toFloat()))

            // 边框
            val borderColor = when {
                selected -> AccountUiColors.accent
                isActive -> AccountUiColors.borderActive
                else -> AccountUiColors.border
            }
            g2.color = borderColor
            g2.stroke = BasicStroke(if (selected || isActive) 2f else 1f)
            g2.draw(RoundRectangle2D.Float(2f, 2f, (w - 4).toFloat(), (h - 4).toFloat(), arc.toFloat(), arc.toFloat()))

            // 头像
            val d = JBUI.scale(44)
            val ax = JBUI.scale(14)
            val ay = (h - d) / 2
            g2.color = avatarColor
            g2.fillOval(ax, ay, d, d)
            g2.color = Color.WHITE
            g2.font = font.deriveFont(Font.BOLD, JBUI.scale(18).toFloat())
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(initial)
            g2.drawString(initial, ax + (d - tw) / 2, ay + (d - fm.height) / 2 + fm.ascent)

            // 文字区域
            val textX = ax + d + JBUI.scale(14)
            var y = JBUI.scale(22)

            // 第一行：名称 + 当前徽标
            g2.font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            g2.color = AccountUiColors.textPrimary
            g2.drawString(snapshot.displayName, textX, y)
            var nameWidth = g2.fontMetrics.stringWidth(snapshot.displayName)

            if (isActive) {
                val badgeX = textX + nameWidth + JBUI.scale(8)
                val badgeW = JBUI.scale(44)
                val badgeH = JBUI.scale(18)
                g2.color = AccountUiColors.accent
                g2.fill(
                    RoundRectangle2D.Float(
                        badgeX.toFloat(), (y - JBUI.scale(13)).toFloat(),
                        badgeW.toFloat(), badgeH.toFloat(), 9f, 9f
                    )
                )
                g2.color = Color.WHITE
                g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                g2.drawString("当前", badgeX + JBUI.scale(10), y)
                nameWidth += badgeW + JBUI.scale(8)
            }

            // 第二行：类型标签 + 手机号 + 认证域
            y += JBUI.scale(20)
            var x = textX
            g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())

            // 类型标签
            val typeText = if (snapshot.type.equals("enterprise", true)) "企业" else "个人"
            val tagW = g2.fontMetrics.stringWidth(typeText) + JBUI.scale(12)
            g2.color = if (typeText == "企业") AccountUiColors.tagEnterpriseBg else AccountUiColors.tagPersonalBg
            g2.fill(
                RoundRectangle2D.Float(
                    x.toFloat(), (y - JBUI.scale(11)).toFloat(),
                    tagW.toFloat(), JBUI.scale(16).toFloat(), 8f, 8f
                )
            )
            g2.color = if (typeText == "企业") AccountUiColors.tagEnterpriseFg else AccountUiColors.tagPersonalFg
            g2.drawString(typeText, x + JBUI.scale(6), y)
            x += tagW + JBUI.scale(8)

            // 手机号
            g2.color = AccountUiColors.textMuted
            g2.drawString(snapshot.phoneNumber.ifBlank { "—" }, x, y)
            x += g2.fontMetrics.stringWidth(snapshot.phoneNumber.ifBlank { "—" }) + JBUI.scale(12)

            // 认证域
            g2.drawString(snapshot.domain.ifBlank { "—" }, x, y)

            // 第三行：域名徽标 + Token 状态 + 配额
            y += JBUI.scale(20)
            x = textX

            // 域名徽标（区分同昵称不同域）
            val domainText = snapshot.domainHost
            if (domainText.isNotBlank()) {
                val dw = g2.fontMetrics.stringWidth(domainText) + JBUI.scale(12)
                val alt = domainText.contains("workbuddy")
                g2.color = if (alt) AccountUiColors.tagDomainAltBg else AccountUiColors.tagDomainBg
                g2.fill(
                    RoundRectangle2D.Float(
                        x.toFloat(), (y - JBUI.scale(11)).toFloat(),
                        dw.toFloat(), JBUI.scale(16).toFloat(), 8f, 8f
                    )
                )
                g2.color = if (alt) AccountUiColors.tagDomainAltFg else AccountUiColors.tagDomainFg
                g2.drawString(domainText, x + JBUI.scale(6), y)
                x += dw + JBUI.scale(10)
            }

            // Token 状态
            val (tokenText, tokenColor) = when {
                snapshot.isRefreshTokenExpired -> "Token 已过期" to AccountUiColors.danger
                snapshot.isAccessTokenExpired -> "Access 已过期" to AccountUiColors.warning
                else -> "Token 有效" to AccountUiColors.success
            }
            g2.color = tokenColor
            g2.drawString(tokenText, x, y)
            x += g2.fontMetrics.stringWidth(tokenText) + JBUI.scale(14)

            // 配额进度条
            if (quota != null && quota.error == null && quota.packages.isNotEmpty()) {
                val barW = JBUI.scale(120)
                val barH = JBUI.scale(8)
                val barX = x
                val barY = y - barH + JBUI.scale(2)

                // 轨道
                g2.color = AccountUiColors.barTrack
                g2.fill(
                    RoundRectangle2D.Float(
                        barX.toFloat(), barY.toFloat(),
                        barW.toFloat(), barH.toFloat(), barH.toFloat(), barH.toFloat()
                    )
                )

                // 进度：按「未过期细项」计算，避免已过期的赠送包撑高百分比
                val pct = quota.usableRemainPercent / 100.0
                val fillW = (barW * pct).toInt().coerceAtLeast(0)
                g2.color = when {
                    quota.usableRemainPercent <= 10 -> AccountUiColors.danger
                    quota.usableRemainPercent <= 30 -> AccountUiColors.warning
                    else -> AccountUiColors.barFill
                }
                if (fillW > 0) {
                    g2.fill(
                        RoundRectangle2D.Float(
                            barX.toFloat(), barY.toFloat(),
                            fillW.toFloat(), barH.toFloat(), barH.toFloat(), barH.toFloat()
                        )
                    )
                }

                // 配额文字
                g2.color = AccountUiColors.textSecondary
                g2.drawString(quota.summaryText(), barX + barW + JBUI.scale(10), y)
            } else if (quota?.error != null) {
                g2.color = AccountUiColors.textFaint
                g2.drawString("额度查询失败", x, y)
            } else {
                g2.color = AccountUiColors.textFaint
                g2.drawString("额度未刷新", x, y)
            }

        } finally {
            g2.dispose()
        }
    }

    companion object {
        /** 按 uid 稳定生成头像配色 */
        fun avatarColorFor(uid: String): Color {
            val h = uid.hashCode()
            val palette = AccountUiColors.avatarPalette
            return palette[Math.floorMod(h, palette.size)]
        }
    }
}
