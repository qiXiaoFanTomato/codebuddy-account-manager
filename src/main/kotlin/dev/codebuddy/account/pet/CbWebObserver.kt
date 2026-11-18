package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.service.AccountSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap



/**
 * CodeBuddy 聊天面板（JCEF）里的常驻 DOM 观察器。
 *
 * 复刻 WorkDaddy 的思路：状态感知放在**渲染进程**里，用 MutationObserver + 定时兜底扫描
 * 直接看页面，不再只靠日志文案。日志监听（[CodeBuddyWatcher]）保留为兜底通道，
 * 两者在 [CbEventCenter] 汇合去重，任一路认出事件都能提示，修掉「日志格式一变就漏提示」。
 *
 * 事件回传不引入新依赖、不开本地端口：注入脚本把最近若干条事件写进 `window.name`，
 * Kotlin 侧定时读 `CefFrame.getName()`（与 [CodeBuddyApprover] 同一套反射），
 * 按 bootId + 递增序号去重；页面刷新 / 插件重启都能自动重新对齐。
 * 观察器本身也负责免打扰自动点击和中断后点「继续」，失败一律静默降级。
 */
object CbWebObserver {

    private val log = logger<CbWebObserver>()

    private const val PREFIX = "CBPET1|"
    private const val POLL_MS = 1_500L

    /** 快路径间隔：只读实时速率字段，不走组件树扫描、不注入脚本 */
    private const val FAST_POLL_MS = 500L

    private const val REINJECT_MS = 10_000L
    private const val CONNECTED_MS = 8_000L

    /** 报文里的实时速率段 `<chars>,<ms>|`；旧版脚本没有这一段，解析时直接跳过 */
    private val RATE_PREFIX = Regex("^(-?\\d+,\\d+)\\|")

