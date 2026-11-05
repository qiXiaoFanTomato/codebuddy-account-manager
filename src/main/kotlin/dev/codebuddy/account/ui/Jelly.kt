package dev.codebuddy.account.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.Timer

/**
 * 表面绘制与动画基础设施。
 *
 * ## 材质层次（素墨仪表：表面靠色阶分层，不靠高光）
 *
 * - [JellyMaterial.FLAT] 纯色：只填色，外层卡片、Hero、轨道一律用它。
 * - [JellyMaterial.SOFT] 柔和：纯色 + 顶部 1px 亮线，给需要与外层区分的嵌套块。
 * - [JellyMaterial.GEL] 兼容别名，行为等同 [JellyMaterial.FLAT]。
 *   旧调用点尚未全部清理，保留常量避免大范围改动，P5 统一删除。
 *
 * 不再绘制体积渐变、径向柔光、底部内阴影与扫光：这些层在窄工具窗里
 * 只会让文字对比度下降，并不增加信息。
 *
 * ## 形状层次
 *
 * - 圆角矩形：卡片、内嵌块、按钮等容器（半径见 [AccountUiRadius]）。
 * - 胶囊（圆角 = 高度一半）：只给状态标签与进度条这类「小而确定」的元素。
 *
 * ## 动画节制
 *
 * - 所有补间共用 [JellyClock] 一个 60fps 时钟，避免每个组件各起定时器。
 * - [Jelly.animationsEnabled] 关闭后所有补间直接跳到终值，界面依然完整可用。
 */
object Jelly {

    /** 动画帧间隔，约 60fps */
    const val FRAME_MS = 16

    /**
     * 全局动画开关。
     *
     * 置 false 后所有补间直接跳到终值（不产生中间帧），
     * 用于低性能环境或用户要求「减少动效」的场景。
     * 也可用系统属性 `-Dcodebuddy.account.ui.animations=off` 启动时关闭。
     */
    @Volatile
    var animationsEnabled: Boolean = System.getProperty("codebuddy.account.ui.animations", "on") != "off"

    /** 胶囊形状：圆角半径取高度一半 */
    fun pill(x: Float, y: Float, w: Float, h: Float): RoundRectangle2D =
        RoundRectangle2D.Float(x, y, w, h, h, h)

    /** 圆角矩形 */
    fun round(x: Float, y: Float, w: Float, h: Float, arc: Float): RoundRectangle2D =
        RoundRectangle2D.Float(x, y, w, h, arc, arc)

    /** 提亮：f=0 原色，f=1 纯白 */
    fun lighten(color: Color, f: Float): Color = blend(color, Color.WHITE, f)

    /** 压暗：f=0 原色，f=1 纯黑 */
    fun darken(color: Color, f: Float): Color = blend(color, Color.BLACK, f)

    /** 两色按比例混合：t=0 取 [from]，t=1 取 [to] */
    fun mix(from: Color, to: Color, t: Float): Color = blend(from, to, t)

    /** 叠加透明度：alpha 取 0~1 */
    fun alpha(color: Color, a: Float): Color =
        Color(color.red, color.green, color.blue, (255 * a.coerceIn(0f, 1f)).toInt())

    /** 混合颜色，alpha 保持原样（主题切换后由重绘重新取色） */
    private fun blend(base: Color, target: Color, f: Float): Color {        val t = f.coerceIn(0f, 1f)
        return Color(
            (base.red + (target.red - base.red) * t).toInt().coerceIn(0, 255),
            (base.green + (target.green - base.green) * t).toInt().coerceIn(0, 255),
            (base.blue + (target.blue - base.blue) * t).toInt().coerceIn(0, 255),
            base.alpha,
        )
    }

    /**
     * 卡片落影：单层 1px 偏移，只做「贴在画布上」的分离，不营造体积。
     *
     * @param strength 0~1，hover 时可略增，但不要超过 1.2
     */
    fun paintShadow(g2: Graphics2D, shape: Shape, strength: Float = 1f) {
        val base = AccountUiColors.cardShadow
        val alpha = (base.alpha * strength * 0.9f).toInt().coerceIn(0, 255)
        if (alpha <= 0) return
        val moved = AffineTransform.getTranslateInstance(0.0, 1.0)
            .createTransformedShape(shape)
        g2.color = Color(base.red, base.green, base.blue, alpha)
        g2.fill(moved)
    }

