package dev.codebuddy.account.pet

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 讲笑话：优先取在线中文冷笑话，失败时回退内置中文笑话。
 *
 * 在线接口（2026-11-11 实测可用，开源项目 vikiboss/60s，免 API Key）：
 *   GET https://60s.viki.moe/v2/dad-joke
 *   {"code":200,"data":{"index":43,"content":"..."}}
 * 文档：https://docs.60s-api.viki.moe/342192632e0
 *
 * 公共实例短时间连续请求会返回 Cloudflare 1015（限流），因此客户端做
 * 最小间隔与失败冷却，冷却期间直接讲内置笑话，不空等也不刷接口。
 *
 * [fetch] 是阻塞方法，必须在后台线程调用；异常不外抛，失败一律回退本地。
 */
object JokeService {

    /** [setup] 为 null 表示单段笑话，否则是「铺垫 → 抖包袱」两拍 */
    data class Joke(val setup: String?, val punchline: String, val online: Boolean)

    private val log = logger<JokeService>()

    private const val URL = "https://60s.viki.moe/v2/dad-joke"

    /** 在线正文超过这个长度就截断，气泡里放不下超长段落 */
    private const val MAX_TEXT_CHARS = 200

    /** 最近讲过的在线笑话 index，避免连着重复 */
    private const val RECENT_LIMIT = 8

    /** 两次在线请求的最小间隔：公共实例限流较严，间隔内直接讲内置笑话 */
    private const val MIN_ONLINE_GAP_MS = 15_000L

    /** 在线失败后的冷却时间，期间不再重复请求 */
    private const val FAIL_COOLDOWN_MS = 60_000L

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val recentIds = ArrayDeque<Int>()
    private val localBag = ArrayDeque<Joke>()
    private var lastLocal: Joke? = null
    private var lastOnlineAt = 0L
    private var cooldownUntil = 0L
    private val lock = Any()

    /** 取一条笑话：中文在线优先，限流 / 失败 / 抽重都回退内置中文 */
    fun fetch(): Joke {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now < cooldownUntil || now - lastOnlineAt < MIN_ONLINE_GAP_MS) return local()
            lastOnlineAt = now
        }
        val online = fetchOnline()
        if (online == null) {
            synchronized(lock) { cooldownUntil = System.currentTimeMillis() + FAIL_COOLDOWN_MS }
            return local()
        }
        synchronized(lock) {
            if (recentIds.contains(online.id)) return local()
            recentIds.addLast(online.id)
            if (recentIds.size > RECENT_LIMIT) recentIds.removeFirst()
        }
        return online.joke
    }

    /** 内置中文笑话：本地随机，不联网也能讲 */
    fun local(): Joke = synchronized(lock) {
        if (localBag.isEmpty()) {
            val shuffled = LOCAL_JOKES.map { Joke(it.first, it.second, online = false) }.shuffled().toMutableList()
            if (shuffled.size > 1 && shuffled.first() == lastLocal) {
                shuffled.add(shuffled.removeAt(0))
            }
            localBag.addAll(shuffled)
        }
        val joke = localBag.removeFirst()
        lastLocal = joke
        joke
    }

    private class OnlineJoke(val id: Int, val joke: Joke)

    private fun fetchOnline(): OnlineJoke? = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(URL))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            log.info("中文笑话接口返回 HTTP ${response.statusCode()}，改用内置笑话")
            null
        } else {
            parse(response.body())
        }
    } catch (e: Exception) {
        log.info("中文笑话接口请求失败，改用内置笑话：${e.message}")
        null
    }

    private fun parse(body: String): OnlineJoke? = try {
        val root = JsonParser.parseString(body).asJsonObject
        if (root.get("code")?.asInt != 200) {
            log.info("中文笑话接口返回业务错误，改用内置笑话")
            return null
        }
        val data = root.getAsJsonObject("data") ?: return null
        val content = text(data.get("content")?.asString) ?: return null
        // 在线冷笑话是单段文本，两拍由内置笑话库提供
        OnlineJoke(data.get("index")?.asInt ?: -1, Joke(null, content, online = true))
    } catch (e: Exception) {
        log.info("中文笑话接口解析失败，改用内置笑话：${e.message}")
        null
    }

    private fun text(raw: String?): String? {
        val value = raw?.trim()?.take(MAX_TEXT_CHARS) ?: return null
        return value.ifBlank { null }
    }

    /** 两拍中文笑话：铺垫 + 抖包袱，全部本地静态文本，不涉及任何外部素材 */
    private val LOCAL_JOKES = listOf(
        "主人，问个问题：程序员为什么总分不清万圣节和圣诞节？" to "因为 Oct 31 等于 Dec 25。",
        "我昨天跟 SQL 吵了一架。" to "它嫌我没写 WHERE，我嫌它一点也不讲分寸。",
        "两个硬编码的值去相亲。" to "见面第一句就问：是不是被写死在那儿了？",
        "测试同学说这个 bug 复现不了。" to "我说行，那我把日志删了，它马上就出现。",
        "主人，咖啡和代码有什么共同点？" to "都得趁热写，凉了没人愿意碰。",
        "我问产品经理需求还会改吗。" to "他说不改了，然后把文档版本从 v9 改成了 v10。",
        "递归最怕什么？" to "怕自己也问一句「递归最怕什么」。",
        "我昨晚在健身房举铁。" to "举的是服务器机架，练的是运维的臂力。",
        "缓存说它什么都记得。" to "结果一过期，连自己叫什么都忘了。",
        "主人，键盘上哪个键最会讲笑话？" to "Enter，一按下去就抖包袱。",
        "小数组去面试。" to "面试官问下标从几开始，它说：看语言，别吵。",
        "我跟编译器和好了。" to "它不再报错，我不再半夜写它不认识的类型。",
        "为什么程序员喜欢在夜里跑步？" to "因为白天都在跑测试。",
        "我给机器人讲了个冷笑话。" to "它回我：温度过低，已自动重启。",
        "主人，日志里最诚实的是哪一行？" to "最后一行，因为它总在报错之后才出现。",
    )
}
