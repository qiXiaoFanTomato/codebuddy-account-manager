package dev.codebuddy.account.pet

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger

/**
 * 把快捷短语写进 CodeBuddy 聊天面板（JCEF）的输入框，可选直接发送。
 *
 * 复刻 WorkDaddy 在输入框操作栏「一键发送快捷短语」的效果，但 JetBrains 侧没有可挂的
 * 输入框操作栏，因此把动作放在本插件这一侧：选中短语 → 注入脚本写入并回车 / 点发送。
 *
 * 实现依据同 [CodeBuddyApprover]：反射拿到 `org.cef.browser.CefBrowser` 后
 * `executeJavaScript` 注入，**全部启发式**：
 *  - 输入框按「可见 + 面积最大 + 不像搜索框」挑选，兼容 textarea / contenteditable / Lexical；
 *  - textarea 走原型 value setter 再派发 input / change，React 受控组件才认；
 *  - contenteditable 走 `execCommand('insertText')`，失败退回 textContent + input 事件；
 *  - 输入框已有草稿时**追加**而不是覆盖，避免一条常用语把主人写了一半的内容冲掉；
 *  - 发送优先点「发送 / Send」短文案按钮，找不到才模拟 Enter。
 *
 * 成功与否通过 [CbWebObserver] 的常驻脚本回传（`emit('phrase', ...)`），
 * 页面没装常驻脚本时只保证「已投递」，不谎报发送成功。
 */
object QuickPhraseSender {

    private val log = logger<QuickPhraseSender>()

    private val gson = Gson()

    /**
     * 投递一条短语。
     *
     * @param autoSend true 表示写完直接发送，false 只填入输入框等主人自己改
     * @param projectName 优先使用该项目的 CodeBuddy 面板，避免多项目时投错窗口
     * @return 是否已把脚本投递到面板（不代表页面里一定找到输入框）
     */
    fun send(text: String, autoSend: Boolean, projectName: String? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val target = CodeBuddyApprover.browsers(projectName).firstOrNull() ?: return false
        return try {
            CodeBuddyApprover.inject(target, script(body, autoSend))
        } catch (t: Throwable) {
            log.warn("投递快捷短语失败", t)
            false
        }
    }

    /** 拼自包含脚本；正文走 Gson 生成 JS 字符串字面量，引号 / 换行由 Gson 负责转义 */
    private fun script(text: String, autoSend: Boolean): String = """
        (function (text, autoSend) {
          try {
            var W = window, D = document;
            function norm(s) { return String(s || '').replace(/\s+/g, ' ').trim(); }
            function vis(el) {
              try {
                if (!el || el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
                var r = el.getBoundingClientRect();
                return r.width >= 120 && r.height >= 16;
              } catch (e) { return false; }
            }
            // 主 frame + 同源 iframe：CodeBuddy 面板若把聊天放在子 frame 里也能摸到
            function docs() {
              var out = [D];
              try {
                var frames = D.querySelectorAll('iframe');
                for (var i = 0; i < frames.length && out.length < 4; i++) {
                  try {
                    var d = frames[i].contentDocument;
                    if (d && d.body) out.push(d);
                  } catch (e) {}
                }
              } catch (e) {}
              return out;
            }
            function pick() {
              var list = docs(), best = null, bestArea = 0;
              var sel = 'textarea, [contenteditable="true"], [contenteditable=""], ' +
                '[data-lexical-editor="true"], .ProseMirror';
              for (var di = 0; di < list.length; di++) {
                var nodes = list[di].querySelectorAll(sel);
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  if (!vis(el)) continue;
                  var hint = ((el.getAttribute('placeholder') || '') + ' ' +
                    (el.getAttribute('aria-label') || '') + ' ' + (el.className || '')).toLowerCase();
                  if (/搜索|search|filter|过滤|重命名|rename/.test(hint)) continue;
                  var r = el.getBoundingClientRect();
                  var area = r.width * r.height;
                  if (area > bestArea) { bestArea = area; best = el; }
                }
              }
              return best;
            }
            function put(el, t) {
              try { el.focus(); } catch (e) {}
              var doc = el.ownerDocument || D;
              var tag = String(el.tagName || '').toUpperCase();
              var simple = tag === 'TEXTAREA' || tag === 'INPUT';
              var current = simple ? String(el.value || '') : String(el.innerText || '');
              // 已有草稿就追加，覆盖会毁掉主人写了一半的内容
              var append = current.trim() !== '';
              var body = append ? ('\n' + t) : t;
              if (simple) {
                var next = append ? (current + '\n' + t) : t;
                try {
                  var proto = tag === 'TEXTAREA' ? W.HTMLTextAreaElement.prototype : W.HTMLInputElement.prototype;
                  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                  if (desc && desc.set) desc.set.call(el, next); else el.value = next;
                } catch (e) { el.value = next; }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
              }
              var ok = false;
              try {
                var sel = W.getSelection();
                var rng = doc.createRange();
                rng.selectNodeContents(el);
                if (append) rng.collapse(false);
                sel.removeAllRanges();
                sel.addRange(rng);
                ok = doc.execCommand('insertText', false, body);
              } catch (e) { ok = false; }
              if (!ok) el.textContent = append ? (current + '\n' + t) : t;
              el.dispatchEvent(new Event('input', { bubbles: true }));
              return true;
            }
            function clickSend(input) {
              var doc = (input && input.ownerDocument) || D;
              var want = /发送|send|submit/i;
              var bad = /停止|stop|取消|cancel|插话|中断/i;
              var nodes = doc.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]');
              for (var i = 0; i < nodes.length; i++) {
                var el = nodes[i];
                if (!vis(el)) continue;
                var t = norm((el.getAttribute('aria-label') || '') + ' ' +
                  (el.getAttribute('title') || '') + ' ' + (el.textContent || ''));
                if (!t || t.length > 24 || bad.test(t) || !want.test(t)) continue;
                try { el.click(); return true; } catch (e) {}
              }
              var target = input || doc.activeElement;
              if (!target) return false;
              var init = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
              ['keydown', 'keypress', 'keyup'].forEach(function (type) {
                try { target.dispatchEvent(new KeyboardEvent(type, init)); } catch (e) {}
              });
              return true;
            }
            function ack(status) {
              try {
                var api = W.__cbPet;
                if (api && typeof api.emit === 'function') {
                  api.emit('phrase', 'phrase|' + Date.now() + '|' + Math.random(), { status: status });
                }
              } catch (e) {}
            }
            var input = pick();
            if (!input) { ack('noinput'); return; }
            put(input, text);
            if (!autoSend) { ack('filled'); return; }
            setTimeout(function () { ack(clickSend(input) ? 'sent' : 'filled'); }, 80);
          } catch (e) {
            try {
              if (window.__cbPet && window.__cbPet.emit) {
                window.__cbPet.emit('phrase', 'phrase|err|' + Date.now(), { status: 'error' });
              }
            } catch (e2) {}
          }
        })(${gson.toJson(text)}, $autoSend);
    """.trimIndent()
}
