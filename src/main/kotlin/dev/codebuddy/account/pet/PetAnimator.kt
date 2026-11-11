package dev.codebuddy.account.pet

import java.awt.image.BufferedImage
import javax.swing.Timer

/**
 * 桌宠状态机：按原项目 `config.jsonc` 的动画池驱动帧序列。
 *
 * 状态与转移：
 * - 待机：`idle` 循环
 * - 点击：随机 `click1..5` 播一次 → 回待机
 * - 余额：按已用百分比切档 `balance0..5` 播一次 → 回待机（仅在档位变化时触发）
 * - 睡觉：`sleep_start` → `sleep_loop` 循环；叫醒走 `sleep_end` → 回待机
 *
 * 定时器按当前动画自身的 fps 走（原素材 25fps），不做插值补帧。
 */
class PetAnimator(private val onFrame: () -> Unit) {

    private class Step(val name: String, val loop: Boolean)

    private val steps = ArrayDeque<Step>()

    private var current: PetAnimation? = null
    private var frameIndex = 0
    private var loop = true
    private var timer: Timer? = null

    private var animationsEnabled = true
    private var tierInitialized = false

    /** 是否睡梦中（睡梦中点击改为叫醒） */
    var isSleeping: Boolean = false
        private set

    /** 是否正被鼠标抓起悬空 */
    var isDragging: Boolean = false
        private set

    /** 当前要绘制的帧；素材缺失时为 null，由调用方退回自绘形象 */
    val currentFrame: BufferedImage? get() = current?.frame(frameIndex)

    /** 当前动画在 462×260 原画框中的位置，供渲染层对齐 */
    val currentAnimation: PetAnimation? get() = current

    fun start() {
        if (current == null && steps.isEmpty()) {
            play(listOf(Step(PetAnimationLibrary.IDLE, true)))
        }
        applyEnabled()
    }

    fun dispose() {
        timer?.stop()
        timer = null
    }

    /** 设置里的「待机动画」开关：关闭后停在当前帧，不再推进 */
    fun setAnimationsEnabled(enabled: Boolean) {
        animationsEnabled = enabled
        applyEnabled()
    }

    /** 是否正处在「非待机」动画里（此时不打断，随机动作顺延） */
    val isBusy: Boolean
        get() = current?.name != PetAnimationLibrary.IDLE

    /**
     * 随机小动作：按分类权重从素材清单里抽一个播一次再回待机。
     *
     * @return false 表示当时不方便（睡觉 / 被抓起 / 正在演别的），调用方可以稍后再试
     */
    fun playRandomAction(): Boolean {
        if (isSleeping || isDragging || isBusy) return false
        val id = PetAnimationLibrary.randomActionId() ?: return false
        play(listOf(Step(id, false), Step(PetAnimationLibrary.IDLE, true)))
        return true
    }

    /**
     * 系统提示的配套动作：按等级播一次对应动画（成功 / 信息 / 警告 / 错误）。
     *
     * 睡梦中先叫醒再播，被鼠标抓起时不打断拖拽。
     */
    fun playNotice(level: PetNotifier.Level) {
        if (isDragging) return
        if (isSleeping) wakeUp()
        val id = PetAnimationLibrary.noticeAnimation(level) ?: return
        play(listOf(Step(id, false), Step(PetAnimationLibrary.IDLE, true)))
    }

    /** 额度减少时的反应动作：在「心虚 / 心疼 / 装没事」组里轮转，避免每次都一样 */
    fun playQuotaDownReaction() {
        if (isSleeping || isDragging || isBusy) return
        val id = PetAnimationLibrary.quotaDownReactionId() ?: return
        play(listOf(Step(id, false), Step(PetAnimationLibrary.IDLE, true)))
    }

    /** 跑动 / 走位：播一段移动动画，播完回待机（窗口位移由 [PetWindow] 负责） */
    fun playMove(id: String) {
        if (isSleeping || isDragging) return
        play(listOf(Step(id, false), Step(PetAnimationLibrary.IDLE, true)))
    }

    /** 碎碎念：随机挑一段「碎碎念」动画播一次 */
    fun playWhisper(): Boolean {
        if (isSleeping || isDragging) return false
        val id = PetAnimationLibrary.whisperIds.randomOrNull() ?: return false
        play(listOf(Step(id, false), Step(PetAnimationLibrary.IDLE, true)))
        return true
    }

    /** 单击桌宠 */
    fun onClick() {
        if (isDragging) return
        if (isSleeping) {
            wakeUp()
            return
        }
        val click = PetAnimationLibrary.CLICKS.random()
        play(listOf(Step(click, false), Step(PetAnimationLibrary.IDLE, true)))
    }

