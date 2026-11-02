package dev.codebuddy.account.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager

/**
 * 状态栏 widget 的动态启用 / 停用。
 *
 * `StatusBarWidgetFactory.isAvailable()` 只在状态栏初始化时评估一次，
 * 因此修改「在状态栏显示当前账号」这类设置后必须重新评估，否则设置看起来不生效。
 */
object StatusBarSupport {

    /** 对所有已打开项目重新评估 widget 可用性 */
    fun refreshAll() {
        ProjectManager.getInstance().openProjects.forEach { refresh(it) }
    }

    fun refresh(project: Project) {
        try {
            // 内部实现类：updateAllWidgets() 会重新评估每个 factory 的 isAvailable 并增删 widget
            project.getService(StatusBarWidgetsManager::class.java)?.updateAllWidgets()
        } catch (_: Throwable) {
            // 内部 API 变动时静默降级：重启 IDE 后依然生效
        }
    }
}
