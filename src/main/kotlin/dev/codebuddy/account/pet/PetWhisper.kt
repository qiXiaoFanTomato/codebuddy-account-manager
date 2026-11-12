package dev.codebuddy.account.pet

import dev.codebuddy.account.usage.UsageTodayTracker
import dev.codebuddy.account.usage.formatCredit
import kotlin.math.roundToInt

/**
 * 桌宠的碎碎念台词与配图。
 *
 * 人设：这只桌宠就是插件里的助手本人——自称「我」，称用户「主人」，
 * 说话短、直给、带技术味，不卖萌也不装可爱。
 *
 * 台词全部是本地静态文本，不调用任何模型（插件本体没有 LLM 通道）。
 * 配图取原项目 `assets/memes` 的 27 张表情包（编号 m01–m27，见 [MemeNames]）。
 *
 * 双击积分读数的彩蛋单独走轻松活泼口径：统计 / 鼓励 / 调侃随机切换，见 [quotaTapLine]。
 */
object PetWhisper {

    /** 一句台词 + 配图 id */
    data class Line(val text: String, val meme: String?)

    /** 双击反馈的配套动作，由窗口层映射到具体动画 */
    enum class TapMotion { CHEER, WHISPER, GUILTY, RANDOM, BALANCE }

    /** 双击反馈：一句文案 + 配图 + 配套动作 */
    data class TapLine(val text: String, val meme: String?, val motion: TapMotion)

    /** 台词里代表变化量的占位符 */
    private const val DELTA_TOKEN = "{delta}"

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
        Line("双击我，随机说点积分小八卦。", "m07"),
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

    /**
     * 额度减少时的台词：`{delta}` 会替换成本次变化量（已带 `−`）。
     *
     * 口吻是「记账 + 心疼但不说」，不做过度卖惨，也不催着充值。
     */
    private val QUOTA_DOWN = listOf(
        Line("又跑了一轮，{delta}，记我账上了。", "m06"),
        Line("扣了 {delta}，还在安全线内，别慌。", "m05"),
        Line("{delta} 换回来一段能跑的代码，值。", "m24"),
        Line("刚出去 {delta}，主人这轮改了几遍？", "m12"),
        Line("钱包轻了 {delta}，我盯着呢。", "m06"),
        Line("消耗 {delta}，建议先跑测试再开下一轮。", "m15"),
        Line("又少了 {delta}，能省就省一点。", "m08"),
        Line("记一笔：{delta}。", "m19"),
        Line("{delta} 没了，不过离底线还远。", "m10"),
        Line("这轮花 {delta}，我心疼但不说。", "m20"),
    )

    /**
     * 额度增加时的台词（充值 / 周期重置都会走到这里）。
     *
     * 口吻是「回血 / 到账」，语气比减少时松，但不夸张庆祝。
     */
    private val QUOTA_UP = listOf(
        Line("额度回来了，{delta}，满血。", "m05"),
        Line("{delta} 到账，今天可以放开跑。", "m26"),
        Line("补上 {delta}，账本翻新一页。", "m24"),
        Line("口袋又鼓了，{delta}。", "m09"),
        Line("主人是不是充值了？{delta} 我收着。", "m02"),
        Line("刷新完成，{delta}，这下不慌了。", "m19"),
        Line("血条回满，{delta}，接着干。", "m14"),
        Line("{delta} 进账，先谢过主人。", "m16"),
    )

    /** 洗牌袋：两类各自维护，取完重洗，避免连续重复同一句 */
    private val downBag = ArrayDeque<Line>()
    private val upBag = ArrayDeque<Line>()
    private var lastDown: Line? = null
    private var lastUp: Line? = null

    /**
     * 额度变化专用台词：[deltaText] 已是带 `+` / `−` 的展示文本，[increase] 决定取哪一组。
     *
     * 与 [nextLine] 的碎碎念池完全分开：额度变化属于「事件反馈」，不能和闲聊撞句。
     */
    fun quotaLine(deltaText: String, increase: Boolean): Line {
        val pool = if (increase) QUOTA_UP else QUOTA_DOWN
        if (pool.isEmpty()) return Line("", null)
        val line = draw(if (increase) upBag else downBag, pool, increase)
        return Line(line.text.replace(DELTA_TOKEN, deltaText), line.meme)
    }

