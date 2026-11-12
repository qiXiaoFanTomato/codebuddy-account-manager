package dev.codebuddy.account.pet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container

/**
 * 在 CodeBuddy 聊天面板（JCEF）里点击「允许」按钮。
 *
 * 实现依据（实测 4.12.42888899）：
 *  - 对话面板是 ToolWindow `com.tencent.code.intel.extension.WebviewBrowser` 里的 JCEF 页面；
 *  - 面板内部持有 `JBCefBrowser`（`WebviewPanel.browser` 字段），能从 ToolWindow 的组件树里拿到；
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

    /** CodeBuddy 对话侧边栏的 ToolWindow id（与 [dev.codebuddy.account.hotswap.CodeBuddyBridge] 一致） */
    private const val CHAT_TOOL_WINDOW_ID = "com.tencent.code.intel.extension.WebviewBrowser"

    private const val MAX_COMPONENTS = 8

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
        val components = browsers(projectName)
        if (components.isEmpty()) {
            log.info("未找到 CodeBuddy 聊天面板，无法自动同意")
            return false
        }
        for (component in components) {
            if (inject(component, SCRIPT)) return true
        }
        return false
    }

    /** 取组件持有的 CefBrowser（运行期由 IDE 平台提供，编译期无 JCEF 依赖） */
    fun cef(component: Component): Any? = try {
        component.javaClass.methods.firstOrNull {
            it.name == "getCefBrowser" && it.parameterCount == 0
        }?.invoke(component)
    } catch (_: Throwable) {
        null
    }

    /** 向面板注入一段脚本；统一在 EDT 投递，后台轮询线程不直接碰 Swing 组件 */
    fun inject(component: Component, script: String): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        val browser = cef(component) ?: return false
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

    /** 面板里的 JCEF 组件：优先项目名匹配，其次任意项目；组件树遍历必须在 EDT */
    fun browsers(projectName: String? = null): List<Component> = onEdt {
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
        val out = ArrayList<Component>()
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

    private fun walkProject(project: Project, out: MutableList<Component>) {
        try {
            val window = ToolWindowManager.getInstance(project).getToolWindow(CHAT_TOOL_WINDOW_ID) ?: return
            for (content in window.contentManager.contents) {
                collect(content.component, out)
            }
        } catch (_: Exception) {
            // 面板尚未创建时忽略
        }
    }

    /** 组件树里带 `getCefBrowser()` 的组件即为 JCEF 浏览器 */
    private fun collect(component: Component, out: MutableList<Component>) {
        if (out.size >= MAX_COMPONENTS) return
        val isCef = try {
            component.javaClass.methods.any { it.name == "getCefBrowser" && it.parameterCount == 0 }
        } catch (_: Throwable) {
            false
        }
        if (isCef) {
            out.add(component)
            return
        }
        if (component is Container) {
            for (child in component.components) collect(child, out)
        }
    }
}
