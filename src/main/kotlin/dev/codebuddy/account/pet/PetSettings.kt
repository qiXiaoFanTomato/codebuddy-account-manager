package dev.codebuddy.account.pet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 桌宠（档案柜精灵）的持久化设置。
 *
 * 与 [dev.codebuddy.account.service.AccountSettings] 分开存储：
 * 这里的字段全部是「浮窗外观」相关，与账号 / 额度业务无关。
 *
 * 位置用哨兵 [NO_POSITION] 表示「尚未拖动过」，首次显示时按主屏右下角定位。
 */
@Service(Service.Level.APP)
@State(name = "CodeBuddyPetSettings", storages = [Storage("codebuddy-pet.xml")])
class PetSettings : PersistentStateComponent<PetSettings.State> {

    data class State(
        /** 是否显示桌宠（关闭后释放单例锁，其他 IDE 实例可接管） */
        var enabled: Boolean = true,
        /** 尺寸百分比，100 为基准 */
        var scalePercent: Int = 100,
        /** 窗口不透明度百分比 */
        var opacityPercent: Int = 100,
        /** 是否置顶 */
        var alwaysOnTop: Boolean = true,
        /** 上次拖动后的屏幕坐标 */
        var posX: Int = NO_POSITION,
        var posY: Int = NO_POSITION,
        /** 宠物身上是否显示剩余额度读数 */
        var showQuotaBadge: Boolean = true,
        /** 呼吸 / 眨眼动画（关闭后只保留静态形象，省电） */
        var animationsEnabled: Boolean = true,
        /** 点击音效（原项目 click-duck.wav） */
        var clickSoundEnabled: Boolean = true,
        /** 停靠位置（PetQuadrant.name）；FREE 表示沿用拖动后的自由位置 */
        var quadrant: String = PetQuadrant.BOTTOM_RIGHT.name,
        /** 松手后受重力下落、可抛出并反弹（对齐原项目 physics 的手感） */
        var physicsEnabled: Boolean = true,
        /** 每隔几分钟自己做一个随机小动作 */
        var randomActionsEnabled: Boolean = true,
        /** 随机动作间隔（分钟） */
        var randomActionMinutes: Int = 3,
        /** 每隔几分钟冒一句碎碎念台词 */
        var whisperEnabled: Boolean = true,
        /** 碎碎念间隔（分钟） */
        var whisperMinutes: Int = 5,
        /** 每隔几分钟主动给主人讲一个笑话 */
        var jokeEnabled: Boolean = true,
        /** 主动讲笑话的间隔（分钟） */
        var jokeMinutes: Int = 10,
        /** 系统提示由桌宠承载（关掉则退回 IDE 气泡通知） */
        var noticePetEnabled: Boolean = true,
        /** CodeBuddy 需要主人确认操作时提示（读 CodeBuddy 运行日志） */
        var cbApproveNoticeEnabled: Boolean = true,
        /** CodeBuddy 一轮结束 / 没跑顺时提示 */
        var cbProgressNoticeEnabled: Boolean = true,
        /** 超过设定分钟数仍未确认时，替主人点击「允许」（危险命令除外） */
        var cbAutoApproveEnabled: Boolean = false,
        /** 自动同意的等待分钟数 */
        var cbAutoApproveMinutes: Int = 5,
        /** 权限弹窗免打扰：页面里出现「允许 / 运行 + 拒绝」决策组时直接代点（危险命令除外） */
        var cbNoDisturbEnabled: Boolean = false,
        /** 高风险：连危险命令确认也代点；扣费 / 提权 / 凭证外发仍然永不代点 */
        var cbNoDisturbDangerEnabled: Boolean = false,
        /** 异常中断会话自动继续：页面 / 日志判定中断后点「继续 / 重试」 */
        var cbAutoContinueEnabled: Boolean = false,
        /** 模型频率限制与预计解封时间提示 */
        var cbLimitNoticeEnabled: Boolean = true,
        /** 成长计划与活跃状态提示 */
        var cbGrowthNoticeEnabled: Boolean = true,
        /** 账号间会话迁移结果提示 */
        var cbHistoryNoticeEnabled: Boolean = true,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        myState.scalePercent = myState.scalePercent.coerceIn(MIN_SCALE, MAX_SCALE)
        myState.opacityPercent = myState.opacityPercent.coerceIn(MIN_OPACITY, 100)
    }

