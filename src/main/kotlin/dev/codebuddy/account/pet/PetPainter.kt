package dev.codebuddy.account.pet

import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.QuadCurve2D
import java.awt.geom.RoundRectangle2D

/**
 * 桌宠形象：档案柜精灵（纯 Swing 绘制，不依赖任何外部素材）。
 *
 * 造型沿用「档案柜」视觉方向：
 * - 身体 = 档案卡纸面（[AccountUiColors.cardBg]）+ 1px 铅线描边
 * - 左上角挂档案卡 tab（墨蓝实心 + 黄铜刻度）
 * - 底部黄铜等宽读数显示剩余额度百分比
 * - 低额度 / 查询失败时右上角盖朱印
 *
 * 全部按 132 x 156 的基准坐标绘制，再整体缩放到实际像素，
 * 因此改变尺寸只影响缩放比，不需要重算几何。
 */
object PetPainter {

    const val BASE_W = 132f
    const val BASE_H = 156f

    /** 低额度阈值（%），与卡片「低额度」提示口径一致 */
    const val LOW_PERCENT = 20.0

    /** 缓存的透明窗口能力探测结果：能力在进程生命周期内不会变 */
    private val translucent: Boolean by lazy { detectTranslucency() }

    /** 当前系统是否支持逐像素透明窗口（不支持时降级为不透明纸面底） */
    fun supportsTranslucency(): Boolean = translucent

    /**
     * 窗口尺寸。
     *
     * 素材可用时以「全部动画在原画框中的并集区域」为基准（100% 即原版逐像素尺寸）；
     * 素材缺失时才退回自绘形象 132×156 的基准。
     */
    fun preferredSize(scalePercent: Int): Dimension {
        val f = (scalePercent / 100f).coerceIn(0.4f, 2.5f)
        val bounds = PetAnimationLibrary.bounds
        if (bounds.width > 0 && bounds.height > 0) {
            return Dimension(
                JBUI.scale(bounds.width * f).toInt().coerceAtLeast(24),
                JBUI.scale(bounds.height * f).toInt().coerceAtLeast(24),
            )
        }
        val w = JBUI.scale(BASE_W * f).toInt().coerceAtLeast(24)
        val h = JBUI.scale(BASE_H * f).toInt().coerceAtLeast(24)
        return Dimension(w, h)
    }