    /** 页面会话状态的有效期：这么久没再上报就当作未接上 */
    private const val STORE_TTL_MS = 10 * 60_000L

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-web-watch").apply { isDaemon = true }
        }

    private var task: ScheduledFuture<*>? = null

    private var fastTask: ScheduledFuture<*>? = null

    /**
     * 快路径单独一个线程：完整扫描会在 EDT 上遍历组件树，混在同一个线程里
     * 会把实时读数卡成 1.5 秒一跳，正好抵消这次想要的效果。
     */
    private val fastExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-web-rate").apply { isDaemon = true }
        }

    private val running = AtomicBoolean(false)

    /**
     * 仅采样模式：状态栏要速度读数而桌宠没开时使用。
     *
     * 该模式下不判权限确认、不代点、不点「继续」，只读页面的实时速率字段，
     * 避免「桌宠关掉了却仍在替主人点同意」。
     */
    private val streamOnly = AtomicBoolean(false)

    /** 上一次完整扫描得到的面板（项目 + 浏览器）：快路径只读它们的 name，不再遍历组件树 */
    @Volatile
    private var ratePanels: List<CodeBuddyApprover.ProjectBrowser> = emptyList()

    /** CefBrowser -> 上次注入完整脚本的时间；弱引用避免持有已关闭的面板 */
    private val injected = WeakHashMap<Any, Long>()

    @Volatile
    private var lastHeartbeat = 0L

    @Volatile
    private var lastBoot = ""

    @Volatile
    private var lastSeq = 0L

    @Volatile
    private var lastConfig = ""

    /** 页面会话状态（React 会话控制器）最近一次接上的时刻；0 表示未接上 */
    @Volatile
    private var lastStoreAt = 0L

    /** 第一次成功投递脚本的时刻，用于区分「找不到面板」和「注入了但没回传」 */
    @Volatile
    private var firstInjectAt = 0L

    private var lastNoBeatLog = 0L

    /** 启动观察（幂等）。由 [PetService] 在桌宠窗口就绪后调用 */
    fun start() {
        streamOnly.set(false)
        startInternal()
    }

    /** 只采集实时速度样本（状态栏读数用）：不判确认、不代点、不点继续 */
    fun startStreamOnly() {
        if (running.get()) return
        streamOnly.set(true)
        startInternal()
    }

    /** 速度读数关闭时释放「仅采样」实例；桌宠的全量观察不受影响 */
    fun stopStreamOnly() {
        if (!streamOnly.get()) return
        stop()
    }

    private fun startInternal() {
        if (!running.compareAndSet(false, true)) return
        synchronized(injected) { injected.clear() }
        firstInjectAt = 0L
        lastNoBeatLog = 0L
        task = executor.scheduleWithFixedDelay(
            { tickQuietly() },
            2_000L,
            POLL_MS,
            TimeUnit.MILLISECONDS,
        )
        fastTask = fastExecutor.scheduleWithFixedDelay(
            { fastTickQuietly() },
            FAST_POLL_MS,
            FAST_POLL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        running.set(false)
        streamOnly.set(false)
        task?.cancel(false)
        task = null
        fastTask?.cancel(false)
        fastTask = null
        ratePanels = emptyList()
        synchronized(injected) { injected.clear() }
        lastHeartbeat = 0L
        lastStoreAt = 0L
        firstInjectAt = 0L
    }

    /** 注入通道是否有心跳（供设置页展示降级状态） */
    fun isConnected(): Boolean = System.currentTimeMillis() - lastHeartbeat < CONNECTED_MS

    /** 页面里的会话状态是否接上：接上后限流才有模型名与解封时间 */
    fun storeConnected(): Boolean = lastStoreAt > 0L && System.currentTimeMillis() - lastStoreAt < STORE_TTL_MS

    /** 日志通道判定「异常中断」后，请求页面点一次「继续 / 重试」 */
    fun clickContinue() {
        if (!PetSettings.getInstance().cbAutoContinueEnabled) return
        for (browser in CodeBuddyApprover.browsers()) {
            CodeBuddyApprover.inject(browser, CONTINUE_JS)
        }
    }

    // ---------------------------------------------------------------
    // 轮询
    // ---------------------------------------------------------------

    private fun tickQuietly() {
        try {
            tick()
        } catch (t: Throwable) {
            log.warn("CodeBuddy 页面观察异常", t)
        }
    }

    private fun tick() {
        if (!running.get()) return
        val settings = try {
            PetSettings.getInstance()
        } catch (_: Throwable) {
            return
        }
        // 仅采样模式（状态栏读数）不碰任何确认相关逻辑：桌宠没开时不能替主人点同意
        val onlyRate = streamOnly.get()
        // 「是否观察」「是否提示」「是否代点」三件事分开：
        // 关掉提醒不应关掉页面代点，关掉跑完提醒也不应关掉自动继续。
        // 处理方式不是「页面代点」时，页面只观察不点，避免和桌宠弹窗通道抢同一个确认。
        // 生效通道由 [ApprovalChannel] 统一判定：桌宠弹窗不可用时页面代点顶上。
        val pageActive = !onlyRate && ApprovalChannel.effective(settings) == CbApprovalMode.PAGE
        // 扣费确认提示是「权限提醒」之外的独立开关：单独开着时页面也要继续观察，
        // 否则关掉权限提醒后这条提示永远不会出现（脚本只能扫到不扫就发不出事件）
        val observeApproval = !onlyRate &&
            (settings.cbApproveNoticeEnabled || pageActive || settings.cbCreditNoticeEnabled)
        val observeInterrupt = !onlyRate &&
            (settings.cbProgressNoticeEnabled || settings.cbAutoContinueEnabled)
        val autoAllowNormal = pageActive && settings.cbApprovalNormal == CbNormalPolicy.ALLOW
        val cfg = JsonObject().apply {
            addProperty("observeApproval", observeApproval)
            addProperty("noDisturb", autoAllowNormal)
            // 危险档位只是普通档位的附加项，普通命令仍需确认时不得单独生效
            addProperty(
                "noDisturbDanger",
                autoAllowNormal && settings.cbApprovalDanger == CbDangerPolicy.AUTO,
            )
            addProperty("observeInterrupt", observeInterrupt)
            addProperty("autoContinue", settings.cbAutoContinueEnabled)
            // 仅采样模式只拿速度：限流提示属于桌宠通道，不能在桌宠关闭时被顺带打开
            addProperty("observeRateLimit", !onlyRate && settings.cbLimitNoticeEnabled)
            // 实时速度采样只跟状态栏速度开关走：主人没开读数就别在页面里统计字符
            addProperty(
                "observeStream",
                try {
                    AccountSettings.getInstance().showStatusBarThroughput
                } catch (_: Throwable) {
                    false
                },
            )
        }
        val configJs = "window.__cbPetCfg=$cfg;" +
            "if(window.__cbPet){window.__cbPet.cfg=window.__cbPetCfg;}"
        val configChanged = configJs != lastConfig
        lastConfig = configJs

        // 优先按项目归属取面板；CodeBuddy 改了 ToolWindow id 时退回带全树兜底的扫描，
        // 宁可速度样本归不到项目，也不能让代点与提示整体失效
        var panels = CodeBuddyApprover.projectBrowsers()
        if (panels.isEmpty()) {
            panels = CodeBuddyApprover.browsers().map {
                CodeBuddyApprover.ProjectBrowser("", "", it)
            }
        }
        ratePanels = panels
        pruneRateStates(panels)
        val now = System.currentTimeMillis()
        for (panel in panels) {
            val browser = panel.browser
            try {
                // 项目归属随面板注入：速率样本回传时靠它落到正确的项目上
                val where = "window.__cbPetCfg.project=${jsString(panel.project)};" +
                    "window.__cbPetCfg.workspace=${jsString(panel.workspace)};"
                val last = synchronized(injected) { injected[browser] }
                val needBoot = last == null || configChanged || now - last > REINJECT_MS ||
                    now - lastHeartbeat > CONNECTED_MS
                if (needBoot) {
                    CodeBuddyApprover.inject(browser, configJs + where + BOOT_JS)
                    synchronized(injected) { injected[browser] = now }
                    if (firstInjectAt == 0L) firstInjectAt = now
                } else {
                    CodeBuddyApprover.inject(browser, configJs + where)
                }
                readEvents(browser)
            } catch (_: Throwable) {
                // 单个面板不可用不影响其它面板
            }
        }
        // 观察功能开着却一个面板都找不到：留一条限频诊断，便于版本升级后定位承载位置
        if (panels.isEmpty() && observeApproval) CodeBuddyApprover.logMissingPanel()
        // 脚本投递成功但迟迟没有回传：注入没执行或回传通道读不到，打日志区分
        if (lastHeartbeat == 0L && firstInjectAt > 0L && now - firstInjectAt > 15_000L) {
            panels.firstOrNull()?.let { logNoHeartbeat(it.browser) }
        }
    }

    // ---------------------------------------------------------------
    // 快路径：实时输出速率
    // ---------------------------------------------------------------

    /** 每个面板的速率累计基线：页面报的是累计值，这里做差分，漏读一次也不会丢字符 */
    private class RateState(var boot: String, var chars: Long, var ms: Long)

    private val rateStates = HashMap<Any, RateState>()

    private fun fastTickQuietly() {
        try {
            fastTick()
        } catch (_: Throwable) {
            // 读数失败不影响主流程，下一拍再试
        }
    }

    /**
     * 只读面板的 `window.name` 并解析速率段，不注入脚本、不遍历组件树。
     *
     * 页面报的是**自本次脚本启动以来的累计字符数与累计毫秒数**，这里做差分：
     * 500ms 读一次、页面 400ms 结算一次，偶尔漏读一次也不会少算字符。
     */
    private fun fastTick() {
        if (!running.get()) return
        val panels = ratePanels
        if (panels.isEmpty()) return
        for (panel in panels) {
            val raw = try {
                CodeBuddyApprover.mainFrameName(panel.browser)
            } catch (_: Throwable) {
                continue
            }
            if (!raw.startsWith(PREFIX)) continue
            val parts = raw.split('|', limit = 4)
            if (parts.size < 4) continue
            val match = RATE_PREFIX.find(parts[3]) ?: continue
            val boot = parts[1]
            val rate = match.groupValues[1]
            if (rate.isEmpty() || rate == "-") continue
            val comma = rate.indexOf(',')
            if (comma <= 0) continue
            val totalChars = rate.substring(0, comma).toLongOrNull() ?: continue
            val totalMs = rate.substring(comma + 1).toLongOrNull() ?: continue
            val state = synchronized(rateStates) {
                val prev = rateStates[panel.browser]
                if (prev == null || prev.boot != boot) {
                    RateState(boot, totalChars, totalMs).also { rateStates[panel.browser] = it }
                } else {
                    prev
                }
            }
            // 第一次见到这个 boot 时没有基线，只记基线不算速度
            val deltaChars = (totalChars - state.chars).coerceAtLeast(0L)
            val deltaMs = totalMs - state.ms
            synchronized(rateStates) {
                state.chars = totalChars
                state.ms = totalMs
            }
            if (deltaMs <= 0L) continue
            CbEventCenter.onStreamChars(
                panel.workspace, panel.project, deltaChars.toInt(), deltaMs,
            )
        }
    }

    /** 面板可能随时被重建：完整 tick 时顺手把过期的累计基线清掉 */
    private fun pruneRateStates(live: List<CodeBuddyApprover.ProjectBrowser>) {
        synchronized(rateStates) {
            if (rateStates.size <= live.size) return
            val ids = java.util.IdentityHashMap<Any, Boolean>()
            for (p in live) ids[p.browser] = true
            rateStates.keys.removeIf { !ids.containsKey(it) }
        }
    }

    /** 注入后无心跳：把主 frame 当前 name 打出来，区分「脚本没跑」和「name 读不到」 */
    private fun logNoHeartbeat(cef: Any) {
        val now = System.currentTimeMillis()
        if (now - lastNoBeatLog < 60_000L) return
        lastNoBeatLog = now
        val raw = try {
            CodeBuddyApprover.mainFrameName(cef)
        } catch (_: Throwable) {
            "读取失败"
        }
        log.warn("页面观察通道未连接：脚本已注入但无心跳，主 frame name=${raw.take(120).ifBlank { "(空)" }}")
    }

    /** 读取 `window.name` 里的事件队列；按 bootId + seq 去重后交给事件中心 */
    private fun readEvents(cef: Any) {
        val raw = CodeBuddyApprover.mainFrameName(cef)
        if (!raw.startsWith(PREFIX)) return
        val parts = raw.split('|', limit = 4)
        if (parts.size < 4) return
        val boot = parts[1]
        lastHeartbeat = System.currentTimeMillis()
        if (boot != lastBoot) {
            lastBoot = boot
            lastSeq = 0L
        }
        val array = try {
            JsonParser.parseString(jsonBody(parts[3])).takeIf { it.isJsonArray }?.asJsonArray ?: return
        } catch (_: Throwable) {
            return
        }
        for (el in array) {
            if (!el.isJsonObject) continue
            val item = el.asJsonObject
            val seq = try {
                item.get("s")?.asLong ?: continue
            } catch (_: Throwable) {
                continue
            }
            if (seq <= lastSeq) continue
            lastSeq = seq
            val event = item.get("e")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            // 页面会话状态是否接上：接上后限流与模型名才有页面来源，供看板与排障展示
            val type = try {
                event.get("t")?.takeIf { it.isJsonPrimitive }?.asString
            } catch (_: Throwable) {
                null
            }
            if (type == "diag") {
                val store = try {
                    event.get("store")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                } catch (_: Throwable) {
                    0
                }
                lastStoreAt = if (store > 0) System.currentTimeMillis() else 0L
                continue
            }
            CbEventCenter.onWebEvent(event.toString())
        }
    }

    /** 报文尾段是 `rate|json`：取 json 部分；旧版报文没有速率段时原样返回 */
    private fun jsonBody(rest: String): String {
        val match = RATE_PREFIX.find(rest) ?: return rest
        return rest.substring(match.range.last + 1)
    }

    /** 项目名写进 JS 字面量：只转义反斜杠与单引号，换行压成空格 */
    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", " ")
            .replace("\n", " ")
        return "'$escaped'"
    }

    // ---------------------------------------------------------------
    // 注入脚本
    // ---------------------------------------------------------------

    /** 一次性点「继续 / 重试」：日志通道判定异常中断后的补刀 */
    private val CONTINUE_JS: String = """
        (function () {
          try {
            var label = /^(继续|重试|重新生成|Continue|Retry|Resume)${'$'}/i;
            var nodes = document.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]');
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              if (!el || el.disabled || el.getAttribute('data-cb-pet-cont')) continue;
              var t = String(el.textContent || '').replace(/\s+/g, ' ').trim().replace(/^\d+\s*/, '');
              if (!label.test(t)) continue;
              if (el.getClientRects && !el.getClientRects().length) continue;
              el.setAttribute('data-cb-pet-cont', '1');
              el.click();
              return 'CLICKED';
            }
            return 'NO_BUTTON';
          } catch (e) { return 'ERROR'; }
        })();
    """.trimIndent()

    /**
     * 常驻观察脚本。对齐 WorkDaddy 的 `startNoDisturbAutoApprove` / `scanNoDisturbApproval`：
     *  - 文案白名单 + 决策组（允许 / 运行 + 拒绝 / 跳过）双通道判定；
     *  - 扣费、提权、批量删除、凭证外发一律不自动点；
     *  - 「危险命令」默认只提示不代点，除非主人显式打开高风险开关；
     *  - MutationObserver 防抖 120ms + 2 秒定时兜底扫描，页面重渲染不会漏。
     */
    private val BOOT_JS: String = """
        (function () {
          var W = window;
          // 带版本号：插件升级后页面里还留着的旧脚本（没有 ver）必须被这份覆盖，
          // 否则 flush 不带速率段，实时读数永远出不来。旧脚本的定时器会继续跑，
          // 但它写的报文没有速率段，上层按「读不到速率」处理，不会算错；
          // 两边事件重复的部分由 [CbEventCenter] 的去重表挡住，页面刷新后只剩新脚本。
          if (W.__cbPet && W.__cbPet.alive && W.__cbPet.ver === 3) {
            W.__cbPet.cfg = W.__cbPetCfg || {};
            return;
          }
          var boot = String(Date.now()) + '-' + Math.random().toString(36).slice(2, 8);
          var seq = 0, queue = [], cfg = W.__cbPetCfg || {}, pending = 0;
          var PREFIX = 'CBPET1|';
          var api = { alive: true, cfg: cfg, ver: 3 };
          W.__cbPet = api;

          var APPROVE_ONCE = /^(允许|允许一次|允许访问|运行|确认执行|批准|同意|确认允许|Allow|Allow once|Run|Yes)${'$'}/i;
          var SESSION = /始终允许|本次会话内始终允许|Always\s*allow/i;
          var DENY = /拒绝|跳过|取消|禁止|Deny|Skip|Cancel/i;
          // 判定闸门（对齐 WorkDaddy 的 ND_CREDIT_HINTS / ND_MAX_DECISION_BUTTONS）：
          //  - CREDIT 命中整卡跳过：积分/扣费卡（图片、视频生成）不是待确认项，不代点也不提示
          //  - BLOCK 命中不代点但必须提示：这类卡点了就等于放开权限
          var BLOCK = /完全访问|全部允许|批量删除|凭证外发|发送到外部|确认开启/i;
          var CREDIT = /积分|信用|credit|消耗|付费|支付|费用|金额|余额|扣费/i;
          var MAX_DECISION_BUTTONS = 12;
          var DANGER = /危险命令|Dangerous command|受保护|敏感|越权|越界|沙箱|sandbox|提权|删除|密钥|凭据|凭证/i;
          var CONFIRM = /确认|允许|运行|是否|需要你|需要您的|permission|approval|sandbox|检测到|受保护|敏感/i;
          var RESET = /(?:Reset at|预计解封|预计恢复|解封时间|恢复时间|resetAt)[^0-9]{0,12}(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})/i;
          var BREAK = /网络异常|请求超时|连接中断|生成中断|已中断|服务异常|请求失败|响应为空|响应中断|aborted|timeout|network error|interrupted/i;
          var MANUAL_STOP = /已停止|已取消|用户取消|手动停止|manual stop|cancelled by user/i;
          var CONTINUE = /^(继续|重试|重新生成|Continue|Retry|Resume)${'$'}/i;

          function docs() {
            var out = [document];
            try {
              var frames = document.querySelectorAll('iframe');
              for (var i = 0; i < frames.length && out.length < 4; i++) {
                var d = frames[i].contentDocument;
                if (d && d.body) out.push(d);
              }
            } catch (e) {}
            return out;
          }
          function txt(el) {
            return String((el && (el.textContent || el.innerText)) || '').replace(/\s+/g, ' ').trim();
          }
          function visible(el) {
            try {
              if (!el || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
              if (el.getClientRects && !el.getClientRects().length) return false;
              return true;
            } catch (e) { return false; }
          }
          function norm(t) { return String(t || '').replace(/^\d+\s*/, '').trim(); }
          function emit(t, key, payload) {
            payload.t = t;
            payload.k = key;
            payload.project = String((W.__cbPetCfg && W.__cbPetCfg.project) || document.title || '').slice(0, 60);
            // workspace 随面板注入：提示里按它取对话标题，比拿项目名猜准
            payload.ws = String((W.__cbPetCfg && W.__cbPetCfg.workspace) || '');
            queue.push({ s: ++seq, e: payload });
            if (queue.length > 8) queue.splice(0, queue.length - 8);
            flush();
          }
          function flush() {
            try {
              // 第 4 段是实时速率 `累计字符,累计毫秒`：旧版脚本没有这一段，
              // Kotlin 侧按 `数字,数字|` 前缀判断，读不到就当没有速率
              W.name = PREFIX + boot + '|' + Date.now() + '|' + rate.out + '|' + JSON.stringify(queue);
            } catch (e) {}
          }

          // ---------------- 实时输出速度采样 ----------------
          // 只统计聊天区「新增字符」。单次增量上限用来挡掉 React 整段重渲染——
          // 否则每次重渲染都会把整段正文重新数一遍，速度会翻好几倍。
          var rate = { at: Date.now(), chars: 0, totalChars: 0, totalMs: 0, out: '-' };
          var RATE_MAX_DELTA = 500;
          function rateNoise(el) {
            for (var d = 0; el && d < 8; d++) {
              if (!el.tagName) break;
              var tag = el.tagName.toLowerCase();
              if (tag === 'button' || tag === 'textarea' || tag === 'input' || tag === 'select' ||
                  tag === 'svg' || tag === 'style' || tag === 'script' || tag === 'noscript') return true;
              if (el.getAttribute) {
                if (el.getAttribute('role') === 'button') return true;
                if (el.getAttribute('contenteditable') === 'true') return true;
                if (el.getAttribute('aria-hidden') === 'true') return true;
              }
              var cls = (el.className ? String(el.className) : '') + ' ' + (el.id || '');
              if (/(toolbar|status-?bar|footer|header|toast|spinner|loading|skeleton|caret|cursor|blink)/i.test(cls)) return true;
              el = el.parentElement;
            }
            return false;
          }
          function rateAdd(n) {
            if (!(n > 0) || n > RATE_MAX_DELTA) return;
            rate.chars += n;
          }
          function rateWatch(doc) {
            if (!doc || !doc.body || doc.__cbPetRate) return;
            try {
              doc.__cbPetRate = 1;
              var mo = new MutationObserver(function (recs) {
                for (var i = 0; i < recs.length; i++) {
                  var r = recs[i];
                  if (rateNoise(r.target)) continue;
                  if (r.type === 'characterData') {
                    var oldLen = r.oldValue ? r.oldValue.length : 0;
                    var newLen = r.target && r.target.nodeValue ? r.target.nodeValue.length : 0;
                    rateAdd(newLen - oldLen);
                  } else if (r.type === 'childList') {
                    for (var j = 0; j < r.addedNodes.length; j++) {
                      var node = r.addedNodes[j];
                      if (node.nodeType === 3) {
                        rateAdd(node.nodeValue ? node.nodeValue.length : 0);
                      } else if (node.nodeType === 1 && !rateNoise(node)) {
                        var txt = node.textContent;
                        if (txt) rateAdd(txt.length);
                      }
                    }
                  }
                }
              });
              mo.observe(doc.body, {
                childList: true, subtree: true, characterData: true, characterDataOldValue: true
              });
            } catch (e) {}
          }
          // 累计值上报：上层做差分，偶尔漏读一次也不会少算字符
          function rateSettle() {
            var now = Date.now();
            var ms = now - rate.at;
            if (ms < 300) return;
            rate.at = now;
            if (rate.chars > 0) {
              rate.totalChars += rate.chars;
              rate.totalMs += ms;
              rate.out = rate.totalChars + ',' + rate.totalMs;
            }
            rate.chars = 0;
          }
          function buttons(doc) {
            return doc.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]');
          }
          function buttonsIn(box) {
            // 与 buttons() 同口径：确认卡不一定用原生 button，role=button 的自绘按钮同样要能识别
            return box && box.querySelectorAll
              ? box.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]')
              : [];
          }
          // 找「允许 + 拒绝」决策组。判定通道两类（与 WorkDaddy 的 keyword / structured 对应）：
          //  keyword    —— 卡片文案命中确认 / 危险关键词；
          //  structured —— 卡内既有拒绝选项、又有精确「允许一次」选项，不依赖文案也能认定是决策卡。
          function decisionBox(btn) {
            var box = btn;
            for (var depth = 0; depth < 6 && box && box.parentElement; depth++) {
              box = box.parentElement;
              var list = buttonsIn(box);
              // 逃出卡片就停：祖先容器（消息列表 / BODY）按钮数量必然远超卡片量级
              if (list.length > MAX_DECISION_BUTTONS) break;
              if (list.length < 2) continue;
              var ctx = txt(box).slice(0, 700);
              var hasDeny = DENY.test(ctx);
              if (!hasDeny && !CONFIRM.test(ctx)) continue;
              var hasOnce = false;
              for (var i = 0; i < list.length; i++) {
                if (visible(list[i]) && APPROVE_ONCE.test(norm(txt(list[i])))) { hasOnce = true; break; }
              }
              return {
                box: box, ctx: ctx, list: list, hasDeny: hasDeny,
                via: (hasDeny && hasOnce) ? 'structured' : 'keyword'
              };
            }
            return null;
          }
          // 目标按钮：优先「允许一次」；没有才回落到「本次会话内始终允许」（跟随 WorkDaddy）。
          // 回落只是候选，是否真的点由 scanApproval 结合「卡内有无拒绝选项」再判一次。
          function pickTarget(list) {
            var fallback = null;
            for (var i = 0; i < list.length; i++) {
              var el = list[i];
              if (!visible(el)) continue;
              var t = norm(txt(el));
              if (APPROVE_ONCE.test(t)) return { el: el, kind: 'once' };
              if (!fallback && SESSION.test(t)) fallback = { el: el, kind: 'session' };
            }
            return fallback;
          }
          function describe(ctx) {
            var hit = /(包含危险命令[^。？?]*[。？?]?|是否[^。？?]{0,40}[。？?]?|检测到[^。？?]{0,40})/.exec(ctx);
            return (hit ? hit[1] : ctx).slice(0, 70) || '一个操作需要确认';
          }
          // 扫描根收敛：MutationObserver 只把变化点所在的确认卡容器排队，
          // 快路径不遍历整页；队列为空时（2 秒兜底扫描）才退回整文档。
          var rootQueue = [];
          function queueRoot(node) {
            var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
            if (!el) return;
            var root = null;
            try {
              root = el.closest && el.closest('[role="dialog"],[class*="_dialog_"],[class*="_modal_"],' +
                '[class*="_permission_"],[class*="_approval_"],[class*="_confirm_"],[class*="_decision_"],' +
                '[class*="_ask_"],[class*="permission-dialog"],[class*="confirm-dialog"],' +
                '[class*="approval"],[class*="permission"]');
            } catch (e) {}
            if (!root) {
              root = el;
              for (var i = 0; i < 4 && root.parentElement; i++) root = root.parentElement;
            }
            for (var j = 0; j < rootQueue.length; j++) if (rootQueue[j] === root) return;
            rootQueue.push(root);
            if (rootQueue.length > 24) rootQueue.shift();
          }
          function sessionIdOf(el) {
            try {
              var owner = el && el.closest && el.closest('[data-conversation-id],[data-root-id]');
              return owner
                ? String(owner.getAttribute('data-conversation-id') || owner.getAttribute('data-root-id') || '')
                : '';
            } catch (e) { return ''; }
          }
          function scanApproval(doc) {
            var roots = [], kept = [], i, j, r, od;
            for (i = 0; i < rootQueue.length; i++) {
              r = rootQueue[i];
              od = null;
              try { od = r.ownerDocument; } catch (e) {}
              // 根可能来自另一个 iframe 文档，只在本轮对应文档里消费
              if (!od || od === doc) roots.push(r); else kept.push(r);
            }
            rootQueue = kept;
            if (!roots.length) roots = [doc.body];
            for (i = 0; i < roots.length; i++) {
              var list = buttonsIn(roots[i]);
              for (j = 0; j < list.length; j++) {
                var btn = list[j];
                if (!visible(btn)) continue;
                var label = norm(txt(btn));
                if (!APPROVE_ONCE.test(label) && !SESSION.test(label)) continue;
                var found = decisionBox(btn);
                if (!found || found.box.getAttribute('data-cb-pet-ask')) continue;
                var target = pickTarget(found.list);
                if (!target) continue;
                found.box.setAttribute('data-cb-pet-ask', '1');
                var credit = CREDIT.test(found.ctx);
                var danger = DANGER.test(found.ctx);
                var blocked = BLOCK.test(found.ctx);
                var base = {
                  what: describe(found.ctx), danger: danger, kind: target.kind,
                  via: found.via, label: txt(target.el).slice(0, 20),
                  credit: credit, sessionId: sessionIdOf(found.box)
                };
                // 扣费卡整卡跳过：不代点也不提示，只留一条审计（auto 恒为 false）
                if (credit) {
                  base.auto = false;
                  base.blocked = true;
                  emit('approval', 'credit|' + found.ctx.slice(0, 90), base);
                  continue;
                }
                var auto = false;
                var c = api.cfg || {};
                // 决策看整张确认卡：卡里同时有「允许」和「允许一次」时，后者才是 pickTarget 的结果
                if (c.noDisturb && !blocked) {
                  // 回落「本次会话内始终允许」= 放开整段会话权限：
                  // 只对普通卡开放（且卡内必须有拒绝选项）；危险卡一律只点单次，不放大到整段会话
                  var acceptable = target.kind === 'once' || (found.hasDeny && !danger);
                  if (acceptable && (!danger || c.noDisturbDanger)) auto = true;
                }
                base.auto = auto;
                base.blocked = blocked;
                emit('approval', 'ask|' + found.ctx.slice(0, 90), base);
                if (auto) {
                  target.el.setAttribute('data-cb-pet-auto', '1');
                  try { target.el.click(); } catch (e) {}
                }
              }
            }
          }
          function resetMillis(text) {
            var m = RESET.exec(text);
            var raw = m ? m[1] : null;
            if (!raw) {
              // 官方错误详情里的日期不一定带「Reset at」前缀，宽松兜底一次
              var loose = /(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})/.exec(String(text || ''));
              raw = loose ? loose[1] : null;
            }
            if (!raw) return 0;
            var s = raw.replace(' ', 'T');
            if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}${'$'}/.test(s)) s += '+08:00';
            var t = Date.parse(s);
            return isFinite(t) ? t : 0;
          }
          function num() {
            for (var i = 0; i < arguments.length; i++) {
              var n = Number(arguments[i]);
              if (isFinite(n) && n > 0) return n;
            }
            return 0;
          }
          function fiberOf(el) {
            if (!el || typeof el !== 'object') return null;
            var keys;
            try { keys = Object.keys(el); } catch (e) { return null; }
            for (var i = 0; i < keys.length; i++) {
              var k = keys[i];
              if (k.indexOf('__reactFiber${'$'}') === 0 || k.indexOf('__reactInternalInstance${'$'}') === 0) {
                try { return el[k]; } catch (e) { return null; }
              }
            }
            return null;
          }
          function looksLikeController(v) {
            return !!(v && typeof v === 'object' && v.conversationId &&
              (v.sessionStore || v.messageStore || v.store || typeof v.getState === 'function'));
          }
          // 与 WorkDaddy 同思路：从 React fiber 上找会话控制器，读官方结构化错误，
          // 不再靠页面文字猜限流（聊天内容里出现「限流」不会被误判）
          function findControllers(doc) {
            var root = null;
            try {
              root = doc.querySelector('#root > div') || doc.querySelector('#root') || doc.body;
            } catch (e) {}
            if (!root) return [];
            var out = [], seen = Object.create(null), visited = 0, stack = [root];
            var fiberSeen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
            while (stack.length && visited++ < 900) {
              var el = stack.pop();
              var fiber = fiberOf(el), hops = 0;
              while (fiber && hops++ < 400) {
                if (!fiberSeen || !fiberSeen.has(fiber)) {
                  if (fiberSeen) fiberSeen.add(fiber);
                  var props = fiber.memoizedProps;
                  var cands = props && [props.value, props.controller, props.adapter, props.store];
                  for (var i = 0; cands && i < cands.length; i++) {
                    var c = cands[i];
                    if (!looksLikeController(c)) continue;
                    var id = String(c.conversationId);
                    if (id && !seen[id]) { seen[id] = 1; out.push(c); }
                  }
                }
                fiber = fiber.return;
              }
              var children = el.children || [];
              for (var ci = 0; ci < children.length && ci < 40; ci++) stack.push(children[ci]);
            }
            return out;
          }
          var ctrlCache = [];
          function controllersOf(doc) {
            var now = Date.now();
            for (var i = 0; i < ctrlCache.length; i++) {
              var e = ctrlCache[i];
              if (e.doc !== doc) continue;
              if (now - e.at < 5000) return e.list;
              e.at = now;
              e.list = findControllers(doc);
              return e.list;
            }
            var list = findControllers(doc);
            ctrlCache.push({ doc: doc, at: now, list: list });
            if (ctrlCache.length > 4) ctrlCache.shift();
            return list;
          }
          function limitFromController(c) {
            var states = [];
            try {
              var ss = c.sessionStore || c.store;
              if (ss && typeof ss.getState === 'function') states.push(ss.getState());
            } catch (e) {}
            try {
              if (c.state && typeof c.state === 'object') states.push(c.state);
            } catch (e) {}
            for (var si = 0; si < states.length; si++) {
              var st = states[si];
              if (!st || typeof st !== 'object') continue;
              var errs = [st.error, st.warning, st.lastError, st.terminal];
              for (var ei = 0; ei < errs.length; ei++) {
                var err = errs[ei];
                if (!err || typeof err !== 'object') continue;
                var term = err.terminal && typeof err.terminal === 'object' ? err.terminal : {};
                var code = num(term.bizCode, err.bizCode, err.code);
                var status = num(err.statusCode, err.status);
                var limited = code === 6004 || code === 6000 || code === 14018 || status === 429;
                if (!limited) continue;
                if (!code) code = 6004;
                var text = [term.details, term.message, err.message].filter(function (v) {
                  return typeof v === 'string';
                }).join(' ');
                return {
                  code: code,
                  modelName: String(err.modelName || err.model || '').slice(0, 80),
                  modelId: String(err.requestModelId || err.modelId || term.requestModelId || '').slice(0, 120),
                  resetAt: num(term.resetAt, err.resetAt) || resetMillis(text),
                  what: String(term.details || err.message || '模型限流').slice(0, 80)
                };
              }
            }
            return null;
          }
          var lastStoreCount = -1;
          function scanRateLimit(doc) {
            var list = controllersOf(doc);
            // 会话状态接上与否回传一次：限流缺模型名/解封时间时，据此判断是页面没接上还是官方没给
            if (list.length !== lastStoreCount) {
              lastStoreCount = list.length;
              emit('diag', 'store|' + list.length, { store: list.length });
            }
            for (var i = 0; i < list.length; i++) {
              var info = limitFromController(list[i]);
              if (!info) continue;
              var model = info.modelName || info.modelId || '当前模型';
              emit('rateLimit', 'limit|' + info.code + '|' + model + '|' + info.resetAt, {
                model: model,
                modelName: model,
                modelId: info.modelId,
                code: info.code,
                resetAt: info.resetAt,
                source: 'store',
                what: info.what
              });
            }
          }
          // 自动继续限次：同一页面 10 分钟内最多代点 5 次，避免模型持续失败时无限续跑烧额度
          var contAt = [];
          function contAllowed(now) {
            var kept = [];
            for (var i = 0; i < contAt.length; i++) {
              if (now - contAt[i] < 600000) kept.push(contAt[i]);
            }
            contAt = kept;
            return contAt.length < 5;
          }
          function scanInterrupted(doc) {
            if (!doc.body) return;
            var text = String(doc.body.innerText || doc.body.textContent || '').slice(-4000);
            if (!BREAK.test(text) || MANUAL_STOP.test(text.slice(-300))) return;
            var hit = BREAK.exec(text);
            var line = text.slice(Math.max(0, hit.index - 60), Math.min(text.length, hit.index + 120));
            var key = 'break|' + line.slice(0, 80);
            var list = buttons(doc), target = null;
            for (var i = 0; i < list.length; i++) {
              if (!visible(list[i])) continue;
              if (!CONTINUE.test(norm(txt(list[i])))) continue;
              // 按钮必须靠近中断提示文案，避免误点页面里其它「继续 / 重试」
              var near = list[i], nearHit = false;
              for (var d = 0; d < 4 && near && near.parentElement; d++) {
                near = near.parentElement;
                if (near && BREAK.test(txt(near).slice(0, 500))) { nearHit = true; break; }
              }
              if (nearHit) { target = list[i]; break; }
            }
            var auto = false, blocked = false;
            var c = api.cfg || {};
            if (c.autoContinue && target && !target.getAttribute('data-cb-pet-cont')) {
              var now = Date.now();
              if (contAllowed(now)) {
                target.setAttribute('data-cb-pet-cont', '1');
                try { target.click(); contAt.push(now); auto = true; } catch (e) {}
              } else {
                blocked = true;
              }
            }
            emit('interrupted', key, {
              what: line.slice(0, 80), auto: auto, hasButton: !!target, blocked: blocked
            });
          }
          var seen = Object.create(null);
          var baseEmit = emit;
          emit = function (t, key, payload) {
            var now = Date.now();
            // 中断 / 权限弹窗只去重 20 秒：点完「继续」后再次中断必须还能再点，
            // 同一张确认卡（文案相同）后续再出现也要能重新提示；其它事件保持 10 分钟防重渲染刷屏
            var ttl = (t === 'interrupted' || t === 'approval') ? 20000 : 600000;
            if (seen[key] && now - seen[key] < ttl) return;
            seen[key] = now;
            baseEmit(t, key, payload);
          };
          // 暴露给一次性注入的脚本（快捷短语回执），走同一条 window.name 队列
          api.emit = function (t, key, payload) {
            try { emit(t, key || ('ext|' + Date.now()), payload || {}); } catch (e) {}
          };
          function scan() {
            pending = 0;
            var c = api.cfg || {};
            var all = docs();
            for (var i = 0; i < all.length; i++) {
              var doc = all[i];
              if (!doc || !doc.body) continue;
              try {
                if (c.observeApproval !== false) scanApproval(doc);
                if (c.observeRateLimit !== false) scanRateLimit(doc);
                if (c.observeInterrupt !== false) scanInterrupted(doc);
                // 实时速度采样只跟状态栏速度开关走；iframe 里的聊天区也要挂上
                if (c.observeStream === true) rateWatch(doc);
              } catch (e) {}
            }
          }
          function schedule() {
            if (pending) return;
            pending = setTimeout(scan, 120);
          }
          try {
            var observer = new MutationObserver(function (records) {
              // 只把变化点所在的确认卡容器排队，快路径不再整页遍历；
              // 根队列为空时由 2 秒兜底扫描退回整文档。
              for (var i = 0; i < records.length; i++) {
                var t = records[i].target;
                if (t && t.getAttribute) {
                  var a = t.getAttribute('data-cb-pet-ask') || t.getAttribute('data-cb-pet-cont');
                  if (a) continue;
                }
                queueRoot(t);
              }
              if (records.length) schedule();
            });
            observer.observe(document.body, {
              childList: true, subtree: true, attributes: true,
              attributeFilter: ['class', 'style', 'hidden', 'disabled', 'aria-disabled', 'data-state']
            });
          } catch (e) {}
          // 后台（页面 hidden）时下面的 2 秒兜底扫描会被浏览器节流到分钟级：
          // 恢复可见时立刻补扫一次并回传，避免 hidden 期间积压的确认卡 / 中断提示延迟处理
          try {
            document.addEventListener('visibilitychange', function () {
              if (document.visibilityState !== 'visible') return;
              if (pending) { clearTimeout(pending); pending = 0; }
              scan();
              flush();
            });
          } catch (e) {}
          setInterval(scan, 2000);
          // 速率结算与上报都跑得比扫描快：读数要亚秒级，事件扫描仍按 2 秒兜底
          setInterval(rateSettle, 400);
          setInterval(flush, 400);
          scan();
          flush();
        })();
    """.trimIndent()
}
