package dev.codebuddy.account.pet

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files



/**
 * 确认通道的可用性判定与自动让位。
 *
 * 两条通道：桌宠弹窗（HOOK，宿主执行 hook 后回调本插件）与页面代点（PAGE，注入脚本点按钮）。
 * 主人选定的通道失效时，另一条可用的顶上，避免「设置开着却什么都不发生」。
 *
 * HOOK 是否真的生效没法从配置看出来，只能看宿主的实际行为（实测，非公开契约）：
 * - 宿主每次工具调用都打 `[HookExecutor] Got N hook config(s) for event PreToolUse`，
 *   本机长期是 N=0，即 `settings.json` 里的 `PreToolUse` 没被当成 hook 配置读进去；
 * - hook 脚本每次被调用都会写 `hook-events.jsonl`，文件长时间不更新也是没被调用的证据。
 *
 * 两个方向都留了门槛，避免启动期来回抖动：
 * - HOOK → PAGE 只在拿到上面两条证据后发生；
 * - PAGE → HOOK 需要页面连续 [PAGE_GRACE_MS] 没有心跳，且 hook 历史上成功执行过。
 */
object ApprovalChannel {

    private val log = logger<ApprovalChannel>()

    /** 宿主不执行 hook 的结论；落盘后重启不必再等一轮日志 */
    private const val KEY_HOOK_BROKEN = "codebuddy.pet.hookBroken"

    /** hook 至少成功执行过一次；页面通道失效时才敢让位回去 */
    private const val KEY_HOOK_PROVEN = "codebuddy.pet.hookProven"

    /** 脚本近 10 分钟内有写入，说明宿主确实在执行 hook */
    private const val EVENT_FRESH_MS = 10 * 60_000L

    /** 页面通道连续这么久没有心跳，才允许让位给 hook，避免启动期来回切 */
    private const val PAGE_GRACE_MS = 60_000L

    /** 判定「宿主没读到配置」的最小间隔：每次工具调用都查文件太浪费 */
    private const val COUNT_CHECK_GAP_MS = 60_000L

    @Volatile
    private var hookBroken: Boolean = loadBoolean(KEY_HOOK_BROKEN)

    @Volatile
    private var hookProven: Boolean = loadBoolean(KEY_HOOK_PROVEN)

    /** 页面通道最后一次有心跳的时间（0 表示本会话还没连上过） */
    @Volatile
    private var lastPageReadyAt: Long = 0L

    @Volatile
    private var lastCountCheckAt: Long = 0L

    /** 宿主当前是否被判定为「不执行 hook」 */
    fun isHookBroken(): Boolean = hookBroken

    /**
     * 宿主报告的 PreToolUse hook 配置数量。
     *
     * [count] 大于 0 说明配置被读到了，直接判活；
     * 等于 0 有两种可能——宿主真没读到，或这次调用本来没匹配 matcher，
     * 所以还要满足「配置已安装」「脚本久未被调用」两条才敢判失效。
     */
    fun onPreToolUseConfigCount(count: Int) {
        if (count > 0) {
            markHookOk()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastCountCheckAt < COUNT_CHECK_GAP_MS) return
        lastCountCheckAt = now
        if (hookEventsFresh()) return
        if (!CodeBuddyHookInstaller.isInstalled()) return
        markHookBroken()
    }

    /**
     * 实际生效的通道。
     *
     * [CbApprovalMode.NONE] 原样返回（只提醒，不干预）；其余按可用性让位。
     */
    fun effective(settings: PetSettings): CbApprovalMode {
        val mode = settings.cbApprovalMode
        if (mode == CbApprovalMode.NONE) return mode
        val now = System.currentTimeMillis()
        val pageReady = CbWebObserver.isConnected()
        if (pageReady) lastPageReadyAt = now
        if (mode == CbApprovalMode.PAGE) {
            // 页面注入迟迟没有心跳时才有必要换通道，且必须证明 hook 真能干活
            val pageDead = !pageReady && lastPageReadyAt > 0 && now - lastPageReadyAt > PAGE_GRACE_MS
            return if (pageDead && hookProven && !hookBroken) CbApprovalMode.HOOK else CbApprovalMode.PAGE
        }
        // 桌宠弹窗通道：宿主不执行 hook 时让位页面代点；页面也没接上就只能继续等原生确认框
        return if (hookBroken && pageReady) CbApprovalMode.PAGE else CbApprovalMode.HOOK
    }

