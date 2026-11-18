package dev.codebuddy.account.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.credential.CredentialParser
import dev.codebuddy.account.model.CredentialSnapshot
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 添加账号：OAuth 授权登录 / Token 反查 / 凭据文本解析。
 *
 * 协议对齐 cockpit-tools 的 CodeBuddy CN 实现
 * （crates/cockpit-core/src/modules/codebuddy_cn_oauth.rs、codebuddy_cn_account.rs）：
 *
 *  1. POST /v2/plugin/auth/state?platform=ide   → data { state, authUrl }
 *     请求需带四个 X-No-*: true 头；缺 User-Agent 会被网关拒绝（403 / code 10085）。
 *  2. 浏览器打开 authUrl（为空时退化为 https://www.codebuddy.cn/login?state=<state>）
 *  3. GET  /v2/plugin/auth/token?state=<state>  → 每 1.5s 轮询一次，最长 600s
 *     code == 0 / 200 且 data.accessToken 非空即授权完成
 *  4. GET  /v2/plugin/login/account?state=<state>（Bearer）→ uid / nickname / email / enterpriseId
 *  5. 裸 token 反查：GET /v2/plugin/accounts（Bearer）→ data.accounts 里取 lastLogin 优先的一条
 *
 * 落库结构与 [CredentialParser] 的实测 .info 结构保持一致：account + accounts + allAccounts + auth，
 * 这样切换、额度刷新、导出等既有链路无需改动。
 */
object AddAccountService {

    private const val ENDPOINT = "https://www.codebuddy.cn"
    private const val API_PREFIX = "/v2/plugin"
    private const val PLATFORM = "ide"
    private const val DEFAULT_DOMAIN = "www.codebuddy.cn"

    /** 授权有效期（秒）：超时后不再轮询 */
    const val TIMEOUT_SECONDS = 600L

    /** 轮询间隔（毫秒） */
    const val POLL_INTERVAL_MS = 1500L

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val log = logger<AddAccountService>()

