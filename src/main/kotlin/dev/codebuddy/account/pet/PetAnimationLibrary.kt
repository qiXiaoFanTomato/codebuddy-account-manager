package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.awt.Rectangle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 原版动画素材库：把 `config.jsonc` 里的动画名映射为随包资源。
 *
 * 对应关系（与原项目 `animations` 一致）：
 * - `idle`         ↔ 待机呼吸休闲
 * - `click1..5`    ↔ 点击回应（开心跃动 / 害羞惊讶 / 傲娇生气 / 挠痒咯咯笑 / 元气挥手）
 * - `balance0..5`  ↔ 余额六档（按已用百分比分档：0-20 / 20-40 / 40-60 / 60-80 / 80-100 / 100）
 * - `sleep_start` / `sleep_loop` / `sleep_end` ↔ 睡觉-开始 / 循环 / 起来
 *
 * 素材来自 dsh-pet（代码 MIT、素材禁止商用、公开渠道二创须署名），
 * 由本地转换脚本从 VP9-alpha 的 webm 转为 `.petanim`（JPEG 颜色层 + 灰度掩码）。
 */
object PetAnimationLibrary {

    const val IDLE = "idle"
    val CLICKS = listOf("click1", "click2", "click3", "click4", "click5")
    val BALANCE = listOf("balance0", "balance1", "balance2", "balance3", "balance4", "balance5")
    const val SLEEP_START = "sleep_start"
    const val SLEEP_LOOP = "sleep_loop"
    const val SLEEP_END = "sleep_end"

    /** 被鼠标抓起悬空时的姿势（原项目 `drag` 池） */
    const val DRAG = "drag"

    /** 在屏幕上跑动 / 走位的动画（原项目 `moves.actions` 池） */
    val MOVES = listOf("move1", "move2", "move3")

    /** 随机取一个可用的走位动画；素材缺失时返回 null（走位也走洗牌袋，避免连着跑同一段） */
    fun randomMoveId(): String? = nextFromBag("moves", MOVES)

    /** 原项目分类权重（小动作 20 / 玩耍 20 / 吃什么 16 / 时节 14 / 文字 10） */
    private val CATEGORY_WEIGHTS = mapOf(
        "小动作" to 20,
        "玩耍" to 20,
        "吃什么" to 16,
        "时节" to 14,
        "文字" to 10,
    )

    /**
     * 提示动作分组：同一等级内轮流使用，避免每次提示都同一个动作。
     *
     * 等级 → 候选动画（第一项是语义最贴切的，后面是补充表现）。
     */
    private val NOTICE_GROUPS: Map<PetNotifier.Level, List<String>> = mapOf(
        // 成功：开心跃动 / 跳起来抓碎头顶物品 / 可爱宅舞 / 收红包
        PetNotifier.Level.SUCCESS to listOf("click1", "act22", "act29", "act67"),
        // 信息：元气挥手 / 悠闲哼歌 / 照镜子 / 撸猫
        PetNotifier.Level.INFO to listOf("click5", "act01", "act13", "act39"),
        // 警告：害羞惊讶 / 被吓一跳 / 打瞌睡被惊醒 / 哈欠连天
        PetNotifier.Level.WARN to listOf("click2", "act08", "act12", "act05"),
        // 错误：傲娇生气 / 玩游戏气急败坏 / 尾巴拍地 / 偷吃零食被抓住
        PetNotifier.Level.ERROR to listOf("click3", "act23", "act11", "act10"),
    )

    /** 额度减少时的反应动作（心虚 / 心疼 / 装没事） */
    private val QUOTA_DOWN_ACTIONS = listOf("act48", "click2", "act10", "act05")

    /** 对话完成时的庆祝动作：跳跃 / 放烟花 / 宅舞 / 收红包 / 开心跃动 */
    private val ROUND_FINISH_ACTIONS = listOf("act22", "act61", "act29", "act67", "click1")

    /** 洗牌袋：key → 本轮还没用过的候选；用完再重洗，保证每段动画都能轮到 */
    private val bags = HashMap<String, ArrayDeque<String>>()
    private val bagLock = Any()
    private val lastPicked = HashMap<String, String>()

