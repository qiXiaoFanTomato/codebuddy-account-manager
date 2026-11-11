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
        /** 系统提示由桌宠承载（关掉则退回 IDE 气泡通知） */
        var noticePetEnabled: Boolean = true,
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

    /** 系统提示（操作反馈 / 错误 / 状态变更）是否由桌宠承载 */
    var noticePetEnabled: Boolean
        get() = myState.noticePetEnabled
        set(value) {
            myState.noticePetEnabled = value
        }

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
