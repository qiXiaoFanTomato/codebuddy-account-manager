package dev.codebuddy.account.pet

/**
 * 桌宠的碎碎念台词与配图。
 *
 * 人设：这只桌宠就是插件里的助手本人——自称「我」，称用户「主人」，
 * 说话短、直给、带技术味，不卖萌也不装可爱。
 *
 * 台词全部是本地静态文本，不调用任何模型（插件本体没有 LLM 通道）。
 * 配图取原项目 `assets/memes` 的 27 张表情包（编号 m01–m27，见 [MemeNames]）。
 */
object PetWhisper {

    /** 一句台词 + 配图 id */
    data class Line(val text: String, val meme: String?)

    private val SCRIPT = listOf(
        Line("主人，额度我看过了，还够用。", "m05"),
        Line("写代码记得先跑一遍再提交。", "m24"),
        Line("这个报错我见过，八成是空引用。", "m12"),
        Line("歇一会儿吧，肩膀别硬撑。", "m09"),
        Line("刚算了一下，剩余额度掉了一点。", "m06"),
        Line("要不要我把上次的对话翻出来？", "m04"),
        Line("测试没过就先别急着合并。", "m15"),
        Line("桌面有点乱，我帮你盯着。", "m11"),
        Line("SQL 记得加 WHERE，别手滑。", "m23"),
        Line("这段逻辑可以再拆小一点。", "m25"),
        Line("我在这儿当值，有事敲我一下。", "m01"),
        Line("别把 token 贴进聊天里。", "m02"),
        Line("咖啡续一杯，代码还要跑一会儿。", "m17"),
        Line("这个 PR 我建议单独提。", "m03"),
        Line("深夜改代码容易出错，先提交一版吧。", "m21"),
        Line("主人，进度推到哪了？", "m14"),
        Line("别死磕，先跑起来再说。", "m20"),
        Line("我没偷懒，我在盯构建。", "m13"),
        Line("日志里那条 warn 值得看一眼。", "m10"),
        Line("双击我，能看额度和上一轮消耗。", "m07"),
        Line("拖我一下试试，我会弹回来的。", "m18"),
        Line("主人，记得喝水。", "m08"),
        Line("这套配色是我挑的，冷蓝灰耐看。", "m05"),
        Line("需求先写清楚，省得返工。", "m16"),
        Line("先复现，再谈修复。", "m27"),
        Line("提交信息写清楚，方便以后翻。", "m24"),
        Line("别慌，一步一步来。", "m10"),
        Line("接口字段对齐了吗？前后端别打架。", "m11"),
        Line("我把素材全下载好了，动起来还挺像样。", "m19"),
        Line("今天也要顺顺利利地跑通。", "m26"),
    )

    private var lastIndex = -1

    /** 随机取一句（连续两次不会重复），配图随台词固定映射 */
    fun nextLine(): Line {
        if (SCRIPT.isEmpty()) return Line("", null)
        if (SCRIPT.size == 1) return SCRIPT.first()
        var index = SCRIPT.indices.random()
        var guard = 0
        while (index == lastIndex && guard < 5) {
            index = SCRIPT.indices.random()
            guard++
        }
        lastIndex = index
        return SCRIPT[index]
    }

    /** 表情包编号与原文件名的对照（用于文档与排查，不在界面上展示） */
    val MemeNames: Map<String, String> = linkedMapOf(
        "m01" to "Ciallo",
        "m02" to "token给妈妈保管",
        "m03" to "假如给我三天多模态",
        "m04" to "先养着吧",
        "m05" to "厉害了我的鲸",
        "m06" to "压力一只大肥鱼",
        "m07" to "可爱",
        "m08" to "吃白饭的大肥鱼",
        "m09" to "吃饱饱摸肚子",
        "m10" to "哦鲸鲸",
        "m11" to "哪来的电脑病毒",
        "m12" to "大师这个区是什么意思",
        "m13" to "大烧货",
        "m14" to "大的药来了",
        "m15" to "大的要来啦",
        "m16" to "完蛋了把黄油删了",
        "m17" to "就一碗",
        "m18" to "就骚了怎么滴吧",
        "m19" to "我不更新你们用什么",
        "m20" to "死掉了",
        "m21" to "深度睡眠",
        "m22" to "电死你",
        "m23" to "要不直接骂他",
        "m24" to "誓死践行开源精神",
        "m25" to "除了调情没啥用",
        "m26" to "鱼片有资源吗",
        "m27" to "黎曼猜想没证明喵",
    )
}