    /**
     * 按材质层级绘制主体。
     *
     * 表面是纯色块：FLAT / GEL 只填色；SOFT 额外补一条顶部 1px 亮线，
     * 用来区分「嵌套块」与「外层卡片」。不再画体积渐变与内阴影——
     * 那些层在窄工具窗里只会压低文字对比度，不增加信息。
     *
     * @param material 材质层级
     */
    fun paintBody(
        g2: Graphics2D,
        shape: Shape,
        base: Color,
        material: JellyMaterial = JellyMaterial.SOFT,
    ) {
        val b = shape.bounds
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        if (w <= 0f || h <= 0f) return

        g2.color = base
        g2.fill(shape)

        if (material != JellyMaterial.SOFT) return

        // 档案柜：嵌套块只用一条顶部亮线区分层级，不画弧形高光
        val oldClip = g2.clip
        g2.clip(shape)
        g2.color = Color(255, 255, 255, if (isDark(base)) 14 else 110)
        g2.fill(java.awt.geom.Rectangle2D.Float(b.x.toFloat(), b.y.toFloat(), w, 1f))
        g2.clip = oldClip
    }

    /**
     * 进度条刻度：每 9px 一条 1px 竖线。
     *
     * 对应 HTML 的 `.meter::after { repeating-linear-gradient(... 0 9px, ... 9px 10px) }`，
     * 让黄铜充盈看起来像量具读数，而不是一条普通进度条。
     */
    fun paintTicks(g2: Graphics2D, x: Float, y: Float, w: Float, h: Float, step: Int = 9) {
        if (w <= 0f || h <= 0f || step <= 0) return
        g2.color = AccountUiColors.tick
        var px = x + step
        while (px < x + w) {
            g2.fill(java.awt.geom.Rectangle2D.Float(px, y, 1f, h))
            px += step + 1
        }
    }

    /** 1px 虚线：档案卡底部、切换日志的分隔用 */
    fun paintDashedLine(g2: Graphics2D, x1: Float, y: Float, x2: Float, color: Color) {
        val oldStroke = g2.stroke
        g2.color = color
        g2.stroke = BasicStroke(
            1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10f, floatArrayOf(4f, 4f), 0f,
        )
        g2.draw(java.awt.geom.Line2D.Float(x1, y, x2, y))
        g2.stroke = oldStroke
    }

    /** 判断颜色是否偏暗（用于决定内描边用白还是黑） */
    fun isDark(color: Color): Boolean =
        (color.red * 0.299 + color.green * 0.587 + color.blue * 0.114) < 128

    /** 判断颜色是否偏亮 */
    fun isLight(color: Color): Boolean = !isDark(color)

    /** 边缘描边：1px 描边界定表面范围 */
    fun paintEdge(g2: Graphics2D, shape: Shape, color: Color, width: Float = 1f) {
        g2.color = color
        g2.stroke = BasicStroke(width)
        g2.draw(shape)
    }

    /**
     * 焦点环：键盘 Tab 到的组件统一套一圈主色描边 + 外发光。
     *
     * @param focused 是否处于焦点态
     */
    fun paintFocusRing(g2: Graphics2D, shape: Shape, focused: Boolean) {
        if (!focused) return
        val c = AccountUiColors.focusRing
        g2.stroke = BasicStroke(1.4f)
        g2.color = c
        g2.draw(shape)
        g2.stroke = BasicStroke(3f)
        g2.color = Color(c.red, c.green, c.blue, 46)
        g2.draw(shape)
    }
}

/**
 * 材质层级。
 *
 * SOFT（纯色 + 顶部亮线，嵌套块）→ FLAT（纯色，外层卡片 / Hero / 轨道）。
 * GEL 是旧调用点的兼容别名，绘制行为等同 FLAT。
 */
enum class JellyMaterial {
    /** 兼容别名：等同 [FLAT]，待 P5 清理调用点后删除 */
    GEL,

    /** 柔和：纯色 + 顶部 1px 亮线 */
    SOFT,

    /** 纯色 */
    FLAT,
}

/**
 * 全局动画时钟：所有补间共用一个 60fps 定时器。
 *
 * 单时钟的好处是同时播放几十个动画时也只有一次定时器唤醒，
 * 避免每个卡片 / 进度条各起一个 Timer 造成的 EDT 抖动。
 */
internal object JellyClock {

    private val active = LinkedHashSet<Tween>()
    private var timer: Timer? = null

    fun add(tween: Tween) {
        if (!active.add(tween)) return
        if (timer == null) {
            timer = Timer(Jelly.FRAME_MS) { tick() }.apply { isRepeats = true }
            timer?.start()
        }
    }

    fun remove(tween: Tween) {
        if (!active.remove(tween)) return
        if (active.isEmpty()) {
            timer?.stop()
            timer = null
        }
    }