    /**
     * 洗牌袋抽取：把候选打乱后依次取，取完重洗，并避免新一轮首个与上一轮末尾重复。
     *
     * 比纯随机更均匀：纯随机会出现「连着三次同一段」，洗牌袋保证一轮内不重复。
     */
    private fun nextFromBag(key: String, candidates: List<String>): String? {
        val usable = candidates.filter { it in availableNames }
        if (usable.isEmpty()) return null
        synchronized(bagLock) {
            val bag = bags.getOrPut(key) { ArrayDeque() }
            if (bag.isEmpty()) {
                val shuffled = usable.shuffled().toMutableList()
                val last = lastPicked[key]
                if (shuffled.size > 1 && shuffled.first() == last) {
                    shuffled.add(shuffled.removeAt(0))
                }
                bag.addAll(shuffled)
            }
            val picked = bag.removeFirst()
            lastPicked[key] = picked
            return picked
        }
    }

    private const val DIR = "/pet/anim/"

    /** 同时驻留内存的动画段数上限 */
    private const val CACHE_ANIMATIONS = 3

    private val log = logger<PetAnimationLibrary>()

    /** manifest 里的一条随机动作 */
    data class ActionEntry(val id: String, val name: String, val category: String)

    /** 素材清单：动作列表 + 碎碎念动画 */
    data class Manifest(
        val actions: List<ActionEntry> = emptyList(),
        val whisper: List<String> = emptyList(),
    )

    val manifest: Manifest by lazy { loadManifest() }

    private val allNames: List<String> by lazy {
        listOf(IDLE) + CLICKS + BALANCE + MOVES +
            listOf(SLEEP_START, SLEEP_LOOP, SLEEP_END, DRAG) +
            manifest.actions.map { it.id } + manifest.whisper
    }

    /** 实际打包进 jar 的动画名（清单里写了但素材缺失的会被排除） */
    val availableNames: Set<String> by lazy { allNames.filter { hasResource(it) }.toSet() }

    /** 可用的碎碎念动画 */
    val whisperIds: List<String> by lazy { manifest.whisper.filter { it in availableNames } }

    /** 可用的随机动作条数（用于设置页展示） */
    val randomActionCount: Int by lazy { manifest.actions.count { it.id in availableNames } }

    /**
     * 按分类权重抽一个随机动作 id。
     *
     * 抽法与原项目一致：先按分类权重决定落在哪个分类，再在该分类内等概率抽一个动作。
     */
    fun randomActionId(): String? {
        val actions = manifest.actions.filter { it.id in availableNames }
        if (actions.isEmpty()) return nextFromBag("fallbackClicks", CLICKS)

        val groups = actions.groupBy { it.category }
        val total = groups.keys.sumOf { CATEGORY_WEIGHTS[it] ?: 10 }
        var roll = Math.random() * total
        for ((category, list) in groups) {
            val weight = CATEGORY_WEIGHTS[category] ?: 10
            if (roll < weight) {
                // 命中分类后用洗牌袋在该分类内轮转，保证组内每段都轮到
                nextFromBag("cat:$category", list.map { it.id })?.let { return it }
                break
            }
            roll -= weight
        }
        return nextFromBag("cat:any", actions.map { it.id })
    }

    /**
     * 系统提示等级 → 桌宠动作（同等级在 [NOTICE_GROUPS] 内洗牌轮转，不再固定一个）。
     */
    fun noticeAnimation(level: PetNotifier.Level): String? =
        nextFromBag("notice:" + level.name, NOTICE_GROUPS[level] ?: CLICKS)

    /** 对话完成专用动作（同组洗牌轮转，避免每次都是同一段） */
    fun roundFinishAnimation(): String? = nextFromBag("roundFinish", ROUND_FINISH_ACTIONS)

    /** 额度减少时的反应动作（同样洗牌轮转，避免每次都同一个表情） */
    fun quotaDownReactionId(): String? = nextFromBag("quotaDown", QUOTA_DOWN_ACTIONS)

    /** 笑话内容里的关键词 → 动作分类；在线英文笑话与内置中文笑话都能命中 */
    private val JOKE_CATEGORY_KEYWORDS = listOf(
        "吃什么" to listOf(
            "吃", "饭", "面", "咖啡", "零食", "蛋糕", "包子",
            "eat", "food", "coffee", "pizza", "beer", "mushroom",
        ),
        "玩耍" to listOf(
            "跑", "跳", "健身", "球", "游戏", "玩", "木马", "秋千",
            "run", "jump", "play", "game", "party", "dance",
        ),
        "文字" to listOf(
            "代码", "bug", "sql", "程序", "编译", "测试", "接口", "缓存", "递归",
            "服务器", "键盘", "数组", "日志", "需求", "文档", "机器人",
            "code", "program", "test", "server", "compiler", "recursion", "cache", "data",
        ),
        "小动作" to listOf("笑", "脸", "镜子", "手", "laugh", "mirror", "smile"),
    )