    /**
     * 配置刚被修复（或主人手动改过 `settings.json`）：清掉失效结论，
     * 让宿主下一次报告重新判定。若仍不生效，下一轮会再次判失效并提示。
     */
    fun resetHookBroken() {
        lastCountCheckAt = 0L
        if (!hookBroken) return
        hookBroken = false
        saveBoolean(KEY_HOOK_BROKEN, false)
    }

    /**
     * 主人拒绝改写宿主配置：直接把 hook 判为不可用，让页面代点接管。
     *
     * 与「判定失效后提示」区分开：拒绝是主人自己的选择，不该再弹一次报警。
     * 之后若手动把 hook 装好，宿主报出 `Got N>0` 时会自动恢复。
     */
    fun markHookUnavailable() {
        lastCountCheckAt = 0L
        if (hookBroken) return
        hookBroken = true
        saveBoolean(KEY_HOOK_BROKEN, true)
    }

    /** 给看板与设置页的一行说明；通道正常时返回 null */
    fun statusText(settings: PetSettings): String? = when {
        settings.cbApprovalMode != CbApprovalMode.HOOK -> null
        // 主人拒绝了改写宿主配置：如实说是没启用，而不是「坏了」
        settings.cbHookConsent == CbHookConsent.DECLINED && !CodeBuddyHookInstaller.isInstalled() ->
            if (CbWebObserver.isConnected()) {
                "未启用桌宠弹窗（没有改写 CodeBuddy 配置），确认由页面代点处理"
            } else {
                "未启用桌宠弹窗（没有改写 CodeBuddy 配置），确认只能手动点"
            }
        !hookBroken -> null
        CbWebObserver.isConnected() -> "宿主未执行 hook，桌宠弹窗不可用，已改用页面代点"
        else -> "宿主未执行 hook，桌宠弹窗不可用；页面代点也没连上，确认只能手动点"
    }

    // ------------------------------------------------------------------

    private fun markHookOk() {
        if (!hookProven) {
            hookProven = true
            saveBoolean(KEY_HOOK_PROVEN, true)
        }
        if (!hookBroken) return
        hookBroken = false
        saveBoolean(KEY_HOOK_BROKEN, false)
        log.info("宿主已执行 hook，确认通道恢复为桌宠弹窗")
        PetNotifier.notify(
            PetNotifier.Level.INFO,
            "桌宠弹窗通道恢复了",
            "宿主开始执行 hook，之后的确认改由桌宠气泡处理",
        )
    }

    private fun markHookBroken() {
        if (hookBroken) return
        hookBroken = true
        saveBoolean(KEY_HOOK_BROKEN, true)
        log.info("宿主未执行 PreToolUse hook，确认通道让位到页面代点")
        PetNotifier.notify(
            PetNotifier.Level.WARN,
            "桌宠弹窗通道没生效",
            "宿主没有加载 hook 配置，已自动改用页面代点；凭证 / 扣费仍要主人自己点",
        )
    }

    /** hook 脚本近 [EVENT_FRESH_MS] 内被调用过 */
    private fun hookEventsFresh(): Boolean = try {
        val file = CodeBuddyHookInstaller.eventsLog()
        Files.exists(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < EVENT_FRESH_MS
    } catch (_: Exception) {
        false
    }

    private fun loadBoolean(key: String): Boolean = try {
        PropertiesComponent.getInstance().getBoolean(key, false)
    } catch (_: Throwable) {
        false
    }

    private fun saveBoolean(key: String, value: Boolean) {
        try {
            PropertiesComponent.getInstance().setValue(key, value)
        } catch (_: Throwable) {
            // 落盘失败只影响下次启动的初判，不影响本次
        }
    }
}
