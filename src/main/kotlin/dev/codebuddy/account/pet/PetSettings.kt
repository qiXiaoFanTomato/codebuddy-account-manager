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
        /**
         * 扣费确认（积分 / 金额 / 余额）也提示「在等主人点头」。
         *
         * 这类卡在页面代点里一律不代点（防扣费），只决定要不要提示。
         */
        var cbCreditNoticeEnabled: Boolean = true,
        /** CodeBuddy 一轮结束 / 没跑顺时提示 */
        var cbProgressNoticeEnabled: Boolean = true,
        /** 已废弃，仅用于迁移到 [cbApprovalNormal]；新代码不要再读 */
        var cbAutoApproveEnabled: Boolean = false,
        /** 超时放行的等待分钟数（页面代点通道用） */
        var cbAutoApproveMinutes: Int = 5,
        /** 已废弃，仅用于迁移到 [cbApprovalMode] / [cbApprovalNormal] */
        var cbNoDisturbEnabled: Boolean = false,
        /** 已废弃，仅用于迁移到 [cbApprovalDanger] */
        var cbNoDisturbDangerEnabled: Boolean = false,
        /** 异常中断会话自动继续：页面 / 日志判定中断后点「继续 / 重试」 */
        var cbAutoContinueEnabled: Boolean = false,
        /** 已废弃，仅用于迁移到 [cbApprovalMode]；新代码判断通道请用 [cbApprovalMode] */
        var cbHookApproveEnabled: Boolean = false,
        /**
         * hook 免打扰：不弹窗直接允许（危险命令仍交回 CodeBuddy）。
         *
         * 已废弃，仅用于迁移到 [cbApprovalNormal]；新代码不要再读。
         */
        var cbHookNoDisturbEnabled: Boolean = false,
        /**
         * 桌宠气泡等待主人裁决的秒数。
         *
         * 已废弃，仅用于迁移到 [cbApprovalTimeoutSeconds]。
         */
        var cbHookApproveSeconds: Int = 45,
        /**
         * 确认处理方式（[CbApprovalMode] 名称），三选一，取代原来并行的两套开关。
         */
        var cbApprovalMode: String = CbApprovalMode.NONE.name,
        /** 普通命令策略（[CbNormalPolicy] 名称） */
        var cbApprovalNormal: String = CbNormalPolicy.ASK.name,
        /** 危险命令策略（[CbDangerPolicy] 名称），两个通道共用 */
        var cbApprovalDanger: String = CbDangerPolicy.ASK.name,
        /**
         * 危险命令按类别配置的策略（`DELETE=ASK;GIT_DESTRUCTIVE=ALLOW` 形式）。
         *
         * 空串表示还没配过，读取时回落到 [DEFAULT_RISK_POLICY]。
         * 只对 hook（桌宠弹窗）通道生效：页面代点通道拿不到命令原文，无法分类。
         */
        var cbRiskRules: String = "",
        /** 静默运行到期时间戳（epoch millis，0 表示未开启） */
        var cbSilentUntil: Long = 0L,
        /** 开启静默时所在 IDE 进程的令牌；重启后对不上即自动失效 */
        var cbSilentBoot: String = "",
        /** 静默是否连凭证 / 扣费红线一起放行（默认否） */
        var cbSilentAllowRedline: Boolean = false,
        /**
         * 是否同意插件改写宿主 `~/.codebuddy/settings.json`（[CbHookConsent] 名称）。
         *
         * 宿主配置是主人自己的文件，首次写入前必须问一句；拒绝过就不再自动写，
         * 只能从看板按钮重新发起。
         */
        var cbHookConsent: String = CbHookConsent.UNKNOWN.name,
        /** 桌宠弹窗等待秒数（仅桌宠弹窗模式使用） */
        var cbApprovalTimeoutSeconds: Int = 45,
        /** 设置迁移版本号：0 表示还没把旧开关折算成新的三组策略 */
        var cbApprovalMigrated: Int = 0,
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
        myState.cbApprovalTimeoutSeconds =
            myState.cbApprovalTimeoutSeconds.coerceIn(MIN_HOOK_SECONDS, MAX_HOOK_SECONDS)
        migrateApprovalSettings()
    }

    /**
     * 旧配置一次性折算成新的三组策略。
     *
     * 旧模型是「页面代点」与「桌宠 hook」两套开关并行、危险命令另有独立开关，
     * 彼此会打架；新模型只留「处理方式 / 普通命令 / 危险命令」三项。
     * 迁移只跑一次（[State.cbApprovalMigrated]），旧字段保留只为读到这里。
     *
     * **注意**：这里用 1 号版本判断，[migrateRiskRules] 用 2 号，两者互相独立。
     * 合成一个版本号会让老用户升级后重跑旧折算、把现有设置覆盖回旧开关的值。
     */
    private fun migrateApprovalSettings() {
        val fromVersion = myState.cbApprovalMigrated
        if (fromVersion >= MIGRATION_VERSION_APPROVAL) {
            migrateRiskRules(fromVersion)
            return
        }
        myState.cbApprovalMigrated = MIGRATION_VERSION_APPROVAL

        myState.cbApprovalMode = when {
            myState.cbHookApproveEnabled -> CbApprovalMode.HOOK.name
            myState.cbNoDisturbEnabled || myState.cbAutoApproveEnabled ||
                myState.cbNoDisturbDangerEnabled -> CbApprovalMode.PAGE.name
            else -> CbApprovalMode.NONE.name
        }
        myState.cbApprovalNormal = when {
            // 两个「免打扰」语义相同，任一开着都说明主人想直接放行
            myState.cbHookNoDisturbEnabled || myState.cbNoDisturbEnabled -> CbNormalPolicy.ALLOW.name
            myState.cbAutoApproveEnabled -> CbNormalPolicy.ASK_THEN_ALLOW.name
            else -> CbNormalPolicy.ASK.name
        }
        // 危险策略两个通道都可选，迁移时照搬旧开关；旧开关没开的仍收敛到永远人工
        myState.cbApprovalDanger = if (myState.cbNoDisturbDangerEnabled) {
            CbDangerPolicy.AUTO.name
        } else {
            CbDangerPolicy.ASK.name
        }
        if (myState.cbHookApproveSeconds in MIN_HOOK_SECONDS..MAX_HOOK_SECONDS) {
            myState.cbApprovalTimeoutSeconds = myState.cbHookApproveSeconds
        }
        migrateRiskRules(fromVersion)
    }

    /**
     * 把「危险命令一刀切」折算成按类别配置。
     *
     * 只做一次，且只在主人还没配过（[State.cbRiskRules] 为空）时写入，
     * 避免覆盖升级后已经手工调过的矩阵。
     *
     * @param fromVersion 迁移前的版本号：0 表示全新安装，此时**不写** cbRiskRules，
     *        让读取逻辑回落到 [DEFAULT_RISK_POLICY]（删除 / Git 默认超时放行）。
     *        如果也按旧档位折算，新用户会被写成一片 ASK，出厂默认等于没了。
     */
    private fun migrateRiskRules(fromVersion: Int) {
        if (myState.cbApprovalMigrated >= MIGRATION_VERSION_RISK) return
        myState.cbApprovalMigrated = MIGRATION_VERSION_RISK
        // 静默是一次性状态：升级 / 重启都从关闭开始
        myState.cbSilentUntil = 0L
        myState.cbSilentBoot = ""
        myState.cbSilentAllowRedline = false
        if (myState.cbRiskRules.isNotBlank()) return
        if (fromVersion < MIGRATION_VERSION_APPROVAL) return
        val legacy = when (CbDangerPolicy.of(myState.cbApprovalDanger)) {
            CbDangerPolicy.AUTO -> CbRiskPolicy.ALLOW
            CbDangerPolicy.ASK_THEN_ALLOW -> CbRiskPolicy.ASK_THEN_ALLOW
            CbDangerPolicy.ASK -> CbRiskPolicy.ASK
        }
        myState.cbRiskRules = CONFIGURABLE_RISK_KINDS.joinToString(";") { "${it.name}=${legacy.name}" }
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

    /** 扣费确认（积分 / 金额 / 余额）是否也提示；这类卡永远不代点 */
    var cbCreditNoticeEnabled: Boolean
        get() = myState.cbCreditNoticeEnabled
        set(value) {
            myState.cbCreditNoticeEnabled = value
        }

    /** CodeBuddy 轮次结束 / 异常时是否提示 */
    var cbProgressNoticeEnabled: Boolean
        get() = myState.cbProgressNoticeEnabled
        set(value) {
            myState.cbProgressNoticeEnabled = value
        }

    /** 超时放行的等待分钟数（页面代点通道用），收敛到 1~30 */
    var cbAutoApproveMinutes: Int
        get() = myState.cbAutoApproveMinutes.coerceIn(MIN_AUTO_APPROVE_MINUTES, MAX_AUTO_APPROVE_MINUTES)
        set(value) {
            myState.cbAutoApproveMinutes = value.coerceIn(MIN_AUTO_APPROVE_MINUTES, MAX_AUTO_APPROVE_MINUTES)
        }

    /** 异常中断会话自动继续 */
    var cbAutoContinueEnabled: Boolean
        get() = myState.cbAutoContinueEnabled
        set(value) { myState.cbAutoContinueEnabled = value }

    /** 确认处理方式：不干预 / 页面代点 / 桌宠弹窗（三者互斥） */
    var cbApprovalMode: CbApprovalMode
        get() = CbApprovalMode.of(myState.cbApprovalMode)
        set(value) {
            myState.cbApprovalMode = value.name
        }

    /** 普通命令策略：每次都问 / 问完超时放行 / 直接放行 */
    var cbApprovalNormal: CbNormalPolicy
        get() = CbNormalPolicy.of(myState.cbApprovalNormal)
        set(value) {
            myState.cbApprovalNormal = value.name
        }

    /**
     * 危险命令策略：一直等人工 / 超时放行 / 自动放行，两个通道共用。
     *
     * 曾经在桌宠弹窗模式下强制 [CbDangerPolicy.ASK]，理由是 hook 超时只会交回原生确认框；
     * 现在放开由主人自己选：选超时放行或自动放行时由桌宠代办并显式提示，
     * 凭证 / 扣费 / 对外分发类调用仍然永远人工（见 [HookApprovalRules.isSensitive]）。
     */
    var cbApprovalDanger: CbDangerPolicy
        get() = CbDangerPolicy.of(myState.cbApprovalDanger)
        set(value) {
            myState.cbApprovalDanger = value.name
        }

    /** 某个风险类别当前生效的策略；红线类恒为 [CbRiskPolicy.ASK] */
    fun riskPolicyOf(kind: CbRiskKind): CbRiskPolicy {
        if (!kind.configurable) return CbRiskPolicy.ASK
        val configured = parseRiskRules(myState.cbRiskRules)[kind]
        return configured ?: DEFAULT_RISK_POLICY[kind] ?: CbRiskPolicy.ASK
    }

    /** 改某一类的策略；红线类直接忽略（UI 也不允许改） */
    fun setRiskPolicy(kind: CbRiskKind, policy: CbRiskPolicy) {
        if (!kind.configurable) return
        val map = parseRiskRules(myState.cbRiskRules).toMutableMap()
        map[kind] = policy
        myState.cbRiskRules = CONFIGURABLE_RISK_KINDS.joinToString(";") { k ->
            "${k.name}=${(map[k] ?: DEFAULT_RISK_POLICY[k] ?: CbRiskPolicy.ASK).name}"
        }
    }

    /** 静默运行到期时间戳（epoch millis，0 表示未开启） */
    var cbSilentUntil: Long
        get() = myState.cbSilentUntil
        set(value) { myState.cbSilentUntil = value }

    /** 开启静默时所在进程的令牌 */
    var cbSilentBoot: String
        get() = myState.cbSilentBoot
        set(value) { myState.cbSilentBoot = value }

    /** 静默是否连凭证 / 扣费一起放行 */
    var cbSilentAllowRedline: Boolean
        get() = myState.cbSilentAllowRedline
        set(value) { myState.cbSilentAllowRedline = value }

    /** 解析 `KIND=POLICY;...`；无法识别的项直接丢弃，不让坏配置卡住判定 */
    private fun parseRiskRules(raw: String): Map<CbRiskKind, CbRiskPolicy> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<CbRiskKind, CbRiskPolicy>()
        for (part in raw.split(';')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) continue
            val kind = CbRiskKind.values().firstOrNull { it.name == pair[0].trim() } ?: continue
            val policy = CbRiskPolicy.values().firstOrNull { it.name == pair[1].trim() } ?: continue
            out[kind] = policy
        }
        return out
    }

    /** 桌宠弹窗等待秒数，收敛到 5~80（必须留出余量给 hook 自身的 55 秒上限） */
    var cbApprovalTimeoutSeconds: Int
        get() = myState.cbApprovalTimeoutSeconds.coerceIn(MIN_HOOK_SECONDS, MAX_HOOK_SECONDS)
        set(value) {
            myState.cbApprovalTimeoutSeconds = value.coerceIn(MIN_HOOK_SECONDS, MAX_HOOK_SECONDS)
        }

    /**
     * 是否同意改写宿主配置（写入 / 移除 PreToolUse hook）。
     *
     * 只有 [CbHookConsent.APPROVED] 才会在同步时自动写；[CbHookConsent.DECLINED]
     * 之后不再自动写，改由看板「启用桌宠弹窗」按钮重新询问。
     */
    var cbHookConsent: CbHookConsent
        get() = CbHookConsent.of(myState.cbHookConsent)
        set(value) {
            myState.cbHookConsent = value.name
        }

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

        /** 桌宠弹窗等待秒数范围 */
        const val MIN_HOOK_SECONDS = 5
        const val MAX_HOOK_SECONDS = 80

        /** 确认策略迁移版本 1：旧并行开关 → 处理方式 / 普通 / 危险三档 */
        private const val MIGRATION_VERSION_APPROVAL = 1

        /** 确认策略迁移版本 2：危险命令一刀切 → 按类别配置 + 静默字段初始化 */
        private const val MIGRATION_VERSION_RISK = 2

        /**
         * 可配置的风险类别（红线两类不在其中，UI 只读展示）。
         *
         * 「普通」排第一：它是主人最常调的一项（想不想让日常命令打扰），
         * 后面按危险程度从高到低排。
         */
        val CONFIGURABLE_RISK_KINDS: List<CbRiskKind> =
            listOf(CbRiskKind.NORMAL) + CbRiskKind.values()
                .filter { it.configurable && it != CbRiskKind.NORMAL }
                .sortedBy { it.severity }

        /**
         * 各类别出厂默认策略。
         *
         * 删除与 Git 破坏给「超时放行」：误删通常能从备份或 reflog 恢复，卡得太死反而天天打断；
         * 提权 / 系统 / 脚本 / 外发一律「一直等」，这几类出错不可回滚。
         */
        val DEFAULT_RISK_POLICY: Map<CbRiskKind, CbRiskPolicy> = mapOf(
            CbRiskKind.DELETE to CbRiskPolicy.ASK_THEN_ALLOW,
            CbRiskKind.GIT_DESTRUCTIVE to CbRiskPolicy.ASK_THEN_ALLOW,
            CbRiskKind.SYSTEM to CbRiskPolicy.ASK,
            CbRiskKind.PRIVILEGE to CbRiskPolicy.ASK,
            CbRiskKind.SCRIPT to CbRiskPolicy.ASK,
            CbRiskKind.EGRESS to CbRiskPolicy.ASK,
        )

        /** 未设置坐标的哨兵值 */
        private const val NO_POSITION = Int.MIN_VALUE

        fun getInstance(): PetSettings =
            ApplicationManager.getApplication().getService(PetSettings::class.java)
    }
}

