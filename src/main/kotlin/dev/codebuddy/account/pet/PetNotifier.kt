package dev.codebuddy.account.pet

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 系统提示的统一出口：**优先由桌宠承载**，桌宠不可用时退回 IDE 气泡通知。
 *
 * 信息范围（全部走这里）：
 * - 操作反馈：切号、热切换、导出、历史同步、批量同步、退出登录
 * - 错误提醒：切换失败、导出失败、额度查询失败、token 异常、调度失败
 * - 状态变更：低额度告警、模型限额达标 / 逼近、自动调度、成长中心与签到结果
 *
 * 不覆盖：对话框内的表单校验（就地提示更合适）、日志、以及 IDE 自身的通知。
 *
 * 桌宠承载时按等级映射动作，见 [PetAnimationLibrary.noticeAnimation]；
 * 展示时长、队列与优先级由 [PetService] 统一调度。
 */
object PetNotifier {

    /** 提示等级：决定动作、配色、展示时长与优先级 */
    enum class Level(val priority: Int) {
        /** 普通反馈（默认） */
        INFO(0),

        /** 操作成功 */
        SUCCESS(1),

        /** 需要留意，但不影响继续操作 */
        WARN(2),

        /** 操作失败 / 数据异常 */
        ERROR(3),
    }

    const val GROUP_ID = "CodeBuddy Account Manager"

    /** 对话完成提示的停留时长 */
    private const val ROUND_FINISH_DURATION_MS = 12_000

    fun fromIDE(type: NotificationType?): Level = when (type) {
        NotificationType.ERROR -> Level.ERROR
        NotificationType.WARNING -> Level.WARN
        else -> Level.INFO
    }

    /**
     * 发一条提示。
     *
     * @param durationMs 自定义展示时长；null 表示按等级与文本长度自动计算
     */
    fun notify(
        level: Level,
        title: String,
        content: String,
        project: Project? = null,
        durationMs: Int? = null,
        highlight: Boolean = false,
    ) {
        val shownByPet = try {
            PetService.getInstance().showNotice(level, title, content, durationMs, highlight)
        } catch (_: Throwable) {
            false
        }
        if (shownByPet) return
        balloon(level, title, content, project)
    }

    /** 兼容旧调用：直接传 IDE 的 [NotificationType] */
    fun notify(title: String, content: String, type: NotificationType? = null, project: Project? = null) {
        notify(fromIDE(type), title, content, project)
    }

    /**
     * CodeBuddy 一轮对话正常完成的强提醒：桌宠临时置顶、弹跳、响两声，
     * 气泡用更大的标题与配图，停留 [ROUND_FINISH_DURATION_MS]。
     */
    fun roundFinished(project: String, steps: Int) {
        val tail = if (steps > 0) "共 $steps 步，跑完了" else "本轮跑完了"
        notify(
            Level.SUCCESS,
            "对话完成啦，回来看看",
            "「$project」$tail",
            durationMs = ROUND_FINISH_DURATION_MS,
            highlight = true,
        )
    }

    /** 成功反馈的便捷入口 */
    fun success(title: String, content: String, project: Project? = null) =
        notify(Level.SUCCESS, title, content, project)

    /** 警告的便捷入口 */
    fun warn(title: String, content: String, project: Project? = null) =
        notify(Level.WARN, title, content, project)

    /** 错误的便捷入口 */
    fun error(title: String, content: String, project: Project? = null) =
        notify(Level.ERROR, title, content, project)

    /** 退回 IDE 气泡通知（原行为，桌宠关闭 / 非持有者时使用） */
    fun balloon(level: Level, title: String, content: String, project: Project?) {
        try {
            val type = when (level) {
                Level.ERROR -> NotificationType.ERROR
                Level.WARN -> NotificationType.WARNING
                else -> NotificationType.INFORMATION
            }
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, type)
                .notify(project)
        } catch (_: Throwable) {
            // 通知失败不影响主流程
        }
    }
}
