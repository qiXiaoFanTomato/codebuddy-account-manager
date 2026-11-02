package dev.codebuddy.account.ui

import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * 账号卡片：左侧圆形头像 + 右侧三行信息 + 配额进度条。
 *
 * 视觉层次：
 *  - 第一行：名称（粗体主文本）+ 「当前」徽标
 *  - 第二行：账号类型 / 手机号 / 认证域（次要信息，弱化）
 *  - 第三行：域名徽标 / Token 状态 / 额度进度条（状态信息，带语义色）
 *
 * 激活与选中状态通过「左侧强调竖条 + 底色 + 描边」三重区分，不只依赖底色。
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
        preferredSize = Dimension(0, JBUI.scale(104))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(104))
        border = JBUI.Borders.empty(6, 10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val arc = JBUI.scale(AccountUiRadius.card).toFloat()
            val pad = 2f
            val shape = RoundRectangle2D.Float(pad, pad, w - pad * 2, h - pad * 2, arc, arc)

            // 背景（优先级：选中 > 悬停 > 激活 > 常规）
            val bg = when {
                selected -> AccountUiColors.cardSelectedBg
                hover -> AccountUiColors.cardHoverBg
                isActive -> AccountUiColors.cardActiveBg
                else -> AccountUiColors.cardBg
            }
            val bordered = selected || isActive
            if (!bordered) {
                // 常规 / 悬停态补一条底边落影，弱化「平铺方块」观感
                g2.color = AccountUiColors.cardShadow
                g2.fill(
                    RoundRectangle2D.Float(
                        pad + 1f, pad + 1.5f, w - pad * 2 - 2f, h - pad * 2 - 1f, arc, arc
                    )
                )
            }
            g2.color = bg
            g2.fill(shape)

            // 边框
            val borderColor = when {
                selected -> AccountUiColors.accent
                isActive -> AccountUiColors.borderActive
                hover -> AccountUiColors.borderActive
                else -> AccountUiColors.border
            }
            g2.color = borderColor
            g2.stroke = BasicStroke(if (bordered) 1.6f else 1f)
            g2.draw(shape)

            // 左侧强调竖条：激活或选中时出现
            if (bordered) {
                val barH = (h - JBUI.scale(40)).coerceAtLeast(JBUI.scale(24))
                val barW = JBUI.scale(3)
                g2.color = AccountUiColors.accent
                g2.fill(
                    RoundRectangle2D.Float(
                        pad + JBUI.scale(4).toFloat(), ((h - barH) / 2).toFloat(),
                        barW.toFloat(), barH.toFloat(), barW.toFloat(), barW.toFloat()
                    )
                )
            }

            // 头像（斜向渐变，避免纯色圆点）
            val d = JBUI.scale(42)
            val ax = JBUI.scale(16)
            val ay = (h - d) / 2
            g2.paint = GradientPaint(
                ax.toFloat(), ay.toFloat(), avatarColor.brighter(),
                (ax + d).toFloat(), (ay + d).toFloat(), avatarColor.darker()
            )
            g2.fillOval(ax, ay, d, d)
            g2.color = Color.WHITE
            g2.font = font.deriveFont(Font.BOLD, JBUI.scale(17).toFloat())
            val fmInitial = g2.fontMetrics
            val tw = fmInitial.stringWidth(initial)
            g2.drawString(initial, ax + (d - tw) / 2, ay + (d - fmInitial.height) / 2 + fmInitial.ascent)

            // 文本区域
            val textX = ax + d + JBUI.scale(14)
            val blockH = JBUI.scale(64)
            var y = (h - blockH) / 2 + JBUI.scale(14)

            // ---- 第一行：名称 + 当前徽标 ----
            g2.font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            val fmName = g2.fontMetrics
            val badgeW = JBUI.scale(46)
            val badgeGap = JBUI.scale(8)
            val nameMax = (w - textX - JBUI.scale(14) - if (isActive) badgeW + badgeGap else 0)
                .coerceAtLeast(JBUI.scale(40))
            val nameText = ellipsize(snapshot.displayName, fmName, nameMax)

            g2.color = AccountUiColors.textPrimary
            g2.drawString(nameText, textX, y)
            val nameWidth = fmName.stringWidth(nameText)

            if (isActive) {
                val badgeX = textX + nameWidth + badgeGap
                val badgeH = JBUI.scale(18)
                g2.color = AccountUiColors.accent
                g2.fill(
                    RoundRectangle2D.Float(
                        badgeX.toFloat(), (y - JBUI.scale(13)).toFloat(),
                        badgeW.toFloat(), badgeH.toFloat(), (badgeH / 2).toFloat(), (badgeH / 2).toFloat()
                    )
                )
                g2.color = Color.WHITE
                g2.font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                g2.drawString("当前", badgeX + JBUI.scale(11), y)
            }

            // ---- 第二行：类型标签 + 手机号 + 认证域 ----
            y += JBUI.scale(21)
            var x = textX
            g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            val fmBody = g2.fontMetrics
            val maxX = w - JBUI.scale(14)

            val isEnterprise = snapshot.type.equals("enterprise", true)
            val typeText = if (isEnterprise) "企业" else "个人"
            val tagW = fmBody.stringWidth(typeText) + JBUI.scale(12)
            g2.color = if (isEnterprise) AccountUiColors.tagEnterpriseBg else AccountUiColors.tagPersonalBg
            g2.fill(
                RoundRectangle2D.Float(
                    x.toFloat(), (y - JBUI.scale(11)).toFloat(),
                    tagW.toFloat(), JBUI.scale(16).toFloat(), JBUI.scale(8).toFloat(), JBUI.scale(8).toFloat()
                )
            )
            g2.color = if (isEnterprise) AccountUiColors.tagEnterpriseFg else AccountUiColors.tagPersonalFg
            g2.drawString(typeText, x + JBUI.scale(6), y)
            x += tagW + JBUI.scale(8)

            g2.color = AccountUiColors.textMuted
            val phone = snapshot.phoneNumber.ifBlank { "—" }
            if (x + fmBody.stringWidth(phone) < maxX) {
                g2.drawString(phone, x, y)
                x += fmBody.stringWidth(phone) + JBUI.scale(10)
                g2.color = AccountUiColors.divider
                g2.drawLine(x - JBUI.scale(6), y - JBUI.scale(8), x - JBUI.scale(6), y + JBUI.scale(1))
                g2.color = AccountUiColors.textMuted
                val domain = snapshot.domain.ifBlank { "—" }
                if (x + fmBody.stringWidth(domain) < maxX) g2.drawString(domain, x, y)
            }

            // ---- 第三行：域名徽标 + Token 状态 + 配额进度条 ----
            y += JBUI.scale(21)
            x = textX

            val domainText = snapshot.domainHost
            if (domainText.isNotBlank()) {
                val alt = domainText.contains("workbuddy")
                val dw = fmBody.stringWidth(domainText) + JBUI.scale(12)
                g2.color = if (alt) AccountUiColors.tagDomainAltBg else AccountUiColors.tagDomainBg
                g2.fill(
                    RoundRectangle2D.Float(
                        x.toFloat(), (y - JBUI.scale(11)).toFloat(),
                        dw.toFloat(), JBUI.scale(16).toFloat(), JBUI.scale(8).toFloat(), JBUI.scale(8).toFloat()
                    )
                )
                g2.color = if (alt) AccountUiColors.tagDomainAltFg else AccountUiColors.tagDomainFg
                g2.drawString(domainText, x + JBUI.scale(6), y)
                x += dw + JBUI.scale(10)
            }

            // Token 状态：小圆点 + 文案，比纯文字更快识别
            val (tokenText, tokenColor) = when {
                snapshot.isRefreshTokenExpired -> "Token 已过期" to AccountUiColors.danger
                snapshot.isAccessTokenExpired -> "Access 已过期" to AccountUiColors.warning
                else -> "Token 有效" to AccountUiColors.success
            }
            val dot = JBUI.scale(5)
            g2.color = tokenColor
            g2.fillOval(x, y - dot - JBUI.scale(2), dot, dot)
            x += dot + JBUI.scale(5)
            g2.drawString(tokenText, x, y)
            x += fmBody.stringWidth(tokenText) + JBUI.scale(14)

            // 配额进度条
            val ok = quota?.takeIf { it.error == null && it.packages.isNotEmpty() }
            when {
                // 面板很窄时进度条放不下，退化成纯百分比，避免被裁成半截
                ok != null && maxX - x < JBUI.scale(60) -> {
                    g2.color = AccountUiColors.textSecondary
                    g2.drawString("${"%.0f".format(ok.usableRemainPercent)}%", x, y)
                }

                ok != null -> {
                    val barW = JBUI.scale(120)
                    val barH = JBUI.scale(8)
                    val barX = x
                    val barY = y - barH + JBUI.scale(2)

                    g2.color = AccountUiColors.barTrack
                    g2.fill(
                        RoundRectangle2D.Float(
                            barX.toFloat(), barY.toFloat(),
                            barW.toFloat(), barH.toFloat(), barH.toFloat(), barH.toFloat()
                        )
                    )

                    // 进度：按「未过期细项」计算，避免已过期的赠送包撑高百分比
                    val pct = ok.usableRemainPercent / 100.0
                    val fillW = (barW * pct).toInt().coerceAtLeast(0)
                    val fillColor = when {
                        ok.usableRemainPercent <= 10 -> AccountUiColors.danger
                        ok.usableRemainPercent <= 30 -> AccountUiColors.warning
                        else -> AccountUiColors.barFill
                    }
                    if (fillW > 0) {
                        g2.color = fillColor
                        g2.fill(
                            RoundRectangle2D.Float(
                                barX.toFloat(), barY.toFloat(),
                                fillW.toFloat(), barH.toFloat(), barH.toFloat(), barH.toFloat()
                            )
                        )
                        if (fillW > JBUI.scale(6)) {
                            g2.color = Color(255, 255, 255, 46)
                            g2.fill(
                                RoundRectangle2D.Float(
                                    barX.toFloat(), barY.toFloat(),
                                    fillW.toFloat(), (barH / 2).toFloat(),
                                    barH.toFloat(), barH.toFloat()
                                )
                            )
                        }
                    }

                    val quotaText = ok.summaryText()
                    val quotaTextX = barX + barW + JBUI.scale(10)
                    if (quotaTextX + fmBody.stringWidth(quotaText) <= maxX) {
                        g2.color = AccountUiColors.textSecondary
                        g2.drawString(quotaText, quotaTextX, y)
                    }
                }

                quota?.error != null -> {
                    g2.color = AccountUiColors.textFaint
                    g2.drawString("额度查询失败", x, y)
                }

                else -> {
                    g2.color = AccountUiColors.textFaint
                    g2.drawString("额度未刷新", x, y)
                }
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

        /** 超出可用宽度的文本按字符截断并追加省略号 */
        fun ellipsize(text: String, fm: FontMetrics, maxWidth: Int): String {
            if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "…"
            val ellipsisW = fm.stringWidth(ellipsis)
            var end = text.length - 1
            while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) end--
            return text.substring(0, end) + ellipsis
        }
    }
}