    /** 延时回调（一次性定时器），用于入场动画的错峰延迟 */
    fun delay(ms: Int, action: () -> Unit): Timer {
        val timer = Timer(ms) { action() }.apply { isRepeats = false }
        timer.start()
        return timer
    }

    private fun tick() {
        // 复制一份再推进：回调里可能会启动 / 停止其它补间
        for (tween in active.toList()) tween.step()
    }
}

/** 缓动函数：入参为 0~1 的线性进度，返回 0~1 的插值进度 */
object Easing {

    /** 匀速 */
    fun linear(t: Double): Double = t

    /** 减速：收尾最快，用于位移类动画 */
    fun outCubic(t: Double): Double = 1 - Math.pow(1 - t, 3.0)

    /** 更重的减速：界面默认曲线，起步快、收尾稳，没有回弹的轻浮感 */
    fun outQuint(t: Double): Double = 1 - Math.pow(1 - t, 5.0)

    /** 指数减速：用于光扫、淡入这类「一下子就位」的效果 */
    fun outExpo(t: Double): Double = if (t >= 1.0) 1.0 else 1 - Math.pow(2.0, -10.0 * t)

    /** 前后缓动：中段匀速 */
    fun inOutCubic(t: Double): Double =
        if (t < 0.5) 4 * t * t * t else 1 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0

    /** 回弹：果冻按压与落位的标志性曲线 */
    fun outBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = c1 + 1.0
        val p = t - 1.0
        return 1.0 + c3 * p * p * p + c1 * p * p
    }

    /** 果冻抖动：过冲后回落，用于数值与位置的落位 */
    fun outElastic(t: Double): Double {
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        val p = 0.36
        return Math.pow(2.0, -10.0 * t) * Math.sin((t - p / 4.0) * (2.0 * Math.PI) / p) + 1.0
    }

    /** 步进：只在终点跳一次，用于刻度、翻牌这类「机械」切换 */
    fun step(t: Double): Double = if (t >= 1.0) 1.0 else 0.0
}

/**
 * 极简补间动画：在 EDT 上按帧推进 0~1 的进度并回调。
 *
 * 进度达到 1 时自动停止；[stop] 可提前取消（不会触发最后一帧回调）。
 */
class Tween(
    private val durationMs: Int,
    private val easing: (Double) -> Double,
    private val onUpdate: (Double) -> Unit,
) {

    private var startedAt = 0L
    private var finished = true

    /** 是否正在播放 */
    val running: Boolean get() = !finished

    fun start() {
        stop()
        // 动画总开关关闭时直接给终值：界面表现一致，但不产生中间帧
        if (!Jelly.animationsEnabled || durationMs <= 0) {
            onUpdate(1.0)
            finished = true
            return
        }
        startedAt = System.currentTimeMillis()
        finished = false
        JellyClock.add(this)
    }

    /** 推进一帧（由 [JellyClock] 驱动） */
    internal fun step() {
        if (finished) return
        val elapsed = System.currentTimeMillis() - startedAt
        val p = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
        onUpdate(easing(p))
        if (p >= 1.0) {
            // 缓动曲线的终值未必等于 1，这里补一次精确终值
            onUpdate(1.0)
            finish()
        }
    }

    fun stop() {
        if (finished) return
        finish()
    }

    private fun finish() {
        finished = true
        JellyClock.remove(this)
    }
}

/**
 * 组件内持有的动画数值：改动即触发补间并重绘宿主组件。
 *
 * 默认曲线是 [Easing.outQuint]：界面里绝大多数补间是状态切换，
 * 回弹曲线只留给胶囊落位、按压松手这类需要「手感」的地方。
 *
 * @param host 数值变化时触发重绘的组件
 */
class JellyValue(
    private val host: JComponent,
    initial: Double = 0.0,
) {

    /** 当前动画值 */
    var value: Double = initial
        private set

    private var tween: Tween? = null

    /**
     * 补间到目标值。
     *
     * @param onFrame 每帧回调，参数为当前插值后的值
     */
    fun animateTo(
        target: Double,
        durationMs: Int = 220,
        easing: (Double) -> Double = { t -> Easing.outQuint(t) },
        onFrame: ((Double) -> Unit)? = null,
    ) {
        val from = value
        if (Math.abs(from - target) < 1e-6) return
        tween?.stop()
        tween = Tween(durationMs, easing) { p ->
            value = from + (target - from) * p
            onFrame?.invoke(value)
            host.repaint()
        }
        tween?.start()
    }

    /** 直接跳到目标值，不播放动画 */
    fun jumpTo(target: Double) {
        tween?.stop()
        tween = null
        value = target
        host.repaint()
    }

    fun stop() {
        tween?.stop()
        tween = null
    }
}


