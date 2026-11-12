package dev.codebuddy.account.pet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.awt.Component
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

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "codebuddy-web-watch").apply { isDaemon = true }
        }

    private var task: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

    /** 组件 -> 上次注入完整脚本的时间；弱引用避免持有已关闭的面板 */
    private val injected = WeakHashMap<Component, Long>()

    @Volatile
    private var lastHeartbeat = 0L

    @Volatile
    private var lastBoot = ""

    @Volatile
    private var lastSeq = 0L

    @Volatile
    private var lastConfig = ""

    /** 启动观察（幂等）。由 [PetService] 在桌宠窗口就绪后调用 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        synchronized(injected) { injected.clear() }
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
    }

    /** 注入通道是否有心跳（供设置页展示降级状态） */
    fun isConnected(): Boolean = System.currentTimeMillis() - lastHeartbeat < CONNECTED_MS

    /** 日志通道判定「异常中断」后，请求页面点一次「继续 / 重试」 */
    fun clickContinue() {
        if (!PetSettings.getInstance().cbAutoContinueEnabled) return
        for (component in CodeBuddyApprover.browsers()) {
            CodeBuddyApprover.inject(component, CONTINUE_JS)
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
        // 三类观察全关时只保留心跳，不扫描 DOM
        val cfg = JsonObject().apply {
            addProperty("approval", settings.cbApproveNoticeEnabled)
            addProperty("noDisturb", settings.cbNoDisturbEnabled)
            addProperty("noDisturbDanger", settings.cbNoDisturbDangerEnabled)
            addProperty("autoContinue", settings.cbAutoContinueEnabled)
            addProperty("rateLimit", settings.cbLimitNoticeEnabled)
            addProperty("interrupt", settings.cbProgressNoticeEnabled)
        }
        val configJs = "window.__cbPetCfg=$cfg;" +
            "if(window.__cbPet){window.__cbPet.cfg=window.__cbPetCfg;}"
        val configChanged = configJs != lastConfig
        lastConfig = configJs

        val components = CodeBuddyApprover.browsers()
        val now = System.currentTimeMillis()
        for (component in components) {
            try {
                val last = synchronized(injected) { injected[component] }
                val needBoot = last == null || configChanged || now - last!! > REINJECT_MS ||
                    now - lastHeartbeat > CONNECTED_MS
                if (needBoot) {
                    CodeBuddyApprover.inject(component, configJs + BOOT_JS)
                    synchronized(injected) { injected[component] = now }
                } else {
                    CodeBuddyApprover.inject(component, configJs)
                }
                readEvents(component)
            } catch (_: Throwable) {
                // 单个面板不可用不影响其它面板
            }
        }
    }

    /** 读取 `window.name` 里的事件队列；按 bootId + seq 去重后交给事件中心 */
    private fun readEvents(component: Component) {
        val cef = CodeBuddyApprover.cef(component) ?: return
        val frame = try {
            cef.javaClass.getMethod("getMainFrame").invoke(cef)
        } catch (_: Throwable) {
            return
        } ?: return
        val raw = try {
            frame.javaClass.getMethod("getName").invoke(frame) as? String
        } catch (_: Throwable) {
            return
        } ?: return
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
            val event = item.get("e")?.takeIf { it.isJsonObject } ?: continue
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
          var LIMIT = /频率限制|限流|rate.?limit|请求过于频繁|用量限制|额度不足|429|6004|6000|14018/i;
          var RESET = /(?:Reset at|预计解封|预计恢复|解封时间|恢复时间|resetAt)[^0-9]{0,12}(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})/i;
          var MODEL = /\b((?:claude|deepseek|gpt|gemini|hunyuan|kimi|qwen|glm|doubao)[-\w.]*)/i;
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
              var list = box.querySelectorAll ? box.querySelectorAll('button') : [];
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
              var once = pickOnce(found.list);
              if (!once) continue;
              found.box.setAttribute('data-cb-pet-ask', '1');
              var danger = DANGER.test(found.ctx);
              var blocked = BLOCK.test(found.ctx);
              var auto = false;
              var c = api.cfg || {};
              if (c.approval !== false && !blocked && once === btn) {
                if (c.noDisturb && !danger) auto = true;
                if (c.noDisturbDanger && danger) auto = true;
              }
              emit('approval', 'ask|' + found.ctx.slice(0, 90), {
                what: describe(found.ctx), danger: danger, blocked: blocked, auto: auto
              });
              if (auto) {
                btn.setAttribute('data-cb-pet-auto', '1');
                try { btn.click(); } catch (e) {}
              }
            }
          }
          function resetMillis(text) {
            var m = RESET.exec(text);
            if (!m) return 0;
            var s = m[1].replace(' ', 'T');
            if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}${'$'}/.test(s)) s += '+08:00';
            var t = Date.parse(s);
            return isFinite(t) ? t : 0;
          }
          function limitCode(text) {
            if (/6004|频率限制|rate.?limit|限流/.test(text)) return 6004;
            if (/6000|用量限制/.test(text)) return 6000;
            if (/14018|额度不足|配额|quota/.test(text)) return 14018;
            return 6004;
          }
          function scanRateLimit(doc) {
            if (!doc.body) return;
            var text = String(doc.body.innerText || doc.body.textContent || '').slice(-4000);
            if (!LIMIT.test(text)) return;
            var hit = LIMIT.exec(text);
            var line = text.slice(Math.max(0, hit.index - 120), Math.min(text.length, hit.index + 240));
            var model = MODEL.exec(line);
            var resetAt = resetMillis(line);
            var key = 'limit|' + resetAt + '|' + line.slice(0, 80);
            emit('rateLimit', key, {
              model: model ? model[1] : '当前模型',
              code: limitCode(line),
              resetAt: resetAt,
              what: line.slice(0, 80)
            });
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
            var auto = false;
            var c = api.cfg || {};
            if (c.autoContinue && c.interrupt !== false && target && !target.getAttribute('data-cb-pet-cont')) {
              target.setAttribute('data-cb-pet-cont', '1');
              try { target.click(); auto = true; } catch (e) {}
            }
            emit('interrupted', key, { what: line.slice(0, 80), auto: auto, hasButton: !!target });
          }
          var seen = Object.create(null);
          var baseEmit = emit;
          emit = function (t, key, payload) {
            var now = Date.now();
            if (seen[key] && now - seen[key] < 600000) return;
            seen[key] = now;
            baseEmit(t, key, payload);
          };
          function scan() {
            pending = 0;
            var c = api.cfg || {};
            var all = docs();
            for (var i = 0; i < all.length; i++) {
              var doc = all[i];
              if (!doc || !doc.body) continue;
              try {
                if (c.approval !== false || c.rateLimit !== false) scanApproval(doc);
                if (c.rateLimit !== false) scanRateLimit(doc);
                if (c.interrupt !== false) scanInterrupted(doc);
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