    private fun draw(bag: ArrayDeque<Line>, pool: List<Line>, increase: Boolean): Line {
        if (bag.isEmpty()) {
            val shuffled = pool.shuffled().toMutableList()
            val last = if (increase) lastUp else lastDown
            if (shuffled.size > 1 && shuffled[0] === last) {
                val head = shuffled[0]
                shuffled[0] = shuffled[1]
                shuffled[1] = head
            }
            bag.addAll(shuffled)
        }
        val line = bag.removeFirst()
        if (increase) lastUp = line else lastDown = line
        return line
    }

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

    // ---------------------------------------------------------------
    // 双击积分读数的随机反馈（统计 / 鼓励 / 调侃）
    // ---------------------------------------------------------------

    /** 文案风格：最近消耗 / 我吃了多少 / 今日统计 / 余额 / 鼓励 / 调侃 */
    private enum class TapKind { RECENT, EATEN, TODAY, BALANCE, CHEER, TEASE }

    /** 占位符由 [quotaTapLine] 按真实数据替换；每条只用本风格可用的变量 */
    private val TAP_LINES: Map<TapKind, List<TapLine>> = mapOf(
        TapKind.RECENT to listOf(
            TapLine("刚那一下花掉 {recent} 积分，主人手速挺快呀。", "m12", TapMotion.GUILTY),
            TapLine("最近一次请求 {recent} 积分，我记在小本本上了。", "m19", TapMotion.WHISPER),
            TapLine("上一轮 {recent} 积分，钱花得挺利落。", "m06", TapMotion.GUILTY),
            TapLine("喏，最近这笔 {recent} 积分，账目清清楚楚。", "m24", TapMotion.WHISPER),
            TapLine("{recent} 积分刚出门，我替它挥了挥小手。", "m17", TapMotion.RANDOM),
            TapLine("就这一下 {recent} 积分，别慌，还扛得住。", "m05", TapMotion.GUILTY),
        ),
        TapKind.EATEN to listOf(
            TapLine("报告主人，这个周期我已经吃掉 {eaten} 积分啦。", "m09", TapMotion.GUILTY),
            TapLine("{eaten} 积分进我肚子了？冤枉，是模型吃的！", "m17", TapMotion.RANDOM),
            TapLine("我吃了 {eaten} 积分，摸摸肚子，还能再来一口。", "m09", TapMotion.RANDOM),
            TapLine("本周期战绩：吃掉 {eaten} 积分，嗝～", "m08", TapMotion.GUILTY),
            TapLine("别瞪我，{eaten} 积分里没几分是我的零食。", "m18", TapMotion.RANDOM),
            TapLine("我承认，{eaten} 积分有一半是我看着它没的。", "m06", TapMotion.GUILTY),
        ),
        TapKind.TODAY to listOf(
            TapLine("今天一共消耗 {today} 积分、{count} 次请求，账本摊开给主人看。", "m19", TapMotion.WHISPER),
            TapLine("今日战报：{today} 积分 / {count} 次，节奏稳得很。", "m24", TapMotion.WHISPER),
            TapLine("从零点算到现在，{count} 次请求花掉 {today} 积分。", "m05", TapMotion.WHISPER),
            TapLine("今天 {today} 积分、{count} 次请求，主打一个高效。", "m26", TapMotion.CHEER),
            TapLine("日报速览：{today} 积分已经飞走，共 {count} 次。", "m14", TapMotion.WHISPER),
            TapLine("主人今天用掉 {today} 积分，跑了 {count} 次，辛苦啦。", "m08", TapMotion.CHEER),
        ),
        TapKind.BALANCE to listOf(
            TapLine("还剩 {remain} 积分（{percent}%），稳得住，继续冲。", "m05", TapMotion.BALANCE),
            TapLine("血条读数 {percent}%，剩 {remain} 积分，暂时不用慌。", "m14", TapMotion.BALANCE),
            TapLine("主人还有 {remain} 积分，我替主人盯着呢。", "m01", TapMotion.BALANCE),
            TapLine("余额 {remain} 积分、{percent}%，够再跑几轮。", "m26", TapMotion.BALANCE),
            TapLine("这份家底：{remain} 积分（{percent}%），省着点更香。", "m06", TapMotion.BALANCE),
        ),
        TapKind.CHEER to listOf(
            TapLine("你好棒！这波代码一次就跑通了。", "m05", TapMotion.CHEER),
            TapLine("主人今天状态在线，掌声奉上。", "m26", TapMotion.CHEER),
            TapLine("节奏拿捏得刚刚好，继续保持呀。", "m14", TapMotion.CHEER),
            TapLine("这么稳的操作，我得记一笔表扬。", "m19", TapMotion.CHEER),
            TapLine("主人厉害，连 bug 都绕着走。", "m03", TapMotion.CHEER),
            TapLine("又推进了一步，真不赖。", "m24", TapMotion.CHEER),
            TapLine("双击有奖：一句夸奖，请主人查收～", "m07", TapMotion.CHEER),
        ),
        TapKind.TEASE to listOf(
            TapLine("别双击啦，积分不会自己长回来的。", "m20", TapMotion.RANDOM),
            TapLine("再点我可要收咨询费了，收积分那种。", "m18", TapMotion.RANDOM),
            TapLine("摸鱼被我抓到了？没事，我也在摸。", "m13", TapMotion.RANDOM),
            TapLine("积分不是我吃的，我只是负责背锅的。", "m06", TapMotion.GUILTY),
            TapLine("主人这双击频率，是在测我反应速度吗？", "m12", TapMotion.RANDOM),
            TapLine("要不要来点更狠的？比如……再写个需求。", "m23", TapMotion.RANDOM),
            TapLine("我在这儿当值，顺便替积分默哀一秒。", "m21", TapMotion.RANDOM),
        ),
    )

