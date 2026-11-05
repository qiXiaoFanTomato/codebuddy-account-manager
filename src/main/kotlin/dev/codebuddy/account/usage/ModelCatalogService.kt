package dev.codebuddy.account.usage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.service.AccountLibrary
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

/**
 * 从 CodeBuddy 拉取可用模型目录（供模型限额规则下拉选择）。
 *
 * 接口（实测有效，与官方 IDE 同源）：
 *   GET https://<domain>/v3/config
 *   Authorization: Bearer <accessToken>
 *   X-User-Id:   <uid>
 *   X-Domain:    <domain>
 *   X-Product:   SaaS
 *   X-CodeBuddy-Request: 1
 *   User-Agent:  CodeBuddyIDE/<ver> CodeBuddy/<ver>   ← 必须，缺失或过旧会 400 code=12403
 *
 * 返回结构（节选）：
 * {
 *   "code": 0,
 *   "data": { "models": [
 *     { "id": "deepseek-v4.1-flash", "name": "Deepseek-V4.1-Flash", "credits": "x0.11",
 *       "maxInputTokens": 1000000, "maxOutputTokens": 128000, "supportsReasoning": true,
 *       "reasoning": { "supportedEfforts": ["low","high","max"], "defaultEffort": "high" },
 *       "disabled": false }
 *   ] }
 * }
 *
 * 目录按「模型名包含关键字」匹配限额规则，因此这里只提供 id / name 供选择。
 */
object ModelCatalogService {

    private val log = logger<ModelCatalogService>()

    /** 与官方 IDE 一致的 UA，版本过低该端点会返回 code=12403 */
    private const val USER_AGENT = "CodeBuddyIDE/4.12.0 CodeBuddy/4.12.0"

    /** 目录缓存有效期 */
    private const val STALE_MS = 30 * 60_000L

    /** 单个模型条目 */
    data class ModelOption(
        val id: String,
        val name: String = "",
        /** 积分倍率原文，如 "x0.11" */
        val credits: String = "",
        val supportsReasoning: Boolean = false,
        val maxInputTokens: Long = 0,
    ) {
        /** 下拉展示文案 */
        fun label(): String = if (name.isBlank()) id else "${name}（$id）"
    }

    /** 一次目录查询结果 */
    data class Catalog(
        val models: List<ModelOption> = emptyList(),
        val fetchedAt: Long = 0L,
        val loading: Boolean = false,
        /** 失败原因；成功为 null */
        val error: String? = null,
    ) {
        val loaded: Boolean get() = fetchedAt > 0L
    }

    @Volatile
    private var catalog = Catalog()

    private val lock = Any()
    private var loading = false
    private val waiters = mutableListOf<() -> Unit>()

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun state(): Catalog = catalog

    /**
     * 确保模型目录可用：缓存有效时不发请求、不回调；
     * 结果回来后通过 [onLoaded]（EDT）通知界面。
     */
    fun ensureFresh(force: Boolean = false, onLoaded: (() -> Unit)? = null) {
        synchronized(lock) {
            val fresh = !force && catalog.loaded && catalog.error == null &&
                System.currentTimeMillis() - catalog.fetchedAt < STALE_MS
            if (fresh) return
            if (onLoaded != null) waiters.add(onLoaded)
            if (loading) return
            loading = true
            catalog = catalog.copy(loading = true)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val next = try {
                fetch()
            } catch (t: Throwable) {
                log.warn("拉取模型目录失败", t)
                Catalog(error = t.message ?: t.javaClass.simpleName, fetchedAt = System.currentTimeMillis())
            }
            synchronized(lock) {
                catalog = next
                loading = false
            }
            notifyWaiters()
        }
    }

    /** 取账号库中第一个可用凭据请求 /v3/config */
    private fun fetch(): Catalog {
        val library = AccountLibrary.getInstance()
        val saved = try {
            library.listSaved()
        } catch (t: Throwable) {
            log.warn("读取账号库失败", t)
            emptyList()
        }
        if (saved.isEmpty()) {
            return Catalog(error = "账号库为空，请先导入账号")
        }

        var lastError: String? = null
        for (snapshot in saved) {
            val token = library.accessTokenOf(snapshot.accountKey)
            if (token.isNullOrBlank()) {
                lastError = lastError ?: "${snapshot.displayName}：凭据缺少 accessToken"
                continue
            }
            val host = snapshot.domain.ifBlank { "www.codebuddy.cn" }
            when (val result = requestCatalog(host, token, snapshot.uid)) {
                is Result.Success -> return Catalog(models = result.models, fetchedAt = System.currentTimeMillis())
                is Result.Failure -> lastError = "${snapshot.displayName}：${result.message}"
            }
        }
        return Catalog(error = lastError ?: "没有可用账号", fetchedAt = System.currentTimeMillis())
    }

