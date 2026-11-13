package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val REINJECT_MS = 10_000L
    private const val CONNECTED_MS = 8_000L

    /** 页面会话状态的有效期：这么久没再上报就当作未接上 */
    private const val STORE_TTL_MS = 10 * 60_000L

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-web-watch").apply { isDaemon = true }
        }

    private var task: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

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
    }

    fun stop() {
        running.set(false)
        task?.cancel(false)
        task = null
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
        // 「是否观察」「是否提示」「是否代点」三件事分开：
        // 关掉提醒不应关掉页面代点，关掉跑完提醒也不应关掉自动继续。
        // 处理方式不是「页面代点」时，页面只观察不点，避免和桌宠弹窗通道抢同一个确认。
        val pageActive = settings.cbApprovalMode == CbApprovalMode.PAGE
        val observeApproval = settings.cbApproveNoticeEnabled || pageActive
        val observeInterrupt = settings.cbProgressNoticeEnabled || settings.cbAutoContinueEnabled
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
            addProperty("observeRateLimit", settings.cbLimitNoticeEnabled)
        }
        val configJs = "window.__cbPetCfg=$cfg;" +
            "if(window.__cbPet){window.__cbPet.cfg=window.__cbPetCfg;}"
        val configChanged = configJs != lastConfig
        lastConfig = configJs

        val browsers = CodeBuddyApprover.browsers()
        val now = System.currentTimeMillis()
        for (browser in browsers) {
            try {
                val last = synchronized(injected) { injected[browser] }
                val needBoot = last == null || configChanged || now - last!! > REINJECT_MS ||
                    now - lastHeartbeat > CONNECTED_MS
                if (needBoot) {
                    CodeBuddyApprover.inject(browser, configJs + BOOT_JS)
                    synchronized(injected) { injected[browser] = now }
                    if (firstInjectAt == 0L) firstInjectAt = now
                } else {
                    CodeBuddyApprover.inject(browser, configJs)
                }
                readEvents(browser)
            } catch (_: Throwable) {
                // 单个面板不可用不影响其它面板
            }
        }
        // 观察功能开着却一个面板都找不到：留一条限频诊断，便于版本升级后定位承载位置
        if (browsers.isEmpty() && observeApproval) CodeBuddyApprover.logMissingPanel()
        // 脚本投递成功但迟迟没有回传：注入没执行或回传通道读不到，打日志区分
        if (lastHeartbeat == 0L && firstInjectAt > 0L && now - firstInjectAt > 15_000L) {
            logNoHeartbeat(browsers.first())
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
            JsonParser.parseString(parts[3]).takeIf { it.isJsonArray }?.asJsonArray ?: return
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
          if (W.__cbPet && W.__cbPet.alive) { W.__cbPet.cfg = W.__cbPetCfg || {}; return; }
          var boot = String(Date.now()) + '-' + Math.random().toString(36).slice(2, 8);
          var seq = 0, queue = [], cfg = W.__cbPetCfg || {}, pending = 0;
          var PREFIX = 'CBPET1|';
          var api = { alive: true, cfg: cfg };
          W.__cbPet = api;

          var APPROVE_ONCE = /^(允许|允许一次|允许访问|运行|确认执行|批准|同意|确认允许|Allow|Allow once|Run|Yes)${'$'}/i;
          var SESSION = /始终允许|本次会话内始终允许|Always\s*allow/i;
          var DENY = /拒绝|跳过|取消|禁止|Deny|Skip|Cancel/i;
          var BLOCK = /积分|信用|credit|消耗|付费|支付|费用|金额|余额|扣费|完全访问|全部允许|批量删除|凭证外发|发送到外部|确认开启/i;
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
            queue.push({ s: ++seq, e: payload });
            if (queue.length > 8) queue.splice(0, queue.length - 8);
            flush();
          }
          function flush() {
            try { W.name = PREFIX + boot + '|' + Date.now() + '|' + JSON.stringify(queue); } catch (e) {}
          }
          function buttons(doc) {
            return doc.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]');
          }
          function decisionBox(btn) {
            var box = btn;
            for (var depth = 0; depth < 6 && box && box.parentElement; depth++) {
              box = box.parentElement;
              // 与 buttons() 同口径：确认卡不一定用原生 button，role=button 的自绘按钮同样要能识别
              var list = box.querySelectorAll
                ? box.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]')
                : [];
              if (list.length > 12) break;
              var ctx = txt(box).slice(0, 700);
              if (list.length >= 2 && (DENY.test(ctx) || CONFIRM.test(ctx))) return { box: box, ctx: ctx, list: list };
            }
            return null;
          }
          function pickOnce(list) {
            var fallback = null;
            for (var i = 0; i < list.length; i++) {
              var el = list[i];
              if (!visible(el)) continue;
              var t = norm(txt(el));
              if (APPROVE_ONCE.test(t)) return el;
              if (!fallback && SESSION.test(t)) fallback = el;
            }
            return fallback;
          }
          // 只认「允许一次」这类单次授权；「始终允许」只能作为提示，绝不代点
          function pickStrict(list) {
            for (var i = 0; i < list.length; i++) {
              var el = list[i];
              if (!visible(el)) continue;
              if (APPROVE_ONCE.test(norm(txt(el)))) return el;
            }
            return null;
          }
          function describe(ctx) {
            var hit = /(包含危险命令[^。？?]*[。？?]?|是否[^。？?]{0,40}[。？?]?|检测到[^。？?]{0,40})/.exec(ctx);
            return (hit ? hit[1] : ctx).slice(0, 70) || '一个操作需要确认';
          }
          function scanApproval(doc) {
            var list = buttons(doc);
            for (var i = 0; i < list.length; i++) {
              var btn = list[i];
              if (!visible(btn)) continue;
              if (SESSION.test(norm(txt(btn))) || !APPROVE_ONCE.test(norm(txt(btn)))) continue;
              var found = decisionBox(btn);
              if (!found || found.box.getAttribute('data-cb-pet-ask')) continue;
              var strict = pickStrict(found.list);
              var once = strict || pickOnce(found.list);
              if (!once) continue;
              found.box.setAttribute('data-cb-pet-ask', '1');
              var danger = DANGER.test(found.ctx);
              var blocked = BLOCK.test(found.ctx);
              var auto = false;
              var c = api.cfg || {};
              // 决策看整张确认卡，不再要求「当前遍历到的按钮就是最终目标」：
              // 同一张卡里同时出现「允许」和「允许一次」时，后者才是 pickOnce 的结果
              if (c.noDisturb && !blocked && strict) {
                if (!danger) auto = true;
                if (c.noDisturbDanger && danger) auto = true;
              }
              emit('approval', 'ask|' + found.ctx.slice(0, 90), {
                what: describe(found.ctx), danger: danger, blocked: blocked, auto: auto
              });
              if (auto) {
                strict.setAttribute('data-cb-pet-auto', '1');
                try { strict.click(); } catch (e) {}
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
              } catch (e) {}
            }
          }
          function schedule() {
            if (pending) return;
            pending = setTimeout(scan, 120);
          }
          try {
            var observer = new MutationObserver(function (records) {
              for (var i = 0; i < records.length; i++) {
                var t = records[i].target;
                if (t && t.getAttribute) {
                  var a = t.getAttribute('data-cb-pet-ask') || t.getAttribute('data-cb-pet-cont');
                  if (a) continue;
                }
                schedule();
                return;
              }
            });
            observer.observe(document.body, {
              childList: true, subtree: true, attributes: true,
              attributeFilter: ['class', 'style', 'hidden', 'disabled', 'aria-disabled', 'data-state']
            });
          } catch (e) {}
          setInterval(scan, 2000);
          setInterval(flush, 1000);
          scan();
          flush();
        })();
    """.trimIndent()
}
