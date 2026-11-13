package dev.codebuddy.account.pet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 在 CodeBuddy 聊天面板（JCEF）里点击「允许」按钮。
 *
 * 实现依据（实测 CodeBuddy 4.12）：
 *  - 对话面板是 ToolWindow `com.tencent.code.intel.extension.WebviewBrowser`；
 *  - 面板内部的 JCEF 浏览器被两层包装（`WebviewBrowser` / `CBCefBrowser`）持有，
 *    **组件树里只有平台 UI 组件（`JBCefBrowser$MyPanel`），拿不到 `getCefBrowser()`**，
 *    因此这里从候选组件做有限字段扫描，反查出运行期的 `org.cef.browser.CefBrowser`；
 *  - 通过 `CefBrowser.executeJavaScript` 注入脚本，按按钮文案筛选后 `click()`。
 *
 * 本插件的编译 classpath 里没有 JCEF 模块，因此**全部走反射**（类在运行期由 IDE 平台提供），
 * 与 [dev.codebuddy.account.hotswap.CodeBuddyBridge] 的做法一致。
 *
 * 这是**逆向 UI 操作**，不是 CodeBuddy 的公开接口：
 *  - 页面上找不到匹配按钮时什么也不做，由调用方据日志判断是否成功；
 *  - 只点「允许 / 同意 / 运行」这类短文案，排除「始终允许 / 总是允许 / 记住」等扩权按钮；
 *  - 任何异常都静默降级，不影响主流程。
 */
object CodeBuddyApprover {

    private val log = logger<CodeBuddyApprover>()

    /**
     * CodeBuddy 对话侧边栏可能所在的 ToolWindow id。
     *
     * 新版（实测 marscode 1.7.0.4）把聊天面板注册为 `Trae AI`（工厂 `com.codeverse.factory.ChatTabFactory`），
     * 旧版才是 `com.tencent.code.intel.extension.WebviewBrowser`；旧 id 仍能查到窗口但 contents 为空，
     * 只认旧 id 会一直拿不到 JCEF，代点与页面提示全部失效。
     */
    private val CHAT_TOOL_WINDOW_IDS = listOf(
        "Trae AI",
        "MarsCode",
        "com.tencent.code.intel.extension.WebviewBrowser",
    )

    /** 运行期才存在的 JCEF 浏览器接口（编译期无依赖） */
    private const val CEF_BROWSER = "org.cef.browser.CefBrowser"

    /** 单个项目最多收集的面板数 */
    private const val MAX_BROWSERS = 8

    /** 字段扫描深度 / 类层次 / 节点上限：只为穿透 JBCefBrowser 两层包装，不做通用对象图遍历 */
    private const val MAX_SCAN_DEPTH = 4
    private const val MAX_SCAN_LEVELS = 10
    private const val MAX_SCAN_NODES = 200

    /** 兜底全量扫描的组件树深度与限频（候选 id 不命中时才走，避免每轮轮询遍历整棵 Swing 树） */
    private const val MAX_FALLBACK_DEPTH = 8
    private const val FALLBACK_INTERVAL_MS = 30_000L

    /** 一次解析结果；未命中也要短期记住，避免每轮轮询把整棵树重扫一遍 */
    private class Resolved(val browser: Any?, val at: Long)

    /** 组件 -> 解析结果；面板重建后旧组件被回收，缓存随弱键自动失效 */
    private val cefCache = WeakHashMap<Component, Resolved>()

    /** 未命中结果的缓存时长：面板可能在 ToolWindow 建好之后才创建 CefBrowser */
    private const val RESOLVE_TTL_MS = 10_000L

    @Volatile
    private var lastDiagnosticAt = 0L

    @Volatile
    private var lastFallbackAt = 0L