    var enabled: Boolean
        get() = myState.enabled
        set(value) {
            myState.enabled = value
        }

    var scalePercent: Int
        get() = myState.scalePercent
        set(value) {
            myState.scalePercent = value.coerceIn(MIN_SCALE, MAX_SCALE)
        }

    var opacityPercent: Int
        get() = myState.opacityPercent
        set(value) {
            myState.opacityPercent = value.coerceIn(MIN_OPACITY, 100)
        }

    var alwaysOnTop: Boolean
        get() = myState.alwaysOnTop
        set(value) {
            myState.alwaysOnTop = value
        }

    var showQuotaBadge: Boolean
        get() = myState.showQuotaBadge
        set(value) {
            myState.showQuotaBadge = value
        }

    var animationsEnabled: Boolean
        get() = myState.animationsEnabled
        set(value) {
            myState.animationsEnabled = value
        }

    var clickSoundEnabled: Boolean
        get() = myState.clickSoundEnabled
        set(value) {
            myState.clickSoundEnabled = value
        }

    var quadrant: PetQuadrant
        get() = PetQuadrant.of(myState.quadrant)
        set(value) {
            myState.quadrant = value.name
        }

    var physicsEnabled: Boolean
        get() = myState.physicsEnabled
        set(value) {
            myState.physicsEnabled = value
        }

    var randomActionsEnabled: Boolean
        get() = myState.randomActionsEnabled
        set(value) {
            myState.randomActionsEnabled = value
        }

    /** 随机动作间隔（分钟），收敛到 1~60 */
    var randomActionMinutes: Int
        get() = myState.randomActionMinutes.coerceIn(MIN_RANDOM_MINUTES, MAX_RANDOM_MINUTES)
        set(value) {
            myState.randomActionMinutes = value.coerceIn(MIN_RANDOM_MINUTES, MAX_RANDOM_MINUTES)
        }

    var whisperEnabled: Boolean
        get() = myState.whisperEnabled
        set(value) {
            myState.whisperEnabled = value
        }

    /** 碎碎念间隔（分钟），收敛到 1~60 */
    var whisperMinutes: Int
        get() = myState.whisperMinutes.coerceIn(MIN_RANDOM_MINUTES, MAX_RANDOM_MINUTES)
        set(value) {
            myState.whisperMinutes = value.coerceIn(MIN_RANDOM_MINUTES, MAX_RANDOM_MINUTES)
        }

    var jokeEnabled: Boolean
        get() = myState.jokeEnabled
        set(value) {
            myState.jokeEnabled = value
        }

    /** 主动讲笑话间隔（分钟），收敛到 1~60 */
    var jokeMinutes: Int
        get() = myState.jokeMinutes.coerceIn(MIN_RANDOM_MINUTES, MAX_RANDOM_MINUTES)
        set(value) {
            myState.jokeMinutes = value.coerceIn(MIN_RANDOM_MINUTES, MAX_RANDOM_MINUTES)
        }

    /** 系统提示（操作反馈 / 错误 / 状态变更）是否由桌宠承载 */
    var noticePetEnabled: Boolean
        get() = myState.noticePetEnabled
        set(value) {
            myState.noticePetEnabled = value
        }

    /** CodeBuddy 需要确认时是否提示 */
    var cbApproveNoticeEnabled: Boolean
        get() = myState.cbApproveNoticeEnabled
        set(value) {
            myState.cbApproveNoticeEnabled = value
        }