    private sealed class Result {
        data class Success(val models: List<ModelOption>) : Result()
        data class Failure(val message: String) : Result()
    }

    private fun requestCatalog(domain: String, accessToken: String, uid: String): Result {
        val host = domain.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val url = "https://$host/v3/config"
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Domain", host)
                .header("X-Product", "SaaS")
                .header("X-CodeBuddy-Request", "1")
                .header("User-Agent", USER_AGENT)
                .GET()
            if (uid.isNotBlank()) builder.header("X-User-Id", uid)

            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> parse(response.body())
                401 -> Result.Failure("HTTP 401：凭据已过期，请重新登录该账号")
                else -> Result.Failure("HTTP ${response.statusCode()}")
            }
        } catch (t: Throwable) {
            log.warn("请求模型目录失败: $url", t)
            Result.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun parse(body: String): Result {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                val msg = root.get("msg")?.takeIf { !it.isJsonNull }?.asString ?: "未知错误"
                return Result.Failure("接口返回 code=$code: $msg")
            }
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return Result.Failure("返回结构异常")
            val arr = data.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return Result.Failure("返回结构异常：缺少 models")

            val models = arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val id = str(o, "id")
                if (id.isBlank()) return@mapNotNull null
                if (bool(o, "disabled")) return@mapNotNull null
                if (!isChatModel(id, long(o, "maxOutputTokens"), tags(o))) return@mapNotNull null
                val reasoning = o.get("reasoning")?.takeIf { it.isJsonObject }?.asJsonObject
                ModelOption(
                    id = id,
                    name = str(o, "name"),
                    credits = str(o, "credits"),
                    supportsReasoning = bool(o, "supportsReasoning") ||
                        (reasoning != null && bool(reasoning, "supportsReasoning")),
                    maxInputTokens = long(o, "maxInputTokens"),
                )
            }
            if (models.isEmpty()) Result.Failure("模型目录为空") else Result.Success(models)
        } catch (t: Exception) {
            log.warn("模型目录解析失败", t)
            Result.Failure("解析失败: ${t.message}")
        }
    }

    /**
     * 是否为可对话模型：剔除补全 / NES / 图像等专用条目。
     * 规则与官方客户端一致（前缀 + 输出长度过小 + text-to-image 标签）。
     */
    private fun isChatModel(id: String, maxOutputTokens: Long, tags: List<String>): Boolean {
        val lower = id.lowercase(Locale.ROOT)
        if (lower.startsWith("codewise-") || lower.startsWith("nes-") || lower.startsWith("completion-")) {
            return false
        }
        if (tags.any { it.contains("text-to-image", ignoreCase = true) }) return false
        if (maxOutputTokens in 1..256) return false
        return true
    }

    private fun str(o: JsonObject, key: String): String =
        try {
            val el = o.get(key)
            if (el == null || el.isJsonNull) "" else el.asString
        } catch (_: Exception) {
            ""
        }

    private fun bool(o: JsonObject, key: String): Boolean =
        try {
            val el = o.get(key)
            el != null && !el.isJsonNull && el.asBoolean
        } catch (_: Exception) {
            false
        }

    private fun long(o: JsonObject, key: String): Long =
        try {
            val el = o.get(key)
            if (el == null || el.isJsonNull) 0L else el.asLong
        } catch (_: Exception) {
            0L
        }

    private fun tags(o: JsonObject): List<String> {
        val el = o.get("tags") ?: return emptyList()
        if (!el.isJsonArray) return emptyList()
        return el.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
    }

    private fun notifyWaiters() {
        val pending: List<() -> Unit>
        synchronized(lock) {
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { callback ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    callback()
                } catch (t: Throwable) {
                    log.warn("模型目录回调失败", t)
                }
            }, ModalityState.any())
        }
    }
}