    /**
     * 注入点击脚本。
     *
     * 只接受「整词匹配」的短文案，并且排除会放大权限的按钮：
     *  - 允许 / 同意 / 确认 / 继续 / 运行 / 批准 / Allow / Approve / Accept / Run / Continue / Yes
     *  - 含「始终 / 总是 / 全部 / 所有 / Always / remember / 记住」的一律跳过
     */
    private val SCRIPT: String = """
        (function () {
          try {
            var ok = /^(允许|同意|确认|继续|运行|批准|允许运行|确认执行|Allow|Approve|Accept|Run|Continue|Yes)${'$'}/;
            var bad = /(始终|总是|全部|所有|Always|remember|记住)/i;
            var blocked = /(积分|信用|credit|消耗|付费|支付|费用|金额|余额|扣费|完全访问|批量删除|凭证外发|发送到外部|确认开启)/i;
            var nodes = Array.prototype.slice.call(
              document.querySelectorAll('button,[role="button"],a[role="button"],div[role="button"]')
            );
            var hit = null;
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              if (!el || el.disabled) continue;
              if (el.getAttribute && el.getAttribute('data-cb-pet-auto')) continue;
              var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
              if (!t || t.length > 12) continue;
              if (bad.test(t)) continue;
              var box = el, ctx = '';
              for (var d = 0; d < 4 && box && box.parentElement; d++) {
                box = box.parentElement;
                if (box) ctx = (box.textContent || '').slice(0, 500);
              }
              if (blocked.test(ctx)) continue;
              if (ok.test(t)) { hit = el; break; }
            }
            if (!hit) return 'NO_BUTTON';
            hit.click();
            return 'CLICKED';
          } catch (e) {
            return 'ERROR';
          }
        })();
    """.trimIndent()

    /**
     * 尝试点击「允许」：日志通道超时自动同意的兜底动作。
     * 常规免打扰由 [CbWebObserver] 的常驻脚本即时处理，这里只作补刀。
     *
     * @return 是否已把脚本投递到面板（不代表点击成功，成功与否由调用方查日志确认）
     */
    fun clickAllow(projectName: String? = null): Boolean {
        val browsers = browsers(projectName)
        if (browsers.isEmpty()) {
            logMissingPanel()
            return false
        }
        for (browser in browsers) {
            if (inject(browser, SCRIPT)) return true
        }
        return false
    }