    /** 被鼠标抓起：循环「悬空」姿势（原项目 `drag` 池） */
    fun beginDrag() {
        if (isDragging) return
        isDragging = true
        play(listOf(Step(PetAnimationLibrary.DRAG, true)))
    }

    /** 松开（落地或被放下）：回到待机 */
    fun endDrag() {
        if (!isDragging) return
        isDragging = false
        play(listOf(Step(PetAnimationLibrary.IDLE, true)))
    }

    /** 余额档位变化（0..5；-1 表示无数据/查询失败） */
    fun updateBalanceTier(tier: Int) {
        if (!tierInitialized) {
            // 首帧只记录档位，不在启动时插入一段 10 秒的余额动画
            tierInitialized = true
            lastTier = tier
            return
        }
        if (tier == lastTier) return
        lastTier = tier
        if (tier !in 0..5) return
        if (isSleeping || isDragging) return
        val name = PetAnimationLibrary.BALANCE.getOrNull(tier) ?: return
        play(listOf(Step(name, false), Step(PetAnimationLibrary.IDLE, true)))
    }

    /** 额度刷新后按当前档位重播一次余额动画（联动动作），不受「档位未变」限制 */
    fun replayBalance(tier: Int) {
        if (tier !in 0..5 || isSleeping || isDragging) return
        val name = PetAnimationLibrary.BALANCE.getOrNull(tier) ?: return
        // 同一档位已经在播就不再打断，避免一次刷新触发两遍
        if (current?.name == name) return
        play(listOf(Step(name, false), Step(PetAnimationLibrary.IDLE, true)))
    }

    private var lastTier = -1

    /** 预解码任务是否在排队（避免每帧都往线程池塞任务） */
    @Volatile private var prefetchPending = false

    fun sleep() {
        if (isSleeping) return
        isSleeping = true
        play(
            listOf(
                Step(PetAnimationLibrary.SLEEP_START, false),
                Step(PetAnimationLibrary.SLEEP_LOOP, true),
            )
        )
    }

    fun wakeUp() {
        if (!isSleeping) return
        isSleeping = false
        play(
            listOf(
                Step(PetAnimationLibrary.SLEEP_END, false),
                Step(PetAnimationLibrary.IDLE, true),
            )
        )
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    private fun play(newSteps: List<Step>) {
        steps.clear()
        steps.addAll(newSteps)
        advance()
    }

    private fun advance() {
        val step = steps.removeFirstOrNull()
        if (step == null) {
            // 序列播完：兜底回待机
            if (current?.name != PetAnimationLibrary.IDLE) {
                advance()
            }
            return
        }
        val animation = PetAnimationLibrary.get(step.name)
        if (animation == null) {
            // 单个动画缺失时跳过，绝不阻塞状态机
            advance()
            return
        }
        current = animation
        frameIndex = 0
        loop = step.loop
        scheduleTimer(animation.fps)
        onFrame()
    }

    private fun scheduleTimer(fps: Int) {
        timer?.stop()
        timer = null
        if (!animationsEnabled) return
        val delay = (1000 / fps.coerceIn(1, 60)).coerceAtLeast(16)
        timer = Timer(delay) { tick() }.apply { start() }
    }

    private fun applyEnabled() {
        if (animationsEnabled) {
            if (timer == null && current != null) scheduleTimer(current?.fps ?: 25)
        } else {
            timer?.stop()
            timer = null
        }
        onFrame()
    }

    private fun tick() {
        val animation = current ?: return
        if (animation.frameCount <= 1) {
            onFrame()
            return
        }
        if (frameIndex + 1 < animation.frameCount) {
            frameIndex++
            onFrame()
            prefetchAhead()
        } else if (loop) {
            frameIndex = 0
            onFrame()
            prefetchAhead()
        } else {
            advance()
        }
    }

    /** 把后续几帧的解码挪到后台线程，EDT 只负责画 */
    private fun prefetchAhead() {
        val animation = current ?: return
        if (prefetchPending) return
        prefetchPending = true
        val base = frameIndex
        try {
            DECODER.execute {
                try {
                    animation.prefetch(base + 1, base + 2, base + 3)
                } finally {
                    prefetchPending = false
                }
            }
        } catch (_: Exception) {
            prefetchPending = false
        }
    }

    private companion object {
        /** 预解码线程：与动画状态机解耦，随插件进程退出 */
        val DECODER = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "codebuddy-pet-decode").apply { isDaemon = true }
        }
    }
}