    /**
     * 直连 client。
     *
     * IDE 的 HTTP 代理常设为「自动检测设置（PAC）」（`proxy.settings.xml` 的 `USE_PROXY_PAC=true`），
     * IDE 会把自己的 ProxySelector 注入成 JVM 默认值，JDK HttpClient 默认就会跟着走，
     * 表现为请求长时间挂在 PAC 解析上、界面停在「正在申请授权链接…」。
     * codebuddy.cn 国内可直连，因此这里显式 NO_PROXY。
     */
    private val directClient: HttpClient by lazy {
        buildClient(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
    }

    /** 兜底 client：沿用系统 / IDE 代理，给必须经代理出网的网络环境 */
    private val defaultClient: HttpClient by lazy { buildClient(null) }

    private fun buildClient(selector: ProxySelector?): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .apply { if (selector != null) proxy(selector) }
            .build()

    private data class HttpResult(val status: Int, val body: String)

    /** 先直连、失败再走代理；两次都失败时抛出最后一次异常 */
    private fun send(request: HttpRequest): HttpResult {
        var lastError: Exception? = null
        for ((label, client) in listOf("直连" to directClient, "代理" to defaultClient)) {
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                return HttpResult(response.statusCode(), response.body())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                log.info("[添加账号] $label 请求失败：${e.javaClass.simpleName}: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("请求失败")
    }

    /** 一次待授权会话 */
    data class Session(
        val state: String,
        /** 浏览器打开的授权页地址 */
        val loginUrl: String,
        /** 会话过期时间戳（毫秒） */
        val expiresAt: Long,
    )

    /** auth/token 返回的 token 信息 */
    data class TokenInfo(
        val accessToken: String,
        val refreshToken: String,
        val domain: String,
        val expiresAt: Long,
        val refreshExpiresAt: Long,
    )

    /** 账号元数据 */
    data class AccountInfo(
        val uid: String,
        val nickname: String,
        val email: String,
        val enterpriseId: String,
    )

    /** 用户粘贴文本的解析结果 */
    sealed class InputParse {
        /** 文本里已含完整凭据，可直接落库 */
        data class Snapshots(val list: List<CredentialSnapshot>) : InputParse()

        /** 文本只有一个裸 token，需要联网反查 uid */
        data class TokenOnly(val token: String) : InputParse()

        data class Invalid(val message: String) : InputParse()
    }

    // ---------------------------------------------------------------
    // OAuth
    // ---------------------------------------------------------------

    /** 申请 state 与授权页地址。网络/解析失败返回 failure。 */
    fun start(): Result<Session> {
        return try {
            val url = "$ENDPOINT$API_PREFIX/auth/state?platform=$PLATFORM"
            log.info("[添加账号] 申请 auth/state：$url")
            val response = send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .header("X-No-Authorization", "true")
                    .header("X-No-User-Id", "true")
                    .header("X-No-Enterprise-Id", "true")
                    .header("X-No-Department-Info", "true")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
            )
            if (response.status != 200) {
                return Result.failure(IllegalStateException("申请授权失败：HTTP ${response.status}"))
            }
            val root = JsonParser.parseString(response.body).asJsonObject
            val data = root.getAsJsonObject("data")
                ?: return Result.failure(IllegalStateException("申请授权失败：响应缺少 data"))
            val state = data.str("state")
            if (state.isBlank()) {
                return Result.failure(IllegalStateException("申请授权失败：响应缺少 state"))
            }
            val authUrl = firstNonBlank(data, "authUrl", "auth_url", "url")
            val loginUrl = authUrl.ifBlank { "$ENDPOINT/login?state=$state" }
            log.info("[添加账号] auth/state 成功，state=$state")
            Result.success(Session(state, loginUrl, System.currentTimeMillis() + TIMEOUT_SECONDS * 1000))
        } catch (e: Exception) {
            log.warn("申请 OAuth state 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 轮询授权结果，直到拿到 accessToken 或超时/取消。
     *
     * **阻塞调用**：必须放在后台线程，[cancelled] 由调用方在窗口关闭时置真。
     */
    fun pollToken(session: Session, cancelled: () -> Boolean): Result<TokenInfo> {
        val url = "$ENDPOINT$API_PREFIX/auth/token?state=${session.state}"
        while (true) {
            if (cancelled()) return Result.failure(IllegalStateException("已取消授权"))
            if (System.currentTimeMillis() > session.expiresAt) {
                return Result.failure(IllegalStateException("授权超时，请重新发起授权"))
            }
            try {
                val response = send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", USER_AGENT)
                        .header("X-No-Authorization", "true")
                        .header("X-No-User-Id", "true")
                        .header("X-No-Enterprise-Id", "true")
                        .header("X-No-Department-Info", "true")
                        .GET()
                        .build(),
                )
                if (response.status == 200) {
                    val info = parseToken(response.body)
                    if (info != null) {
                        log.info("[添加账号] 授权完成，已获取 accessToken")
                        return Result.success(info)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.failure(IllegalStateException("已取消授权"))
            } catch (e: Exception) {
                // 轮询期间的网络抖动不终止流程，下一轮继续
                log.info("轮询授权结果失败，稍后重试: ${e.message}")
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.failure(IllegalStateException("已取消授权"))
            }
        }
    }

    /** 解析 auth/token 响应；尚未授权完成返回 null */
    private fun parseToken(body: String): TokenInfo? {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val code = root.get("code")?.asInt ?: -1
            if (code != 0 && code != 200) return null
            val data = root.getAsJsonObject("data") ?: return null
            val token = firstNonBlank(data, "accessToken", "access_token")
            if (token.isBlank()) return null
            TokenInfo(
                accessToken = token,
                refreshToken = firstNonBlank(data, "refreshToken", "refresh_token"),
                domain = firstNonBlank(data, "domain").ifBlank { DEFAULT_DOMAIN },
                expiresAt = expiryOf(data, "expiresAt", "expires_at", "expiresIn", "expires_in"),
                refreshExpiresAt = expiryOf(data, "refreshExpiresAt", "refresh_expires_at"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 用 state 拉账号信息；失败返回 null（账号信息缺失不影响 token 落库） */
    fun fetchAccount(token: String, state: String, domain: String): AccountInfo? {
        return try {
            val host = domain.ifBlank { DEFAULT_DOMAIN }
            val url = "https://$host$API_PREFIX/login/account?state=$state"
            val response = send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .header("X-No-User-Id", "true")
                    .header("X-No-Enterprise-Id", "true")
                    .header("X-No-Department-Info", "true")
                    .header("X-Domain", host)
                    .GET()
                    .build(),
            )
            if (response.status != 200) return null
            val data = JsonParser.parseString(response.body).asJsonObject.getAsJsonObject("data")
                ?: return null
            accountInfoOf(data)
        } catch (e: Exception) {
            log.info("获取账号信息失败: ${e.message}")
            null
        }
    }

    /** 裸 token 反查账号：取 data.accounts 中 lastLogin 的一条，否则第一条 */
    fun resolveByToken(token: String): Result<AccountInfo> {
        return try {
            val url = "$ENDPOINT$API_PREFIX/accounts"
            val response = send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build(),
            )
            if (response.status != 200) {
                return Result.failure(IllegalStateException("token 校验失败：HTTP ${response.status}"))
            }
            val root = JsonParser.parseString(response.body).asJsonObject
            val code = root.get("code")?.asInt
            if (code != null && code != 0 && code != 200) {
                return Result.failure(IllegalStateException("token 校验失败：code=$code"))
            }
            val accounts = root.getAsJsonObject("data")?.getAsJsonArray("accounts")
                ?: return Result.failure(IllegalStateException("token 未关联到任何账号"))
            val objects = accounts.filterIsInstance<JsonObject>()
            val picked = objects.firstOrNull { it.bool("lastLogin") } ?: objects.firstOrNull()
                ?: return Result.failure(IllegalStateException("token 未关联到任何账号"))
            val info = accountInfoOf(picked)
            if (info.uid.isBlank()) {
                Result.failure(IllegalStateException("token 未关联到任何账号"))
            } else {
                Result.success(info)
            }
        } catch (e: Exception) {
            log.warn("裸 token 反查账号失败", e)
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------
    // 凭据组装
    // ---------------------------------------------------------------

    /**
     * 把 token 与账号信息组装成账号库可用的 [CredentialSnapshot]。
     *
     * 结构对齐实测 .info：account / accounts / allAccounts / auth。
     * accounts 与 allAccounts 只放元数据（实测不含 token），token 只在 auth 节点。
     */
    fun buildSnapshot(token: TokenInfo, account: AccountInfo): CredentialSnapshot {
        val domain = token.domain.ifBlank { DEFAULT_DOMAIN }
        val type = if (account.enterpriseId.isNotBlank()) "enterprise" else "personal"

        val meta = JsonObject().apply {
            addProperty("uid", account.uid)
            addProperty("nickname", account.nickname)
            addProperty("uin", "")
            addProperty("type", type)
            addProperty("phoneNumber", "")
            if (account.email.isNotBlank()) addProperty("email", account.email)
            if (account.enterpriseId.isNotBlank()) addProperty("enterpriseId", account.enterpriseId)
        }
        val auth = JsonObject().apply {
            addProperty("accessToken", token.accessToken)
            if (token.refreshToken.isNotBlank()) addProperty("refreshToken", token.refreshToken)
            addProperty("domain", domain)
            addProperty("expiresAt", token.expiresAt)
            addProperty("refreshExpiresAt", token.refreshExpiresAt)
            addProperty("tokenType", "Bearer")
        }
        val root = JsonObject().apply {
            add("account", meta)
            add("accounts", JsonArray().apply { add(meta.deepCopy()) })
            add("allAccounts", JsonArray().apply { add(meta.deepCopy()) })
            add("auth", auth)
        }
        val raw = GsonBuilder().setPrettyPrinting().create().toJson(root)

        return CredentialSnapshot(
            uid = account.uid,
            nickname = account.nickname,
            uin = "",
            type = type,
            phoneNumber = "",
            domain = domain,
            expiresAt = token.expiresAt,
            refreshExpiresAt = token.refreshExpiresAt,
            rawContent = raw,
            sourcePath = "",
        )
    }

    /** 裸 token 场景：只有 accessToken，没有 refreshToken 与过期时间 */
    fun snapshotFromToken(accessToken: String, account: AccountInfo): CredentialSnapshot =
        buildSnapshot(TokenInfo(accessToken, "", DEFAULT_DOMAIN, 0L, 0L), account)

    // ---------------------------------------------------------------
    // 文本解析
    // ---------------------------------------------------------------

    /**
     * 解析用户粘贴的内容。支持三种输入：
     *  - 单个 .info 凭据 JSON（account + auth）
     *  - 账号库导出 JSON（数组，元素含 rawContent）
     *  - 裸 accessToken（需要后续联网反查）
     */
    fun parseInput(raw: String): InputParse {
        val text = raw.trim()
        if (text.isEmpty()) return InputParse.Invalid("请输入凭据内容")

        if (text.startsWith("[")) {
            val array = try {
                JsonParser.parseString(text).asJsonArray
            } catch (_: Exception) {
                return InputParse.Invalid("JSON 数组格式不正确")
            }
            val snapshots = mutableListOf<CredentialSnapshot>()
            for (el in array) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                // 账号库导出格式：凭据在 rawContent 里
                val inner = obj.get("rawContent")
                val credentialText = if (inner != null && inner.isJsonPrimitive) inner.asString else obj.toString()
                CredentialParser.parseContent(credentialText)?.let { snapshots.add(it) }
            }
            return if (snapshots.isEmpty()) {
                InputParse.Invalid("数组中没有可识别的凭据")
            } else {
                InputParse.Snapshots(snapshots)
            }
        }

        if (text.startsWith("{")) {
            CredentialParser.parseContent(text)?.let { return InputParse.Snapshots(listOf(it)) }
            // 没有 account 节点时，退一步取 auth.accessToken 反查
            val token = try {
                val root = JsonParser.parseString(text).asJsonObject
                firstNonBlank(root, "accessToken", "access_token", "token").ifBlank {
                    root.getAsJsonObject("auth")?.let { firstNonBlank(it, "accessToken", "access_token") }.orEmpty()
                }
            } catch (_: Exception) {
                ""
            }
            return if (token.isNotBlank()) InputParse.TokenOnly(token)
            else InputParse.Invalid("JSON 中缺少 account 节点或 accessToken")
        }

        // 既不是 JSON 也不是短串：按裸 token 处理
        if (text.length < 16 || text.contains(' ')) {
            return InputParse.Invalid("无法识别的凭据内容")
        }
        return InputParse.TokenOnly(text)
    }

    // ---------------------------------------------------------------
    // JsonObject 小工具
    // ---------------------------------------------------------------

    private fun accountInfoOf(obj: JsonObject): AccountInfo = AccountInfo(
        uid = firstNonBlank(obj, "uid"),
        nickname = firstNonBlank(obj, "nickname"),
        email = firstNonBlank(obj, "email"),
        enterpriseId = firstNonBlank(obj, "enterpriseId"),
    )

    /**
     * 过期时间归一化：秒级时间戳 ×1000，`expiresIn` 按当前时间加秒数。
     * 与 cockpit-tools 的 token_expiry_at 同口径。
     */
    private fun expiryOf(data: JsonObject, vararg keys: String): Long {
        for (key in keys) {
            val el = data.get(key) ?: continue
            if (el.isJsonNull) continue
            val value = try {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.trim().toLongOrNull()
                else el.asLong
            } catch (_: Exception) {
                null
            } ?: continue
            if (value <= 0) continue
            if (key.startsWith("expiresIn") || key.startsWith("expires_in")) {
                return System.currentTimeMillis() + value * 1000
            }
            return if (value < 100_000_000_000L) value * 1000 else value
        }
        return 0L
    }

    private fun firstNonBlank(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            val value = try {
                el.asString.trim()
            } catch (_: Exception) {
                ""
            }
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun JsonObject.str(key: String): String = firstNonBlank(this, key)

    private fun JsonObject.bool(key: String): Boolean = try {
        val el = get(key)
        el != null && !el.isJsonNull && el.asBoolean
    } catch (_: Exception) {
        false
    }
}