    /** 没命中关键词时用的「开心 / 咯咯笑」动作组 */
    private val JOKE_FALLBACK_ACTIONS = listOf("click4", "click1", "act22", "click5", "act29")

    /**
     * 讲笑话抖包袱时的对应动作：按内容关键词挑同分类的动作，
     * 没命中就在开心动作组里轮转。动作缺失由 [nextFromBag] 过滤。
     */
    fun jokeActionId(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        for ((category, keywords) in JOKE_CATEGORY_KEYWORDS) {
            if (keywords.none { lower.contains(it) }) continue
            val ids = manifest.actions
                .filter { it.category == category && it.id in availableNames }
                .map { it.id }
            if (ids.isNotEmpty()) return nextFromBag("joke:$category", ids)
        }
        return nextFromBag("joke:fallback", JOKE_FALLBACK_ACTIONS)
    }

    private fun hasResource(name: String): Boolean =
        PetAnimationLibrary::class.java.getResource(DIR + name + ".petanim") != null

    private fun loadManifest(): Manifest {
        return try {
            val stream = PetAnimationLibrary::class.java.getResourceAsStream(DIR + "manifest.json")
                ?: return Manifest()
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            val actions = root.getAsJsonArray("actions")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                ActionEntry(
                    id = id,
                    name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: id,
                    category = o.get("category")?.takeIf { !it.isJsonNull }?.asString ?: "",
                )
            } ?: emptyList()
            val whisper = root.getAsJsonArray("whisper")?.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                el.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString
            } ?: emptyList()
            Manifest(actions, whisper)
        } catch (e: Exception) {
            log.warn("读取动画清单失败，随机动作退回点击回应", e)
            Manifest()
        }
    }

    /**
     * 已加载的动画容器。
     *
     * 每段素材约 3.5–4.5MB（压缩字节全量驻留），15 段全留会吃掉约 58MB 堆，
     * 因此只保留最近使用的 3 段；待机每秒被取 25 次，必然是 MRU，不会被挤掉。
     */
    private val cache = object : LinkedHashMap<String, PetAnimation>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PetAnimation>): Boolean =
            size > CACHE_ANIMATIONS
    }

    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * 全部动画在 462×260 原画框中的并集区域。
     *
     * 各动画的裁剪框大小不同（余额动画会画出钱袋、烟花等外围元素），
     * 用并集做窗口尺寸，切换动画时角色才不会跳位。
     */
    val bounds: Rectangle by lazy { computeBounds() }

    fun get(name: String): PetAnimation? {
        synchronized(cache) {
            cache[name]?.let { return it }
        }
        if (name in failed) return null
        val loaded = try {
            PetAnimationLibrary::class.java.getResourceAsStream(DIR + name + ".petanim")
                ?.let { PetAnimation.load(name, it) }
        } catch (e: Exception) {
            log.warn("读取动画资源失败: $name", e)
            null
        }
        if (loaded == null) {
            failed.add(name)
            return null
        }
        synchronized(cache) { cache[name] = loaded }
        return loaded
    }

    /** 素材是否齐备（缺任何一个都退回自绘形象） */
    fun isAvailable(): Boolean = get(IDLE) != null

    private fun computeBounds(): Rectangle {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (name in allNames) {
            val header = try {
                PetAnimationLibrary::class.java.getResourceAsStream(DIR + name + ".petanim")
                    ?.let { PetAnimation.readHeader(name, it) }
            } catch (_: Exception) {
                null
            } ?: continue
            minX = minOf(minX, header.offsetX)
            minY = minOf(minY, header.offsetY)
            maxX = maxOf(maxX, header.offsetX + header.width)
            maxY = maxOf(maxY, header.offsetY + header.height)
        }
        if (minX == Int.MAX_VALUE) {
            log.info("未找到动画素材，桌宠退回自绘形象")
            return Rectangle(0, 0, 0, 0)
        }
        return Rectangle(minX, minY, maxX - minX, maxY - minY)
    }
}