/**
 * CodeBuddy 确认处理方式，三选一。
 *
 * 原来「页面代点」和「桌宠 hook」是两套并行开关，会同时生效、互相打架；
 * 收敛成单选之后，任一时刻只有一个通道负责决策。
 */
enum class CbApprovalMode(val label: String, val hint: String) {
    NONE("不干预", "只用桌宠提醒，确认仍由 CodeBuddy 原生弹框完成"),
    PAGE("页面代点", "观察聊天面板的「允许 / 拒绝」决策组，自动点「允许」"),
    HOOK("桌宠弹窗", "走 CodeBuddy hook 通道，由桌宠气泡给「同意 / 取消」"),
    ;

    companion object {
        fun of(name: String?): CbApprovalMode = CbApprovalMode.values().firstOrNull { it.name == name } ?: NONE
    }
}

/** 普通命令策略（两个通道共用同一套语义） */
enum class CbNormalPolicy(val label: String) {
    ASK("每次都问"),
    ASK_THEN_ALLOW("问，超时没人管就放行"),
    ALLOW("直接放行"),
    ;

    companion object {
        fun of(name: String?): CbNormalPolicy = CbNormalPolicy.values().firstOrNull { it.name == name } ?: ASK
    }
}

/**
 * 危险命令策略，两个通道共用。
 *
 * 三档语义与 [CbNormalPolicy] 对齐：[ASK] 一直等主人点，[ASK_THEN_ALLOW] 等超时就放行，
 * [AUTO] 直接放行。[AUTO] / [ASK_THEN_ALLOW] 只放开「破坏性 / 提权」一类；
 * 凭证、扣费、对外分发不在此列，永远人工。
 */