    /** 向面板注入一段脚本；统一在 EDT 投递，后台轮询线程不直接碰 Swing 组件 */
    fun inject(browser: Any, script: String): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        app.invokeLater {
            try {
                val execute = browser.javaClass.methods.firstOrNull {
                    it.name == "executeJavaScript" && it.parameterCount == 3
                } ?: return@invokeLater
                execute.invoke(browser, script, frameUrl(browser), 0)
            } catch (e: Throwable) {
                log.warn("注入 CodeBuddy 脚本失败", e)
            }
        }
        return true
    }

    /** 当前页面 URL（脚本来源标记，失败时给空串） */
    private fun frameUrl(cef: Any): String = try {
        val frame = cef.javaClass.getMethod("getMainFrame").invoke(cef) ?: return ""
        (frame.javaClass.getMethod("getUrl").invoke(frame) as? String) ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * 主 frame 的 name（[CbWebObserver] 靠它回传页面事件）；读不到返回空串。
     * CEF 的 frame 调用是原生调用，统一回 EDT，避免后台线程直接碰 JCEF。
     */
    internal fun mainFrameName(cef: Any): String = onEdt {
        val frame = try {
            cef.javaClass.getMethod("getMainFrame").invoke(cef)
        } catch (_: Throwable) {
            null
        } ?: return@onEdt null
        try {
            frame.javaClass.getMethod("getName").invoke(frame) as? String
        } catch (_: Throwable) {
            null
        }
    } ?: ""

    /** 面板里的 JCEF 浏览器：优先项目名匹配，其次任意项目；组件树遍历必须在 EDT */
    fun browsers(projectName: String? = null): List<Any> = onEdt {
        val projects = try {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        } catch (_: Exception) {
            emptyList()
        }
        val ordered = if (projectName.isNullOrBlank()) {
            projects
        } else {
            projects.sortedByDescending { it.name == projectName }
        }
        val out = ArrayList<Any>()
        for (p in ordered) walkProject(p, out)
        out
    } ?: emptyList()

    private fun <T> onEdt(block: () -> T?): T? {
        val app = ApplicationManager.getApplication() ?: return null
        if (app.isDispatchThread) return block()
        var result: T? = null
        try {
            app.invokeAndWait { result = block() }
        } catch (_: Throwable) {
            return null
        }
        return result
    }

    private fun walkProject(project: Project, out: MutableList<Any>) {
        val manager = try {
            ToolWindowManager.getInstance(project)
        } catch (_: Exception) {
            return
        }
        var found = false
        for (id in CHAT_TOOL_WINDOW_IDS) {
            val window = try {
                manager.getToolWindow(id)
            } catch (_: Exception) {
                null
            } ?: continue
            val before = out.size
            try {
                for (content in window.contentManager.contents) {
                    collect(content.component, out)
                }
            } catch (_: Exception) {
                // 单个窗口不可用不影响其它候选
            }
            if (out.size > before) found = true
        }
        if (found) return
        // 候选 id 都不命中（CodeBuddy 再改 id）：限频扫一遍全部 ToolWindow 兜底
        val now = System.currentTimeMillis()
        if (now - lastFallbackAt < FALLBACK_INTERVAL_MS) return
        lastFallbackAt = now
        try {
            val budget = intArrayOf(MAX_SCAN_NODES)
            for (id in manager.toolWindowIds) {
                val window = try {
                    manager.getToolWindow(id)
                } catch (_: Exception) {
                    null
                } ?: continue
                for (content in window.contentManager.contents) {
                    collect(content.component, out, 0, budget)
                }
            }
        } catch (_: Throwable) {
            // 兜底扫描失败按找不到处理
        }
    }

    /**
     * 组件树里反查 CefBrowser。
     *
     * 命中一个面板后不再深入它的子组件；命中不到才继续遍历，避免在 JCEF 内部组件上做无谓扫描。
     */
    private fun collect(component: Component, out: MutableList<Any>) = collect(component, out, 0, null)

    /**
     * [budget] 非空时用于兜底全量扫描：限制节点数与递归深度，避免在 EDT 上遍历整棵 Swing 树。
     */
    private fun collect(component: Component, out: MutableList<Any>, depth: Int, budget: IntArray?) {
        if (out.size >= MAX_BROWSERS) return
        if (budget != null) {
            if (budget[0] <= 0 || depth > MAX_FALLBACK_DEPTH) return
            budget[0]--
        }
        val browser = cachedCef(component)
        if (browser != null) {
            if (out.none { it === browser }) out.add(browser)
            return
        }
        if (component is Container) {
            for (child in component.components) collect(child, out, depth + 1, budget)
        }
    }

    private fun cachedCef(component: Component): Any? {
        val now = System.currentTimeMillis()
        val cached = synchronized(cefCache) { cefCache[component] }
        if (cached != null && (cached.browser != null || now - cached.at < RESOLVE_TTL_MS)) {
            return cached.browser
        }
        val found = resolveCef(component)
        synchronized(cefCache) { cefCache[component] = Resolved(found, now) }
        return found
    }

    /** 组件自身即含 `getCefBrowser()`，否则做有限字段扫描（穿透 JBCefBrowser 包装） */
    private fun resolveCef(component: Component): Any? =
        cefMethodOf(component) ?: scanFields(component, MAX_SCAN_DEPTH, HashMap())

    private fun cefMethodOf(target: Any): Any? = try {
        val method = target.javaClass.methods.firstOrNull {
            it.name == "getCefBrowser" && it.parameterCount == 0
        } ?: return null
        method.invoke(target)?.takeIf { isCef(it) }
    } catch (_: Throwable) {
        null
    }

    private fun isCef(value: Any): Boolean =
        value.javaClass.name == CEF_BROWSER || implementsCef(value.javaClass)

    private fun implementsCef(cls: Class<*>?): Boolean {
        var current = cls
        var level = 0
        while (current != null && level < MAX_SCAN_LEVELS) {
            if (current.name == CEF_BROWSER) return true
            if (current.interfaces.any { it.name == CEF_BROWSER || implementsCef(it) }) return true
            current = current.superclass
            level++
        }
        return false
    }

    /**
     * 有限字段扫描：只沿 JCEF / CodeBuddy 自己的类型下钻，
     * 跳过 JDK 成员，避免在 EDT 上遍历整个 Swing 对象图。
     */
    private fun scanFields(target: Any, depth: Int, visited: MutableMap<Int, Boolean>): Any? {
        if (depth < 0 || visited.size > MAX_SCAN_NODES) return null
        if (visited.put(System.identityHashCode(target), true) != null) return null
        cefMethodOf(target)?.let { return it }
        var cls: Class<*>? = target.javaClass
        var level = 0
        while (cls != null && level < MAX_SCAN_LEVELS) {
            val fields = try {
                cls.declaredFields
            } catch (_: Throwable) {
                emptyArray<Field>()
            }
            for (field in fields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val typeName = field.type.name
                if (typeName == CEF_BROWSER) return readField(field, target)
                if (!interesting(typeName)) continue
                val value = readField(field, target) ?: continue
                if (isCef(value)) return value
                scanFields(value, depth - 1, visited)?.let { return it }
            }
            cls = cls.superclass
            level++
        }
        return null
    }

    private fun interesting(typeName: String): Boolean =
        typeName.startsWith("org.cef.") ||
            typeName.startsWith("com.intellij.ui.jcef") ||
            typeName.startsWith("com.tencent.code.intel")

    private fun readField(field: Field, target: Any): Any? = try {
        field.isAccessible = true
        field.get(target)
    } catch (_: Throwable) {
        null
    }

    /** 找不到面板时打印一次组件树快照（限频），版本变更后便于定位新的承载位置 */
    fun logMissingPanel() {
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticAt < 60_000L) return
        lastDiagnosticAt = now
        val snapshot = onEdt { diagnose() } ?: "无法读取 EDT"
        log.info("未找到 CodeBuddy 聊天面板，无法自动同意；组件树：${snapshot.take(600)}")
    }

    private fun diagnose(): String = try {
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.joinToString("; ") { project ->
            val manager = try {
                ToolWindowManager.getInstance(project)
            } catch (_: Throwable) {
                null
            } ?: return@joinToString "${project.name}: 无法读取 ToolWindowManager"
            val parts = CHAT_TOOL_WINDOW_IDS.map { id ->
                val window = try {
                    manager.getToolWindow(id)
                } catch (_: Throwable) {
                    null
                }
                if (window == null) {
                    "$id=无"
                } else {
                    val contents = window.contentManager.contents
                    "$id=${contents.size}[" +
                        contents.take(3).joinToString(", ") { describe(it.component, 0) } + "]"
                }
            }
            // 附上实际存在的 id，CodeBuddy 再换承载位置时可据此定位
            val ids = try {
                manager.toolWindowIds.take(30).joinToString(",")
            } catch (_: Throwable) {
                "读取失败"
            }
            "${project.name}: ${parts.joinToString(" ")}; 现有 id=[$ids]"
        }
    } catch (e: Throwable) {
        "诊断失败: ${e.message}"
    }

    private fun describe(component: Component, depth: Int): String {
        val name = component.javaClass.name
        if (depth >= 3) return name
        if (component is Container && component.components.isNotEmpty()) {
            return "$name(" + component.components.take(6).joinToString(",") { describe(it, depth + 1) } + ")"
        }
        return name
    }
}