    /**
     * 绘制桌宠。
     *
     * @param hover 0~1 悬停强度（只提亮描边与投影，不做位移）
     * @param press 0~1 按压强度（身体轻微回弹）
     * @param blink 0~1 眨眼强度（1 表示完全闭眼）
     * @param lift 呼吸位移（基准坐标系下的上浮量，投影不跟随）
     * @param badge 是否显示额度读数
     */
    fun paint(
        g2: Graphics2D,
        width: Int,
        height: Int,
        data: PetData,
        hover: Double,
        press: Double,
        blink: Double,
        lift: Double = 0.0,
        badge: Boolean = true,
    ) {
        if (width <= 0 || height <= 0) return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 不支持透明窗口时，先铺一层纸面底，避免露出系统窗口灰底
        if (!translucent) {
            g2.color = AccountUiColors.panelBg
            g2.fillRect(0, 0, width, height)
        }

        val sx = width / BASE_W
        val sy = height / BASE_H
        val scale = 1.0 - press * 0.05 + hover * 0.012
        val bodyCx = 66.0
        val bodyCy = 76.0
        val transform = AffineTransform.getTranslateInstance(bodyCx, bodyCy)
        transform.scale(sx * scale, sy * scale)
        transform.translate(-bodyCx, -bodyCy)
        g2.transform(transform)

        val hoverF = hover.toFloat()

        // ---- 投影：贴地椭圆，悬停加深（不跟随呼吸位移）----
        g2.color = alpha(AccountUiColors.ink, 0.10f + 0.06f * hoverF)
        g2.fill(Ellipse2D.Float(26f, 138f, 80f, 12f))

        // ---- 呼吸：以身体为单位整体上浮 ----
        if (lift != 0.0) g2.translate(0.0, -lift)

        // ---- 档案卡 tab（在身体之后会被压住一部分，形成文件夹标签感）----
        g2.color = AccountUiColors.ink
        g2.fill(RoundRectangle2D.Float(10f, 8f, 48f, 20f, 8f, 8f))
        g2.color = AccountUiColors.brass
        g2.fillRect(16, 13, 36, 2)

        // ---- 身体 ----
        val body = RoundRectangle2D.Float(8f, 18f, 116f, 116f, 16f, 16f)
        g2.color = AccountUiColors.cardBg
        g2.fill(body)
        g2.stroke = BasicStroke(1f)
        g2.color = if (hover > 0.45) AccountUiColors.ink else AccountUiColors.rule
        g2.draw(body)

        // ---- 眼睛（眨眼时压扁）----
        val eyeH = (13.0 * (1.0 - blink * 0.86)).coerceAtLeast(1.6).toFloat()
        val eyeTop = 56f + (13f - eyeH) / 2f
        g2.color = AccountUiColors.ink
        g2.fill(RoundRectangle2D.Float(46f, eyeTop, 9f, eyeH, 4.5f, 4.5f))
        g2.fill(RoundRectangle2D.Float(77f, eyeTop, 9f, eyeH, 4.5f, 4.5f))

        // ---- 嘴：低额度时下弯，其余时候微笑 ----
        g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.color = AccountUiColors.ink
        if (data.low) {
            g2.draw(QuadCurve2D.Float(57f, 86f, 66f, 80f, 75f, 86f))
        } else {
            g2.draw(QuadCurve2D.Float(57f, 80f, 66f, 87f, 75f, 80f))
        }

        // ---- 右上角标记：朱印 = 低额度 / 异常；黄铜描环 = 正常 ----
        if (data.low || data.quota?.error != null) {
            g2.color = AccountUiColors.seal
            g2.fill(Ellipse2D.Float(103f, 28f, 12f, 12f))
        } else {
            g2.stroke = BasicStroke(1.2f)
            g2.color = AccountUiColors.brass
            g2.draw(Ellipse2D.Float(103.5f, 28.5f, 11f, 11f))
        }

        // ---- 底部黄铜读数 ----
        if (badge) {
            val plate = RoundRectangle2D.Float(22f, 96f, 88f, 30f, 8f, 8f)
            g2.color = AccountUiColors.itemBg
            g2.fill(plate)
            g2.stroke = BasicStroke(1f)
            g2.color = AccountUiColors.rule
            g2.draw(plate)

            g2.color = AccountUiColors.brass
            g2.fill(RoundRectangle2D.Float(27f, 104f, 3f, 14f, 1.5f, 1.5f))

            val text = data.readout()
            g2.font = Font(Font.MONOSPACED, Font.BOLD, 17)
            val fm = g2.fontMetrics
            val textX = 72f - fm.stringWidth(text) / 2f
            val baseline = 107f + (fm.ascent - fm.descent) / 2f
            g2.color = when {
                data.quota == null -> AccountUiColors.textMuted
                data.quota.error != null -> AccountUiColors.seal
                data.low -> AccountUiColors.seal
                else -> AccountUiColors.brassInk
            }
            g2.drawString(text, textX, baseline)
        } else {
            // 不显示读数时留两条档案卡分隔线，保持版面平衡
            g2.stroke = BasicStroke(1f)
            g2.color = AccountUiColors.divider
            g2.drawLine(26, 104, 106, 104)
            g2.drawLine(26, 116, 92, 116)
        }
    }

    private fun alpha(color: java.awt.Color, a: Float): java.awt.Color =
        java.awt.Color(color.red, color.green, color.blue, (255 * a.coerceIn(0f, 1f)).toInt())

    private fun detectTranslucency(): Boolean = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
    } catch (_: Throwable) {
        false
    }
}
