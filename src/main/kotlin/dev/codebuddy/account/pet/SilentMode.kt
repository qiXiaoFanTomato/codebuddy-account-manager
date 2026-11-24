package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 静默运行状态机：开启后所有确认直接放行（含红线，前提是主人显式勾选）。
 *
 * ## 为什么不是一个简单的布尔开关
 *
 * 「全部放行」等于把 `rm -rf /` 的最终否决权交给模型。因此这里强制四道约束，
 * 缺任何一条都可能把一次误开变成事故：
 *
 *  1. **时间盒**：默认 30 分钟，上限 8 小时，到点自动关闭并提醒；
 *  2. **重启失效**：开的时候把本次进程的 [bootToken] 写进设置，重启后令牌对不上就自动失效，
 *     避免「昨天试了一下，今天还在静默」；
 *  3. **红线可保留**：默认仍拦凭证与扣费，要放开必须单独勾选；
 *  4. **可审计 + 可感知**：放行逐条落 [PetAuditLog]（`via=silent`），
 *     每 [WARN_EVERY] 次弹一次警告，桌面状态栏常驻朱红标识。
 *
 * 到期检查用后台单线程轮询（20 秒一次），不依赖 EDT，也不会因为 IDE 卡顿而漏判。
 */
internal object SilentMode {

    private val log = logger<SilentMode>()

    /**
     * 本次 IDE 进程的启动令牌。
     *
     * 持久化在设置里，重启后新令牌与旧值不等 → 静默自动失效。
     * 多开 IDE 时每个实例令牌不同，互不影响（后开的不会继承前一个的静默）。
     */
    private val bootToken: String = UUID.randomUUID().toString().take(8)

    /** 开启期间累计放行次数，供状态栏与警告节流使用 */
    private val approvedCount = AtomicLong(0)

    /** 下一次该提醒的放行次数（每 [WARN_EVERY] 次提醒一次） */
    private val nextWarnAt = AtomicLong(WARN_EVERY.toLong())

    @Volatile
    private var watcher: ScheduledExecutorService? = null

    /**
     * 静默当前是否生效。
     *
     * 三个条件缺一不可：开关写过到期时间、令牌属于本次进程、还没到点。
     */
    fun isActive(settings: PetSettings): Boolean {
        if (settings.cbSilentUntil <= 0L) return false
        if (settings.cbSilentBoot != bootToken) return false
        return System.currentTimeMillis() < settings.cbSilentUntil
    }

    /** 剩余毫秒；未开启返回 0 */
    fun remainingMillis(settings: PetSettings): Long {
        if (!isActive(settings)) return 0L
        return (settings.cbSilentUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 开启静默；[minutes] 会收敛到 [MIN_MINUTES]~[MAX_MINUTES] */
    fun enable(settings: PetSettings, minutes: Int, allowRedline: Boolean) {
        val span = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        settings.cbSilentUntil = System.currentTimeMillis() + span * 60_000L
        settings.cbSilentBoot = bootToken
        settings.cbSilentAllowRedline = allowRedline
        approvedCount.set(0L)
        nextWarnAt.set(WARN_EVERY.toLong())
        startWatch()
        log.info("静默运行已开启 ${span} 分钟，红线${if (allowRedline) "放行" else "仍人工"}")
        PetNotifier.notify(
            PetNotifier.Level.WARN,
            "静默运行已开启（${span} 分钟）",
            if (allowRedline) {
                "所有确认直接放行，包含凭证与扣费。到期自动关闭，重启 IDE 也会关闭。"
            } else {
                "所有确认直接放行，凭证与扣费仍然要你亲手点。到期自动关闭，重启 IDE 也会关闭。"
            },
            durationMs = 10_000,
        )
    }

    /**
     * 关闭静默。
     *
     * [reason] 为空表示主人主动关闭；非空是到期自动关闭，两种情况提示语气不同。
     */
    fun disable(settings: PetSettings, reason: String? = null) {
        val wasActive = isActive(settings)
        settings.cbSilentUntil = 0L
        settings.cbSilentBoot = ""
        if (!wasActive) return
        val count = approvedCount.getAndSet(0L)
        log.info("静默运行已关闭（${reason ?: "主人关闭"}），期间放行 $count 次")
        PetNotifier.notify(
            PetNotifier.Level.INFO,
            "静默运行已关闭",
            buildString {
                append(reason ?: "已恢复逐条确认")
                if (count > 0) append("；本次共自动放行 $count 次，明细见审计日志")
            },
            durationMs = 8_000,
        )
    }

    /** 一次放行计数；到点提醒主人「现在处于静默」 */
    fun noteApproval(redline: Boolean) {
        val n = approvedCount.incrementAndGet()
        if (n < nextWarnAt.get()) return
        nextWarnAt.addAndGet(WARN_EVERY.toLong())
        PetNotifier.notify(
            PetNotifier.Level.WARN,
            "静默运行中，已自动放行 $n 次",
            if (redline) "包含凭证 / 扣费类调用，请确认这是你想要的。" else "状态栏有朱红标识，可随时关闭。",
            durationMs = 8_000,
        )
    }

    /** 本次静默期间已放行次数 */
    fun approvedCount(): Long = approvedCount.get()

    /**
     * 状态栏 / 设置页用的一行说明；未开启返回 null。
     *
     * 文案固定前缀「静默运行中」，状态栏据此显示朱红标识（颜色 + 文案双通道）。
     */
    fun describe(settings: PetSettings): String? {
        if (!isActive(settings)) return null
        val left = remainingMillis(settings)
        val minutes = (left / 60_000L).coerceAtLeast(0L)
        val redline = if (settings.cbSilentAllowRedline) "含红线" else "红线仍人工"
        return "静默运行中 · 剩 ${minutes} 分钟 · $redline"
    }

    /**
     * 启动到期检查（幂等）。
     *
     * 由 PetService 启动时调用一次即可；20 秒一轮，代价可以忽略。
     */
    @Synchronized
    fun startWatch() {
        if (watcher != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-pet-silent").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            {
                try {
                    val settings = PetSettings.getInstance()
                    // 令牌对不上（重启后的旧值）也顺手清掉，避免设置文件里留个过期时间戳
                    if (settings.cbSilentUntil > 0L && !isActive(settings)) {
                        if (settings.cbSilentBoot != bootToken) {
                            settings.cbSilentUntil = 0L
                            settings.cbSilentBoot = ""
                        } else {
                            disable(settings, "已到设定时间，自动恢复逐条确认")
                        }
                    }
                } catch (e: Exception) {
                    log.info("静默到期检查失败: ${e.message}")
                }
            },
            CHECK_SECONDS,
            CHECK_SECONDS,
            TimeUnit.SECONDS,
        )
        watcher = executor
    }

    /** 时间盒下限：1 分钟（够跑完一轮脚本） */
    const val MIN_MINUTES = 1

    /** 时间盒上限：8 小时（一个工作日，再长就该重新确认一次） */
    const val MAX_MINUTES = 480

    /** 默认时长：30 分钟 */
    const val DEFAULT_MINUTES = 30

    /** 每这么多次放行提醒一次 */
    private const val WARN_EVERY = 20

    /** 到期检查间隔（秒） */
    private const val CHECK_SECONDS = 20L
}