    private val kindBag = ArrayDeque<TapKind>()
    private var lastKind: TapKind? = null
    private val lineBags = mutableMapOf<TapKind, ArrayDeque<TapLine>>()
    private val lastLines = mutableMapOf<TapKind, TapLine>()

    /**
     * 双击积分读数：先洗牌抽一种风格，再从该风格里洗牌抽一句。
     *
     * 数据不可用的风格（今日消耗未加载、额度查询失败）自动跳过，
     * 只回退到鼓励 / 调侃，不展示空数字，也不编造统计口径。
     */
    fun quotaTapLine(data: PetData, today: UsageTodayTracker.State): TapLine {
        val quota = data.quota?.takeIf { it.error == null && it.usableSize > 0 }
        val todayReady = today.loaded && today.accountCount > 0
        val recent = if (todayReady) today.latest(1).firstOrNull() else null

        val vars = mutableMapOf<String, String>()
        val kinds = mutableListOf(TapKind.CHEER, TapKind.TEASE)
        if (recent != null) {
            vars["recent"] = formatCredit(recent.credit)
            kinds += TapKind.RECENT
        }
        if (quota != null) {
            vars["eaten"] = formatCredit(quota.usableUsed)
            vars["remain"] = formatCredit(quota.usableRemain)
            vars["percent"] = quota.usableRemainPercent.roundToInt().toString()
            kinds += TapKind.EATEN
            kinds += TapKind.BALANCE
        }
        if (todayReady) {
            vars["today"] = formatCredit(today.totalCredit)
            vars["count"] = today.totalRequests.toString()
            kinds += TapKind.TODAY
        }

        val line = drawTapLine(drawTapKind(kinds))
        return line.copy(text = vars.entries.fold(line.text) { acc, (key, value) ->
            acc.replace("{$key}", value)
        })
    }

    private fun drawTapKind(pool: List<TapKind>): TapKind {
        // 数据可用性会随刷新变化，先滤掉当前不可用的风格
        kindBag.retainAll(pool.toSet())
        return drawTap(kindBag, pool, lastKind).also { lastKind = it }
    }

    private fun drawTapLine(kind: TapKind): TapLine {
        val bag = lineBags.getOrPut(kind) { ArrayDeque() }
        return drawTap(bag, TAP_LINES.getValue(kind), lastLines[kind]).also { lastLines[kind] = it }
    }

    /** 洗牌袋：一轮内不重复，换轮时也不与上一句 / 上一类连着撞 */
    private fun <T> drawTap(bag: ArrayDeque<T>, pool: List<T>, last: T?): T {
        if (bag.isEmpty()) {
            val shuffled = pool.shuffled().toMutableList()
            if (shuffled.size > 1 && shuffled[0] == last) {
                val head = shuffled[0]
                shuffled[0] = shuffled[1]
                shuffled[1] = head
            }
            bag.addAll(shuffled)
        }
        if (bag.size > 1 && bag.first() == last) {
            val head = bag.removeFirst()
            val second = bag.removeFirst()
            bag.addFirst(head)
            return second
        }
        return bag.removeFirst()
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
