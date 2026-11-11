package dev.codebuddy.account.pet

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.ui.AccountUiColors
import dev.codebuddy.account.ui.HistoryDialog
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 桌宠浮窗：无边框透明 [JWindow]，只承载形象与交互。
 *
 * 交互（与需求「查看余额 / 对话」对应）：
 * - 悬停：描边转墨蓝、投影加深
 * - 单击：展开余额气泡（再次单击收起），拖动改变位置并持久化
 * - 双击 / 右键菜单：额度明细、历史对话、立即刷新
 *
 * 窗口不抢 IDE 焦点（`isFocusableWindow = false`），不进入任务栏（JWindow 天然无任务栏项）。
 */
class PetWindow(
    private val onRefresh: () -> Unit,
    private val onHide: () -> Unit,
) {

    private val settings: PetSettings get() = PetSettings.getInstance()

    private val window = JWindow()
    private val canvas = PetCanvas()
    private val bubble = PetBubble(
        onRefresh = { onRefresh() },
        onQuotaDetail = { openQuotaDetail() },
        onHistory = { openHistory() },
    )

    /** 帧动画状态机（素材来自原版 webm，见 [PetAnimationLibrary]） */
    private val animator = PetAnimator { canvas.repaint() }

    /** 碎碎念气泡 */
    private val speech = PetSpeechBubble()

    private var dragStartScreen: Point? = null
    private var dragStartLocation: Point? = null
    private var dragging = false

    // ---- 拖拽物理（对齐原项目 physics：重力 / 反弹 / 地面摩擦 / 抛出）----
    private data class DragSample(val at: Long, val point: Point)

    private val dragSamples = ArrayDeque<DragSample>()
    private var physicsTimer: Timer? = null
    private var physicsAt = 0L
    private var px = 0.0
    private var py = 0.0
    private var vx = 0.0
    private var vy = 0.0

    /** 额度变化浮字（如 「−3.34」），展示后自动淡出 */
    private var deltaText: String? = null
    private var deltaAt = 0L

    init {
        window.isAlwaysOnTop = settings.alwaysOnTop
        window.setFocusableWindowState(false)
        window.background = if (PetPainter.supportsTranslucency()) Color(0, 0, 0, 0) else AccountUiColors.panelBg
        window.contentPane = canvas

        // 注意：mouseDragged 属于 MouseMotionListener 的回调，
        // 只用 addMouseListener 注册的话永远不会被调用（表现为「能点不能拖」），
        // 因此同一个 handler 必须同时注册为鼠标与鼠标移动监听。
        val mouseHandler = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                canvas.hoverTarget = 1.0
            }

            override fun mouseExited(e: MouseEvent) {
                canvas.hoverTarget = 0.0
            }

            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showMenu(e)
                    return
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return
                stopPhysics()
                stopMove()
                val start = screenPoint(e)
                dragStartScreen = start
                dragStartLocation = window.location
                dragging = false
                dragSamples.clear()
                dragSamples.addLast(DragSample(System.currentTimeMillis(), start))
                canvas.pressTarget = 1.0
                PetSound.playClick()
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = dragStartScreen ?: return
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val now = screenPoint(e)
                if (!dragging && (Math.abs(now.x - start.x) > DRAG_THRESHOLD || Math.abs(now.y - start.y) > DRAG_THRESHOLD)) {
                    dragging = true
                    bubble.hide()
                    animator.beginDrag()
                }
                if (dragging) {
                    dragSamples.addLast(DragSample(System.currentTimeMillis(), now))
                    while (dragSamples.size > 6) dragSamples.removeFirst()
                    val origin = dragStartLocation ?: return
                    window.setLocation(origin.x + (now.x - start.x), origin.y + (now.y - start.y))
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                canvas.pressTarget = 0.0
                if (!SwingUtilities.isLeftMouseButton(e)) return
                if (dragging) {
                    dragging = false
                    dragStartScreen = null
                    dragStartLocation = null
                    val (releaseVx, releaseVy) = releaseVelocity()
                    // 只有「甩」出去的加速度才触发抛出：普通拖拽只是挪位置，
                    // 松手后原地停住，不受重力影响（与原项目手感一致）
                    val speed = Math.hypot(releaseVx, releaseVy) / physicsScale()
                    if (settings.physicsEnabled && speed >= THROW_MIN_SPEED) {
                        startPhysics(releaseVx, releaseVy)
                    } else {
                        animator.endDrag()
                        persistPosition()
                    }
                    dragSamples.clear()
                    return
                }
                dragStartScreen = null
                dragStartLocation = null

                if (e.clickCount >= 2) {
                    openQuotaDetail()
                } else {
                    performClickAction()
                }
            }
        }
        canvas.addMouseListener(mouseHandler)
        canvas.addMouseMotionListener(mouseHandler)
    }

    val isShowing: Boolean get() = window.isVisible

    val windowBounds: Rectangle get() = window.bounds

    /** 显示桌宠：按停靠位置就位（自由拖动时恢复上次坐标，越界则回落主屏右下角） */
    fun show() {
        stopPhysics()
        stopMove()
        val size = PetPainter.preferredSize(settings.scalePercent)
        window.setBounds(Rectangle(locationFor(size), size))
        applyOpacity()
        window.isVisible = true
        canvas.startAnimation()
        animator.setAnimationsEnabled(settings.animationsEnabled)
        animator.start()
    }

    fun hide() {
        bubble.hide()
        speech.hide()
        stopPhysics()
        stopMove()
        animator.endDrag()
        window.isVisible = false
        canvas.stopAnimation()
        animator.dispose()
    }

    fun dispose() {
        bubble.dispose()
        speech.dispose()
        stopPhysics()
        stopMove()
        animator.endDrag()
        canvas.stopAnimation()
        animator.dispose()
        window.isVisible = false
        window.dispose()
    }

    /** 应用外观设置（尺寸 / 不透明度 / 置顶）；尺寸变化时保留当前中心点 */
    fun applyAppearance() {
        val old = window.bounds
        val size = PetPainter.preferredSize(settings.scalePercent)
        if (size != old.size) {
            val center = Point(old.x + old.width / 2, old.y + old.height / 2)
            window.setBounds(Rectangle(Point(center.x - size.width / 2, center.y - size.height / 2), size))
        }
        window.isAlwaysOnTop = settings.alwaysOnTop
        applyOpacity()
        // 停靠到四角时立刻归位；自由拖动模式保持当前位置
        if (settings.quadrant != PetQuadrant.FREE && window.isVisible) {
            window.setLocation(locationFor(size))
        }
        animator.setAnimationsEnabled(settings.animationsEnabled)
        canvas.applySettings()
        canvas.revalidate()
        canvas.repaint()
        window.validate()
    }

    /** 刷新显示数据（余额变化、账号切换） */
    fun update(data: PetData) {
        canvas.data = data
        animator.updateBalanceTier(data.balanceTier)
        canvas.repaint()
        if (bubble.isShowing) bubble.update(data)
    }

    /** 气泡正在展示时跟随角色位置重算锚点 */
    fun refreshBubbleAnchor() {
        if (bubble.isShowing) bubble.show(characterBounds(), canvas.data)
    }

    private fun applyOpacity() {
        val value = settings.opacityPercent / 100f
        try {
            if (GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                    .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.TRANSLUCENT)
            ) {
                window.opacity = value.coerceIn(0.3f, 1f)
            }
        } catch (_: Throwable) {
            // 不支持透明度的环境保持不透明
        }
    }

    /**
     * 单击只播放点击回应动画与音效，**不弹任何窗口**：
     * 额度信息改由双击弹出（[PetQuotaDialog]），避免误触打断操作。
     */
    private fun performClickAction() {
        animator.onClick()
    }

    private fun toggleBubble() {
        if (bubble.isShowing) {
            bubble.hide()
        } else {
            bubble.show(characterBounds(), canvas.data)
        }
    }

    private fun showMenu(e: MouseEvent) {
        bubble.hide()
        val menu = JPopupMenu()
        menu.add(JMenuItem("刷新一下额度").apply { addActionListener { onRefresh() } })
        menu.add(JMenuItem("看看额度与上一轮消耗…").apply { addActionListener { openQuotaDetail() } })
        menu.add(JMenuItem("让我说句话").apply { addActionListener { whisper(PetWhisper.nextLine()) } })
        menu.add(JMenuItem("看一眼余额").apply { addActionListener { toggleBubble() } })
        menu.add(JMenuItem("翻翻聊过的记录…").apply { addActionListener { openHistory() } })
        menu.add(
            JMenuItem(if (animator.isSleeping) "叫醒我" else "打个盹").apply {
                addActionListener {
                    if (animator.isSleeping) animator.wakeUp() else animator.sleep()
                }
            }
        )
        menu.addSeparator()
        menu.add(JMenuItem("桌宠设置…").apply { addActionListener { openSettings() } })
        menu.add(JMenuItem("先躲起来").apply { addActionListener { onHide() } })
        menu.show(canvas, e.x, e.y)
    }

    /** 双击入口：当前账号余额 + 上一轮对话消耗 */
    private fun openQuotaDetail() {
        bubble.hide()
        val project = activeProject() ?: return
        PetQuotaDialog.show(project)
    }

    /** 定时随机小动作（由 [PetService] 的定时器调用）；返回是否真的演了一段 */
    fun playRandomAction(): Boolean = animator.playRandomAction()

    /** 说一句话：播碎碎念动画 + 上方冒气泡（含配图）；返回是否真的说了 */
    fun whisper(line: PetWhisper.Line): Boolean {
        if (line.text.isBlank()) return false
        // 提示展示期间不插话，避免两个气泡叠在一起
        if (speech.isShowing && noticeActive) return false
        animator.playWhisper()
        speech.show(line.text, characterBounds(), line.meme)
        return true
    }

    /** 提示是否正在由桌宠承载（碎碎念 / 随机动作据此让路） */
    var noticeActive: Boolean = false
        private set

    /** 用桌宠承载一条系统提示：先收起额度气泡，再播对应动作 + 冒提示气泡 */
    fun showNotice(level: PetNotifier.Level, title: String, content: String, durationMs: Int) {
        if (!window.isVisible) return
        bubble.hide()
        noticeActive = true
        animator.playNotice(level)
        speech.showNotice(level, title, content, characterBounds(), durationMs)
    }

    /** 当前提示结束（由服务侧定时器调用） */
    fun hideNotice() {
        noticeActive = false
        speech.hide()
    }

    /**
     * 额度刷新后展示变化量，并触发对应反应：
     * - 减少 → 从「心虚 / 心疼」动作组轮转一个反应（不再只播同一个余额档动画）
     * - 增加 → 按新档位重播余额动画
     */
    fun showQuotaDelta(delta: Double, tier: Int) {
        if (!delta.isFinite() || Math.abs(delta) < 0.005) return
        deltaText = (if (delta > 0) "+" else "−") + dev.codebuddy.account.usage.formatCredit(Math.abs(delta))
        deltaAt = System.currentTimeMillis()
        if (delta < 0) {
            animator.playQuotaDownReaction()
        } else {
            animator.replayBalance(tier)
        }
        canvas.repaint()
    }

    /**
     * 角色当前在屏幕上的实际位置。
     *
     * 窗口是按「全部动画的并集区域」开的，比角色本身大不少；
     * 气泡必须挂在角色身上而不是窗口上，否则看起来离得很远。
     */
    fun characterBounds(): Rectangle {
        val animation = animator.currentAnimation
        val bounds = PetAnimationLibrary.bounds
        if (animation == null || bounds.width <= 0) return windowBounds
        val scale = window.width.toDouble() / bounds.width
        val x = window.x + ((animation.offsetX - bounds.x) * scale).toInt()
        val y = window.y + ((animation.offsetY - bounds.y) * scale).toInt()
        val w = (animation.width * scale).toInt().coerceAtLeast(1)
        val h = (animation.height * scale).toInt().coerceAtLeast(1)
        return Rectangle(x, y, w, h)
    }

    private fun openHistory() {
        bubble.hide()
        val project = activeProject() ?: return
        val labels = try {
            AccountLibrary.getInstance().listSaved().associate { it.accountKey to it.displayName }
        } catch (_: Exception) {
            emptyMap()
        }
        HistoryDialog.show(project, labels)
    }

    private fun openSettings() {
        bubble.hide()
        PetSettingsDialog.show { PetService.getInstance().applySettings() }
    }

    /** 手动拖动后改为「自由拖动」模式并记住坐标 */
    private fun persistPosition() {
        settings.quadrant = PetQuadrant.FREE
        settings.savePosition(window.x, window.y)
    }

    private fun screenPoint(e: MouseEvent): Point = e.locationOnScreen

    // ---------------------------------------------------------------
    // 屏幕上跑动（原项目 moves 池）
    // ---------------------------------------------------------------

    private var moveTimer: Timer? = null

    /**
     * 在屏幕上跑一段：播放移动动画的同时把窗口平移到目标位置。
     *
     * 时间轴对齐原项目 `moves`：lead 期间原地起步、中段匀速位移、tail 收尾站定。
     *
     * @return false 表示当前不方便（睡着 / 被抓起 / 已在移动 / 没有可移动空间）
     */
    fun performMove(): Boolean {
        if (!window.isVisible) return false
        if (animator.isSleeping || animator.isDragging) return false
        if (moveTimer != null) return false
        val id = PetAnimationLibrary.randomMoveId() ?: return false
        val animation = PetAnimationLibrary.get(id) ?: return false

        val scale = physicsScale()
        val screen = screenBounds()
        val minX = screen.x
        val maxX = (screen.x + screen.width - window.width).coerceAtLeast(minX)
        val distance = (MIN_MOVE_DIST + Math.random() * (MAX_MOVE_DIST - MIN_MOVE_DIST)) * scale
        val dir = if (Math.random() < 0.5) -1 else 1
        val startX = window.x
        val targetX = (startX + dir * distance).toInt().coerceIn(minX, maxX)
        if (Math.abs(targetX - startX) < JBUI.scale(10)) return false

        stopPhysics()
        animator.playMove(id)

        val totalMs = animation.frameCount.toLong() * 1000L / animation.fps.coerceAtLeast(1)
        val travelMs = (totalMs - MOVE_LEAD_MS - MOVE_TAIL_MS).coerceAtLeast(900L)
        val startedAt = System.currentTimeMillis()
        moveTimer = Timer(16) { event ->
            val elapsed = System.currentTimeMillis() - startedAt - MOVE_LEAD_MS
            if (elapsed < 0) return@Timer
            val progress = (elapsed.toDouble() / travelMs).coerceIn(0.0, 1.0)
            window.setLocation((startX + (targetX - startX) * progress).toInt(), window.y)
            if (progress >= 1.0) {
                (event.source as? Timer)?.stop()
                moveTimer = null
                persistPosition()
            }
        }.apply { start() }
        return true
    }

    private fun stopMove() {
        moveTimer?.stop()
        moveTimer = null
    }

    // ---------------------------------------------------------------
    // 拖拽物理（重力 / 反弹 / 地面摩擦 / 抛出）
    // ---------------------------------------------------------------

    /** 松手速度：用最近 160ms 的位移估算，并按原项目的 throwPower 上限截断 */
    private fun releaseVelocity(): Pair<Double, Double> {
        if (dragSamples.size < 2) return 0.0 to 0.0
        val last = dragSamples.last()
        val first = dragSamples.firstOrNull { last.at - it.at <= 160 } ?: dragSamples.first()
        val dt = (last.at - first.at).coerceAtLeast(1) / 1000.0
        val limit = THROW_LIMIT * physicsScale()
        val rawVx = (last.point.x - first.point.x) / dt
        val rawVy = (last.point.y - first.point.y) / dt
        return rawVx.coerceIn(-limit, limit) to rawVy.coerceIn(-limit, limit)
    }

    /** 物理量缩放：100% 尺寸即原项目手感，放大桌宠时按比例放大 */
    private fun physicsScale(): Double {
        val base = PetAnimationLibrary.bounds.width
        if (base <= 0) return 1.0
        return (window.width.toDouble() / base).coerceIn(0.4, 3.0)
    }

    private fun startPhysics(initialVx: Double, initialVy: Double) {
        stopPhysics()
        px = window.x.toDouble()
        py = window.y.toDouble()
        vx = initialVx
        vy = initialVy
        physicsAt = System.currentTimeMillis()
        physicsTimer = Timer(PHYSICS_FRAME_MS) { physicsTick() }.apply { start() }
    }

    private fun stopPhysics() {
        physicsTimer?.stop()
        physicsTimer = null
    }

    /** 一帧物理：重力加速 → 位移 → 屏幕四边反弹 → 落地摩擦；停下后回到待机并记坐标 */
    private fun physicsTick() {
        val now = System.currentTimeMillis()
        var dt = (now - physicsAt) / 1000.0
        physicsAt = now
        if (dt <= 0.0) return
        dt = dt.coerceAtMost(0.05)

        val scale = physicsScale()
        vy += GRAVITY * scale * dt
        px += vx * dt
        py += vy * dt

        val screen = screenBounds()
        val minX = screen.x.toDouble()
        val minY = screen.y.toDouble()
        val maxX = (screen.x + screen.width - window.width).toDouble().coerceAtLeast(minX)
        val maxY = (screen.y + screen.height - window.height).toDouble().coerceAtLeast(minY)

        var onGround = false
        if (px < minX) {
            px = minX
            vx = -vx * RESTITUTION
        }
        if (px > maxX) {
            px = maxX
            vx = -vx * RESTITUTION
        }
        if (py < minY) {
            py = minY
            vy = -vy * RESTITUTION
        }
        if (py > maxY) {
            py = maxY
            onGround = true
            vy = -vy * RESTITUTION
            if (Math.abs(vy) < BOUNCE_STOP * scale) vy = 0.0
            vx *= (1.0 - GROUND_FRICTION * dt).coerceIn(0.0, 1.0)
        }
        window.setLocation(px.toInt(), py.toInt())

        if (onGround && vy == 0.0 && Math.abs(vx) < 25.0 * scale) {
            stopPhysics()
            animator.endDrag()
            persistPosition()
        }
    }

    /** 桌宠当前所在屏幕的完整区域（多屏时按窗口中心判定） */
    private fun screenBounds(): Rectangle {
        val center = Point(window.x + window.width / 2, window.y + window.height / 2)
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val device = env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(center) }
            ?: env.defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    private fun ensureOnScreen(point: Point, size: Dimension): Point {
        val rect = Rectangle(point, size)
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
        val visible = screens.any { it.intersects(rect) }
        return if (visible) point else defaultLocation(size)
    }

    /** 按停靠设置算出窗口左上角 */
    private fun locationFor(size: Dimension): Point {
        val quadrant = settings.quadrant
        if (quadrant != PetQuadrant.FREE) return cornerLocation(quadrant, size)
        val saved = settings.savedPosition?.let { Point(it.first, it.second) }
        return saved?.let { ensureOnScreen(it, size) } ?: defaultLocation(size)
    }

    /** 四角吸附位置：与主屏可用区域（已扣掉任务栏）留出固定边距 */
    private fun cornerLocation(quadrant: PetQuadrant, size: Dimension): Point {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val mx = JBUI.scale(CORNER_MARGIN_X)
        val my = JBUI.scale(CORNER_MARGIN_Y)
        val left = bounds.x + mx
        val right = bounds.x + bounds.width - size.width - mx
        val top = bounds.y + my
        val bottom = bounds.y + bounds.height - size.height - my
        return when (quadrant) {
            PetQuadrant.TOP_LEFT -> Point(left, top)
            PetQuadrant.TOP_RIGHT -> Point(right, top)
            PetQuadrant.BOTTOM_LEFT -> Point(left, bottom)
            PetQuadrant.BOTTOM_RIGHT -> Point(right, bottom)
            PetQuadrant.FREE -> defaultLocation(size)
        }
    }

    /** 默认位置：主屏右下角，避开任务栏 */
    private fun defaultLocation(size: Dimension): Point = cornerLocation(PetQuadrant.BOTTOM_RIGHT, size)

    private fun activeProject(): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDefault }

    // ---------------------------------------------------------------
    // 画布：形象绘制 + 动画
    // ---------------------------------------------------------------

    private inner class PetCanvas : JComponent() {

        var data: PetData = PetData.empty()

        var hoverTarget: Double = 0.0
        var pressTarget: Double = 0.0

        private var hover = 0.0
        private var press = 0.0
        private var timer: Timer? = null

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        fun startAnimation() {
            if (timer != null) return
            timer = Timer(PRESS_FRAME_MS) { tick() }.apply { start() }
        }

        fun stopAnimation() {
            timer?.stop()
            timer = null
        }

        /** 悬停 / 按压的缓动量：只影响按压回弹，动画本身由 [PetAnimator] 推进 */
        fun applySettings() {
            if (!settings.animationsEnabled) {
                hover = 0.0
                press = 0.0
            }
            repaint()
        }

        private fun tick() {
            val animate = settings.animationsEnabled
            if (animate) {
                hover += (hoverTarget - hover) * 0.28
                press += (pressTarget - press) * 0.35
            } else {
                hover = 0.0
                press = 0.0
            }
            repaint()
        }

        /** 额度变化浮字：右上角小胶囊，展示 4.5 秒后淡出 */
        private fun drawQuotaDelta(g2: Graphics2D) {
            val text = deltaText ?: return
            val elapsed = System.currentTimeMillis() - deltaAt
            if (elapsed > DELTA_SHOW_MS) {
                deltaText = null
                return
            }
            val fadeStart = DELTA_SHOW_MS - 800
            val alpha = if (elapsed <= fadeStart) 1.0 else (1.0 - (elapsed - fadeStart) / 800.0).coerceIn(0.0, 1.0)

            g2.font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(15))
            val fm = g2.fontMetrics
            val padH = JBUI.scale(8)
            val padV = JBUI.scale(4)
            val textW = fm.stringWidth(text)
            val plateW = textW + padH * 2
            val plateH = fm.height + padV * 2

            // 浮字贴在角色正上方，而不是窗口右上角（窗口比角色大，贴着窗口会显得很远）
            val animation = animator.currentAnimation
            val bounds = PetAnimationLibrary.bounds
            val charRight: Int
            val charTop: Int
            if (animation != null && bounds.width > 0) {
                val scale = width.toDouble() / bounds.width
                charRight = ((animation.offsetX - bounds.x + animation.width) * scale).toInt()
                charTop = ((animation.offsetY - bounds.y) * scale).toInt()
            } else {
                charRight = width
                charTop = 0
            }
            val x = (charRight - plateW).coerceIn(0, (width - plateW).coerceAtLeast(0))
            val y = (charTop - plateH - JBUI.scale(2)).coerceAtLeast(0)

            val a = (255 * alpha).toInt().coerceIn(0, 255)
            val plate = AccountUiColors.cardBg
            g2.color = java.awt.Color(plate.red, plate.green, plate.blue, (a * 0.92).toInt())
            g2.fillRoundRect(x, y, plateW, plateH, JBUI.scale(6), JBUI.scale(6))
            val accent = if (text.startsWith("+")) AccountUiColors.brassInk else AccountUiColors.seal
            g2.color = java.awt.Color(accent.red, accent.green, accent.blue, a)
            g2.drawString(text, x + padH, y + padV + fm.ascent)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )

                val animation = animator.currentAnimation
                val frame = animator.currentFrame
                val bounds = PetAnimationLibrary.bounds

                if (animation != null && frame != null && bounds.width > 0) {
                    // 原版素材：按并集区域等比缩放，逐帧对齐各自裁剪偏移
                    val scale = width.toDouble() / bounds.width
                    val dx = (animation.offsetX - bounds.x) * scale
                    val dy = (animation.offsetY - bounds.y) * scale
                    val dw = animation.width * scale
                    val dh = animation.height * scale

                    if (press > 0.001) {
                        val cx = dx + dw / 2
                        val cy = dy + dh / 2
                        val s = 1.0 - press * 0.03
                        val old = g2.transform
                        g2.transform(
                            java.awt.geom.AffineTransform.getTranslateInstance(cx, cy)
                                .apply { scale(s, s) }
                                .apply { translate(-cx, -cy) },
                        )
                        g2.drawImage(frame, dx.toInt(), dy.toInt(), dw.toInt(), dh.toInt(), null)
                        g2.transform = old
                    } else {
                        g2.drawImage(frame, dx.toInt(), dy.toInt(), dw.toInt(), dh.toInt(), null)
                    }
                } else {
                    // 素材缺失时的兜底：自绘档案柜精灵
                    PetPainter.paint(
                        g2 = g2,
                        width = width,
                        height = height,
                        data = data,
                        hover = hover,
                        press = press,
                        blink = 0.0,
                        lift = 0.0,
                        badge = settings.showQuotaBadge,
                    )
                }

                drawQuotaDelta(g2)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** 悬停 / 按压缓动的刷新间隔（与素材帧率无关） */
        private const val PRESS_FRAME_MS = 33

        /** 拖拽判定阈值（像素） */
        private const val DRAG_THRESHOLD = 4

        /** 四角吸附时与屏幕可用区域的距离（dp） */
        private const val CORNER_MARGIN_X = 24
        private const val CORNER_MARGIN_Y = 12

        // ---- 物理参数：与 dsh-pet 默认 config 一致 ----
        private const val PHYSICS_FRAME_MS = 16

        /** 重力（px/s²，100% 尺寸下） */
        private const val GRAVITY = 1400.0

        /** 墙面 / 地面反弹系数 */
        private const val RESTITUTION = 0.78

        /** 落地水平摩擦（/s） */
        private const val GROUND_FRICTION = 2.5

        /** 抛出速度上限（px/s，100% 尺寸下） */
        private const val THROW_LIMIT = 3600.0

        /** 竖直速度低于该值即视为落地静止（px/s） */
        private const val BOUNCE_STOP = 60.0

        /** 甩出阈值（px/s，100% 尺寸下）：低于它只算挪位置，不触发抛出 */
        private const val THROW_MIN_SPEED = 600.0

        /** 走位距离范围（px，100% 尺寸下，对应原项目 moves 的 minDist / maxDist） */
        private const val MIN_MOVE_DIST = 60.0
        private const val MAX_MOVE_DIST = 240.0

        /** 走位时间轴：起步与收尾原地停留 */
        private const val MOVE_LEAD_MS = 1_500L
        private const val MOVE_TAIL_MS = 1_500L

        /** 额度变化浮字的展示时长 */
        private const val DELTA_SHOW_MS = 4_500L
    }
}
