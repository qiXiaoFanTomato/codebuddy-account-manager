package dev.codebuddy.account.hotswap

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.util.Hashtable

/**
 * 与 CodeBuddy 主插件（id=com.tencent.cloud.codingcopilot）的热切换桥。
 *
 * 实测依据（4.12.41911495）：
 *  - CodeBuddy 用自定义 PersistentStateComponent
 *    `com.tencent.code.intel.apibridge.extension.mainthread.secretstate.SecretStorage`
 *    保存账号凭据，内存表是 `SecretTable extends Hashtable<String,String>`；
 *    Node 侧通过 `MainThreadSecretState`（GET/SET/DELETE_PASSWORD）访问该表。
 *  - `secret-storage.xml` 是该 component 的持久化文件（key = 凭据条目名）。
 *  - `com.tencent.code.intel.action.ResetPluginStatusAction` → `NodeProjectService.reset()`：
 *    释放旧 NodeRunner 并重建，即重启 CodeBuddy 的 Node 侧进程，不影响 IDEA 本身。
 *
 * 因此「不重启 IDE 切号」= 改内存表 + 触发 ResetPluginStatusAction。
 * 全部走反射，CodeBuddy 未安装或版本变更时安全降级为 false。
 */
object CodeBuddyBridge {

    private val log = logger<CodeBuddyBridge>()

    private const val PLUGIN_ID = "com.tencent.cloud.codingcopilot"

    private const val SECRET_STORAGE_CLASS =
        "com.tencent.code.intel.apibridge.extension.mainthread.secretstate.SecretStorage"

    private const val RESET_ACTION_ID = "com.tencent.code.intel.action.ResetPluginStatusAction"

    /** CodeBuddy 插件类加载器；未安装返回 null */
    fun pluginClassLoader(): ClassLoader? = try {
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginClassLoader
    } catch (e: Exception) {
        log.warn("获取 CodeBuddy 插件类加载器失败", e)
        null
    }

    /** 是否可用热切换（CodeBuddy 已安装且类可解析） */
    fun isAvailable(): Boolean {
        val cl = pluginClassLoader() ?: return false
        return try {
            Class.forName(SECRET_STORAGE_CLASS, false, cl)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 写入 CodeBuddy 内存凭据表 */
    fun setInMemorySecret(key: String, value: String): Boolean {
        val cl = pluginClassLoader() ?: return false
        return try {
            val cls = Class.forName(SECRET_STORAGE_CLASS, true, cl)
            val instance = cls.getMethod("getInstance").invoke(null) ?: return false
            val scores = cls.getMethod("getScores").invoke(instance) ?: return false
            @Suppress("UNCHECKED_CAST")
            val table = scores as Hashtable<String, String>
            table[key] = value
            true
        } catch (e: Exception) {
            log.warn("写入 CodeBuddy 内存凭据失败", e)
            false
        }
    }

    /** 读回 CodeBuddy 内存凭据（校验用） */
    fun getInMemorySecret(key: String): String? {
        val cl = pluginClassLoader() ?: return null
        return try {
            val cls = Class.forName(SECRET_STORAGE_CLASS, true, cl)
            val instance = cls.getMethod("getInstance").invoke(null) ?: return null
            val scores = cls.getMethod("getScores").invoke(instance) as? Hashtable<*, *> ?: return null
            scores[key] as? String
        } catch (e: Exception) {
            log.warn("读取 CodeBuddy 内存凭据失败", e)
            null
        }
    }

    /**
     * 重启 CodeBuddy 的 Node 侧进程（等价于其 Settings 里的「Reset Plugin Status」）。
     * 调用后 CodeBuddy 会重新初始化并重新读取凭据。
     */
    fun restartAgent(project: Project): Boolean {
        return try {
            val action = ActionManager.getInstance().getAction(RESET_ACTION_ID)
            if (action == null) {
                log.warn("未找到 CodeBuddy 重置动作: $RESET_ACTION_ID")
                return false
            }
            val context = SimpleDataContext.getSimpleContext(
                CommonDataKeys.PROJECT.name,
                project,
                DataContext.EMPTY_CONTEXT
            )
            action.actionPerformed(
                AnActionEvent.createFromAnAction(action, null, "CodeBuddyAccountManager", context)
            )
            true
        } catch (e: Exception) {
            log.warn("重启 CodeBuddy 内核失败", e)
            false
        }
    }
}