enum class CbDangerPolicy(val label: String) {
    ASK("问，一直等待主人确认"),
    ASK_THEN_ALLOW("问，超时没人管就放行"),
    AUTO("自动放行（含危险命令）"),
    ;

    companion object {
        fun of(name: String?): CbDangerPolicy = CbDangerPolicy.values().firstOrNull { it.name == name } ?: ASK
    }
}

/**
 * 单个风险类别的处置策略，只用于 hook（桌宠弹窗）通道。
 *
 * 为什么页面代点通道不用它：页面通道只能看到确认卡上的文案，拿不到命令原文，
 * 物理上无法区分「删除」和「提权」，所以仍沿用 [CbDangerPolicy] 的三档。
 */
enum class CbRiskPolicy(val label: String, val hint: String) {
    ASK("问，一直等主人确认", "气泡常驻不代点，超时交回 CodeBuddy 原生确认框"),
    ASK_THEN_ALLOW("问，超时没人管就放行", "气泡倒计时，超时后由桌宠代办并留审计"),
    ALLOW("直接放行", "不弹窗直接允许，逐条记审计"),
    ;

    companion object {
        fun of(name: String?): CbRiskPolicy =
            CbRiskPolicy.values().firstOrNull { it.name == name } ?: ASK
    }
}

/**
 * 是否允许插件改写宿主 CodeBuddy 的 `~/.codebuddy/settings.json`。
 *
 * 这个文件不属于插件，首次写入（加 PreToolUse hook）必须先征得同意：
 * - [UNKNOWN] 还没问过：需要写入时弹一次确认；
 * - [APPROVED] 已同意：之后同步直接写，写入前自动备份；
 * - [DECLINED] 已拒绝：不再自动写，只能从看板按钮重新发起。
 */
enum class CbHookConsent(val label: String) {
    UNKNOWN("还没问过"),
    APPROVED("已同意写入"),
    DECLINED("已拒绝写入"),
    ;

    companion object {
        fun of(name: String?): CbHookConsent = CbHookConsent.values().firstOrNull { it.name == name } ?: UNKNOWN
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
            PetQuadrant.values().firstOrNull { it.name == name } ?: BOTTOM_RIGHT
    }
}