    /** CodeBuddy 轮次结束 / 异常时是否提示 */
    var cbProgressNoticeEnabled: Boolean
        get() = myState.cbProgressNoticeEnabled
        set(value) {
            myState.cbProgressNoticeEnabled = value
        }

    /** 是否超时自动同意 */
    var cbAutoApproveEnabled: Boolean
        get() = myState.cbAutoApproveEnabled
        set(value) {
            myState.cbAutoApproveEnabled = value
        }

    /** 自动同意的等待分钟数，收敛到 1~30 */
    var cbAutoApproveMinutes: Int
        get() = myState.cbAutoApproveMinutes.coerceIn(MIN_AUTO_APPROVE_MINUTES, MAX_AUTO_APPROVE_MINUTES)
        set(value) {
            myState.cbAutoApproveMinutes = value.coerceIn(MIN_AUTO_APPROVE_MINUTES, MAX_AUTO_APPROVE_MINUTES)
        }

    /** 权限弹窗免打扰 */
    var cbNoDisturbEnabled: Boolean
        get() = myState.cbNoDisturbEnabled
        set(value) { myState.cbNoDisturbEnabled = value }

    /** 危险命令是否也代点（高风险） */
    var cbNoDisturbDangerEnabled: Boolean
        get() = myState.cbNoDisturbDangerEnabled
        set(value) { myState.cbNoDisturbDangerEnabled = value }

    /** 异常中断会话自动继续 */
    var cbAutoContinueEnabled: Boolean
        get() = myState.cbAutoContinueEnabled
        set(value) { myState.cbAutoContinueEnabled = value }

    /** 模型频率限制与预计解封时间提示 */
    var cbLimitNoticeEnabled: Boolean
        get() = myState.cbLimitNoticeEnabled
        set(value) { myState.cbLimitNoticeEnabled = value }

    /** 成长计划与活跃状态提示 */
    var cbGrowthNoticeEnabled: Boolean
        get() = myState.cbGrowthNoticeEnabled
        set(value) { myState.cbGrowthNoticeEnabled = value }

    /** 账号间会话迁移结果提示 */
    var cbHistoryNoticeEnabled: Boolean
        get() = myState.cbHistoryNoticeEnabled
        set(value) { myState.cbHistoryNoticeEnabled = value }

    /** 上次保存的坐标；未拖动过时返回 null */
    val savedPosition: Pair<Int, Int>?
        get() {
            val x = myState.posX
            val y = myState.posY
            if (x == NO_POSITION || y == NO_POSITION) return null
            return x to y
        }

    fun savePosition(x: Int, y: Int) {
        myState.posX = x
        myState.posY = y
    }

    companion object {
        const val MIN_SCALE = 60
        const val MAX_SCALE = 160
        const val MIN_OPACITY = 50

        /** 随机动作间隔范围（分钟） */
        const val MIN_RANDOM_MINUTES = 1
        const val MAX_RANDOM_MINUTES = 60

        /** 自动同意等待分钟数范围 */
        const val MIN_AUTO_APPROVE_MINUTES = 1
        const val MAX_AUTO_APPROVE_MINUTES = 30

        /** 未设置坐标的哨兵值 */
        private const val NO_POSITION = Int.MIN_VALUE

        fun getInstance(): PetSettings =
            ApplicationManager.getApplication().getService(PetSettings::class.java)
    }
}

/** 桌宠停靠位置：四角吸附，或跟随手动拖动 */
enum class PetQuadrant(val label: String) {
    FREE("自由拖动"),
    TOP_LEFT("左上角"),
    TOP_RIGHT("右上角"),
    BOTTOM_LEFT("左下角"),
    BOTTOM_RIGHT("右下角"),
    ;

    companion object {
        fun of(name: String?): PetQuadrant =
            entries.firstOrNull { it.name == name } ?: BOTTOM_RIGHT
    }
}
