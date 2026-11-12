package dev.codebuddy.account.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.codebuddy.account.credential.CredentialParser
import dev.codebuddy.account.credential.CredentialPaths
import dev.codebuddy.account.credential.SecretStorage
import dev.codebuddy.account.hotswap.CodeBuddyBridge
import dev.codebuddy.account.model.CredentialSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 账号库：管理用户保存的所有凭据快照。
 *
 * 存储布局（插件私有目录下）：
 *   <pluginConfig>/codebuddy-accounts/
 *     index.json                账号元数据索引
 *     snapshots/<uid>.info      每个账号的完整凭据文件副本
 *
 * 之所以保存完整文件而不是 token 字段：切换时必须整文件替换，见 CredentialSnapshot 注释。
 */
@Service(Service.Level.APP)
class AccountLibrary {

    private val log = logger<AccountLibrary>()
    private val lock = Any()

    /** 插件私有目录 */
    private val baseDir: Path by lazy {
        PathManager.getConfigDir().resolve("codebuddy-accounts").also { Files.createDirectories(it) }
    }

    private val snapshotsDir: Path by lazy {
        baseDir.resolve("snapshots").also { Files.createDirectories(it) }
    }

    private val indexFile: Path by lazy { baseDir.resolve("index.json") }

    /** 配额缓存 */
    val quotaCache: QuotaCache by lazy { QuotaCache(baseDir.resolve("quota-cache.json")) }

    // ---------------------------------------------------------------
    // 读取
    // ---------------------------------------------------------------

    /** 返回账号库中已保存的所有快照（按导入时间倒序） */
    fun listSaved(): List<CredentialSnapshot> {
        synchronized(lock) {
            migrateLegacySnapshotsIfNeeded()
            if (!Files.isRegularFile(indexFile)) return emptyList()
            return try {
                val arr = CredentialParser.parseIndex(Files.readString(indexFile, StandardCharsets.UTF_8))
                arr.map { hydrate(it) }.sortedByDescending { it.importedAt }
            } catch (e: Exception) {
                log.warn("读取账号索引失败", e)
                emptyList()
            }
        }
    }

    /**
     * 迁移旧版快照文件。
     *
     * 旧版文件名为 <uid>.info（不含域名），新版为 <uid>@<domain>.info。
     * 若发现旧文件且能从内容解析出 domain，则重命名为新格式。
     */
    private fun migrateLegacySnapshotsIfNeeded() {
        if (!Files.isDirectory(snapshotsDir)) return
        try {
            val files = Files.list(snapshotsDir).use { it.toList() }
            for (f in files) {
                val name = f.fileName.toString()
                if (!name.endsWith(".info") || name.contains("@")) continue
                // 旧文件：从内容读 domain
                val snap = CredentialParser.parse(f) ?: continue
                val newName = dev.codebuddy.account.model.AccountKeys.toFileName(snap.accountKey)
                if (newName == name) continue
                val target = snapshotsDir.resolve(newName)
                if (Files.exists(target)) {
                    // 已有新文件，删除旧的
                    Files.deleteIfExists(f)
                } else {
                    Files.move(f, target, StandardCopyOption.REPLACE_EXISTING)
                    log.info("已迁移旧快照: $name -> $newName")
                }
            }
        } catch (e: Exception) {
            log.warn("迁移旧快照失败", e)
        }
    }

    /** 索引里不存 rawContent，这里从快照文件回填 */
    private fun hydrate(snapshot: CredentialSnapshot): CredentialSnapshot {
        if (snapshot.rawContent.isNotEmpty()) return snapshot
        val file = snapshotsDir.resolve(dev.codebuddy.account.model.AccountKeys.toFileName(snapshot.accountKey))
        if (!Files.isRegularFile(file)) return snapshot
        return try {
            val raw = Files.readString(file, StandardCharsets.UTF_8)
            snapshot.copy(rawContent = raw, sourcePath = file.toString())
        } catch (_: Exception) {
            snapshot
        }
    }

    /** 当前 IDEA 正在使用的凭据（读 secret-storage.xml） */
    fun currentActive(): CredentialSnapshot? = SecretStorage.readCurrent()

    /**
     * 刷新指定账号的配额并写入缓存。key 为 accountKey。
     * [auto] = true 表示自动定时路径：TTL 内复用其它实例的刷新结果，
     * 并按跨进程最小间隔抑制同刻重复；手动刷新不受影响。
     */
    fun refreshQuota(uid: String, auto: Boolean = false): dev.codebuddy.account.quota.QuotaSummary? {
        val saved = listSaved()
        val snapshot = saved.firstOrNull { it.accountKey == uid } ?: return null
        // 从快照原文取 token（listSaved 已 hydrate，rawContent 非空）
        val token = extractAccessToken(snapshot.rawContent) ?: return null
        val coordinator = MultiInstanceCoordinator
        val shared = coordinator.sharedQuotaCache(saved.map { it.accountKey })

        if (auto) {
            val cached = shared?.loadAll()?.get(uid)?.takeIf {
                it.error == null && System.currentTimeMillis() - it.fetchedAt < coordinator.QUOTA_SHARED_TTL_MS
            }
            if (cached != null) {
                quotaCache.put(uid, cached)
                coordinator.noteRequest("quota:shared-hit")
                return cached
            }
            if (!coordinator.claim("quota", uid, coordinator.QUOTA_MIN_INTERVAL_MS)) {
                // 其它实例刚刷新过：复用共享结果，不再打接口
                val recent = shared?.loadAll()?.get(uid)
                if (recent != null) quotaCache.put(uid, recent)
                return recent ?: quotaCache.loadAll()[uid]
            }
        }

        val summary = limited("quota") {
            dev.codebuddy.account.quota.QuotaService.fetch(snapshot.domain, token)
        }
        quotaCache.put(uid, summary)
        if (summary.error == null) {
            // 写一份到全局共享缓存，供其它 IDE 进程复用
            shared?.put(uid, summary)
        } else if (auto) {
            // 失败不占用闸门，允许立即重试
            coordinator.releaseClaim("quota", uid)
        }

        // 配额告警
        if (summary.error == null && summary.packages.isNotEmpty()) {
            val settings = QuotaAlertSettings.getInstance()
            if (summary.usableRemainPercent < settings.state.thresholdPercent) {
                if (settings.shouldAlert(uid, summary.usableRemainPercent)) {
                    fireQuotaAlert(snapshot, summary)
                }
            } else {
                settings.clearAlert(uid)
            }
        }
        return summary
    }

    /**
     * 查询指定账号在 [startTime, endTime] 内的请求消耗明细（对应网页「消耗明细」）。
     * key 为 accountKey；失败信息通过 UsagePage.error 返回，不抛异常。
     */
    fun fetchUsage(
        accountKey: String,
        startTime: String,
        endTime: String,
        pageSize: Int = dev.codebuddy.account.usage.UsageService.MAX_PAGE_SIZE,
    ): dev.codebuddy.account.usage.UsagePage {
        val snapshot = listSaved().firstOrNull { it.accountKey == accountKey }
            ?: return dev.codebuddy.account.usage.UsagePage(error = "账号不在账号库中，请先导入")
        val token = extractAccessToken(snapshot.rawContent)
            ?: return dev.codebuddy.account.usage.UsagePage(error = "凭据快照中缺少 accessToken")
        return limited("usage") {
            dev.codebuddy.account.usage.UsageService.fetch(
                domain = snapshot.domain,
                accessToken = token,
                startTime = startTime,
                endTime = endTime,
                pageSize = pageSize,
            )
        }
    }

    /**
     * 翻页查询指定账号在 [startTime, endTime] 内的全部请求消耗。
     * 用于周期内按模型聚合统计（模型积分限额）；最多拉 [maxPages] 页。
     * key 为 accountKey；失败信息通过 UsagePage.error 返回，不抛异常。
     */
    fun fetchUsageAll(
        accountKey: String,
        startTime: String,
        endTime: String,
        maxPages: Int = 20,
    ): dev.codebuddy.account.usage.UsagePage {
        val snapshot = listSaved().firstOrNull { it.accountKey == accountKey }
            ?: return dev.codebuddy.account.usage.UsagePage(error = "账号不在账号库中，请先导入")
        val token = extractAccessToken(snapshot.rawContent)
            ?: return dev.codebuddy.account.usage.UsagePage(error = "凭据快照中缺少 accessToken")
        return limited("usage-all") {
            dev.codebuddy.account.usage.UsageService.fetchAll(
                domain = snapshot.domain,
                accessToken = token,
                startTime = startTime,
                endTime = endTime,
                pageSize = dev.codebuddy.account.usage.UsageService.MAX_PAGE_SIZE,
                maxPages = maxPages,
            )
        }
    }

    /** 配额过低时发通知（优先由桌宠承载） */
    private fun fireQuotaAlert(snapshot: CredentialSnapshot, summary: dev.codebuddy.account.quota.QuotaSummary) {
        try {
            dev.codebuddy.account.pet.PetNotifier.warn(
                "CodeBuddy 额度不足",
                "账号 ${snapshot.displayName} 剩余额度仅 ${String.format("%.1f", summary.usableRemainPercent)}%（${summary.summaryText()}）",
                null,
            )
        } catch (_: Exception) { }
    }

    /** 一次性取所有账号的 accessToken（批量接口用，避免逐个重复读索引） */
    fun accessTokenMap(): Map<String, String> = listSaved()
        .mapNotNull { s -> extractAccessToken(s.rawContent)?.let { s.accountKey to it } }
        .toMap()

    /** 取某账号快照里的 accessToken（签到等自定义请求用） */
    fun accessTokenOf(accountKey: String): String? {
        val snapshot = listSaved().firstOrNull { it.accountKey == accountKey } ?: return null
        return extractAccessToken(snapshot.rawContent)
    }

    private fun extractAccessToken(raw: String): String? = try {
        val root = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val auth = root.getAsJsonObject("auth") ?: return null
        val t = auth.get("accessToken")
        if (t == null || t.isJsonNull) null else t.asString
    } catch (_: Exception) {
        null
    }

    /**
     * 把所有已知账号的历史会话索引合并，补齐到 [targetUid]。
     * 用于切换账号前，保证新账号的侧边栏也能看到全部历史会话。
     */
    fun syncHistoryIndexes(
        targetUid: String,
        copyMessages: Boolean = false,
    ): dev.codebuddy.account.history.HistorySync.Result {
        val dataRoot = CredentialPaths.dataRoot()
            ?: return dev.codebuddy.account.history.HistorySync.Result(0, 0, 0, "找不到 CodeBuddy Data 目录")
        val uids = (listSaved().map { it.uid } + scanAuthDir().map { it.uid }).distinct()
        return dev.codebuddy.account.history.HistorySync.syncIndexes(dataRoot, targetUid, uids, copyMessages)
    }

    /**
     * 扫描 auth 目录下所有历史快照（不写入账号库）。
     *
     * 按 accountKey（uid@domain）去重：同一账号在不同域下的凭据是独立账号，
     * 同一 accountKey 的多个历史文件只保留 token 有效期最晚的。
     */
    fun scanAuthDir(): List<CredentialSnapshot> {
        return CredentialPaths.listSnapshotFiles()
            .mapNotNull { CredentialParser.parse(it) }
            .groupBy { it.accountKey }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.expiresAt } }
    }

    // ---------------------------------------------------------------
    // 写入
    // ---------------------------------------------------------------

    /**
     * 导入一份快照到账号库。
     * 同 uid 的账号会被覆盖更新（以最新 token 为准）。
     */
    fun importSnapshot(snapshot: CredentialSnapshot): Boolean {
        synchronized(lock) {
            // 「读索引 → 改 → 写索引」整体放在跨进程锁内，避免多开时丢失更新
            val locked = MultiInstanceCoordinator.withLockFor(indexFile) {
                importSnapshotLocked(snapshot)
            }
            return locked ?: importSnapshotLocked(snapshot)
        }
    }

    private fun importSnapshotLocked(snapshot: CredentialSnapshot): Boolean {
        return try {
            val target = snapshotsDir.resolve(dev.codebuddy.account.model.AccountKeys.toFileName(snapshot.accountKey))
            Files.writeString(target, snapshot.rawContent, StandardCharsets.UTF_8)

            val all = listSaved().filterNot { it.accountKey == snapshot.accountKey }.toMutableList()
            all.add(snapshot.copy(sourcePath = target.toString()))
            writeIndex(all)
            AccountEvents.fireAccountsChanged()
            true
        } catch (e: Exception) {
            log.warn("导入账号失败: ${snapshot.uid}", e)
            false
        }
    }

    /**
     * 导入可发现的账号：
     *  1. IDEA 版凭据（secret-storage.xml 中的当前登录账号）
     *  2. WorkBuddy 桌面端 auth 目录下的历史 .info 快照
     * 返回新增/更新的数量。
     */
    fun importFromAuthDir(): Int {
        val found = mutableListOf<CredentialSnapshot>()
        SecretStorage.readCurrent()?.let { found.add(it) }
        found.addAll(scanAuthDir())
        var count = 0
        for (s in found) {
            if (importSnapshot(s)) count++
        }
        return count
    }

    /** 删除账号库中的某个账号（不影响 IDE 当前凭据）。key 为 accountKey */
    fun delete(uid: String): Boolean {
        synchronized(lock) {
            // 「读索引 → 改 → 写索引」整体放在跨进程锁内，避免多开时丢失更新
            val locked = MultiInstanceCoordinator.withLockFor(indexFile) { deleteLocked(uid) }
            return locked ?: deleteLocked(uid)
        }
    }

    private fun deleteLocked(uid: String): Boolean {
        return try {
            Files.deleteIfExists(snapshotsDir.resolve(dev.codebuddy.account.model.AccountKeys.toFileName(uid)))
            writeIndex(listSaved().filterNot { it.accountKey == uid })
            AccountEvents.fireAccountsChanged()
            true
        } catch (e: Exception) {
            log.warn("删除账号失败: $uid", e)
            false
        }
    }

    // ---------------------------------------------------------------
    // 切换
    // ---------------------------------------------------------------

    /**
     * 把指定账号的凭据写入 IDEA 版 CodeBuddy 插件的凭据存储。
     *
     * 目标：<config>/options/secret-storage.xml 的
     *      Tencent-Cloud.coding-copilot.new.accessToken 条目。
     *
     * 注意：CodeBuddy 插件启动时读取凭据并缓存，
     * 因此切换后需要重启 IDE 才能生效。
     */
    fun switchTo(uid: String): SwitchResult {
        synchronized(lock) {
            val snapshotFile = snapshotsDir.resolve(dev.codebuddy.account.model.AccountKeys.toFileName(uid))
            if (!Files.isRegularFile(snapshotFile)) {
                return SwitchResult.Failure("账号 $uid 的快照不存在，请重新导入")
            }

            val json = try {
                Files.readString(snapshotFile, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                return SwitchResult.Failure("读取账号快照失败: ${e.message}")
            }

            // 先记录待生效切换，防止 IDEA 退出时覆盖
            PendingSwitch.saveWrite(uid, listSaved().firstOrNull { it.accountKey == uid }?.displayName ?: uid, json)

            // SecretStorage 内部会自动备份 secret-storage.xml
            val result = SecretStorage.writeCurrent(json)
            val switchResult = result.fold(
                onSuccess = { SwitchResult.Success(it) },
                onFailure = { SwitchResult.Failure(it.message ?: "写入凭据失败") }
            )
            if (switchResult is SwitchResult.Success) AccountEvents.fireAccountsChanged()
            return switchResult
        }
    }

    /**
     * 热切换：写凭据文件 + 更新 CodeBuddy 内存凭据表 + 重启其 Node 侧进程。
     *
     * 三步都成功即无需重启 IDE；任一步失败都以 [HotSwitchResult.message] 说明原因，
     * 调用方应提示用户改为重启 IDE。
     */
    fun hotSwitchTo(accountKey: String, project: Project? = null): HotSwitchResult {
        val fileResult = switchTo(accountKey)
        if (fileResult is SwitchResult.Failure) {
            return HotSwitchResult(false, fileResult.message)
        }

        val snapshotFile = snapshotsDir.resolve(dev.codebuddy.account.model.AccountKeys.toFileName(accountKey))
        val json = try {
            Files.readString(snapshotFile, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return HotSwitchResult(false, "读取账号快照失败: ${e.message}")
        }

        if (!CodeBuddyBridge.isAvailable()) {
            return HotSwitchResult(false, "未检测到 CodeBuddy 插件，需重启 IDE 生效")
        }
        if (!CodeBuddyBridge.setInMemorySecret(SecretStorage.ENTRY_KEY, json)) {
            return HotSwitchResult(false, "写入 CodeBuddy 内存凭据失败，需重启 IDE 生效")
        }
        if (CodeBuddyBridge.getInMemorySecret(SecretStorage.ENTRY_KEY) != json) {
            return HotSwitchResult(false, "凭据回读校验不一致，需重启 IDE 生效")
        }

        val target = project ?: ProjectManager.getInstance().openProjects.firstOrNull()
        val restarted = target != null && CodeBuddyBridge.restartAgent(target)
        if (restarted) PendingSwitch.clear()

        return HotSwitchResult(
            success = true,
            message = if (restarted) null
            else "凭据已更新，但 CodeBuddy 内核未重启，界面可能仍显示旧账号"
        )
    }

    // ---------------------------------------------------------------
    // 回滚
    // ---------------------------------------------------------------

    /**
     * 回滚到最近一次切换前的 secret-storage.xml。
     *
     * 每次写入前 SecretStorage 都会在同目录生成 secret-storage.xml.bak-<stamp>。
     */
    fun rollbackLastSwitch(): SwitchResult {
        synchronized(lock) {
            val target = SecretStorage.file()
                ?: return SwitchResult.Failure("找不到 secret-storage.xml")

            val dir = target.parent ?: return SwitchResult.Failure("路径异常")
            val latestBackup = try {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().startsWith("secret-storage.xml.bak-") }
                        .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                        .orElse(null)
                }
            } catch (e: Exception) {
                return SwitchResult.Failure("查找备份失败: ${e.message}")
            } ?: return SwitchResult.Failure("没有可回滚的备份")

            return try {
                Files.copy(latestBackup, target, StandardCopyOption.REPLACE_EXISTING)
                log.info("已从 $latestBackup 回滚")
                AccountEvents.fireAccountsChanged()
                SwitchResult.Success(target)
            } catch (e: Exception) {
                log.warn("回滚失败", e)
                SwitchResult.Failure("回滚失败: ${e.message}")
            }
        }
    }

    /** 是否存在可回滚的备份 */
    fun hasRollback(): Boolean {
        val target = SecretStorage.file() ?: return false
        val dir = target.parent ?: return false
        return try {
            Files.list(dir).use { s ->
                s.anyMatch { it.fileName.toString().startsWith("secret-storage.xml.bak-") }
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------
    // 别名
    // ---------------------------------------------------------------

    /**
     * 强制退出登录：清除 IDEA 版 CodeBuddy 的认证条目。
     *
     * 注意：不会删除账号库中的快照，用户随时可以切换回来。
     * 清除后需重启 IDE，CodeBuddy 会回到未登录状态。
     */
    fun forceLogout(): SwitchResult {
        synchronized(lock) {
            // 记录待生效登出：防止 IDE 退出时把内存中的登录态写回
            PendingSwitch.saveClear()

            val logoutResult = SecretStorage.clearCredentials().fold(
                onSuccess = { count ->
                    if (count == 0) SwitchResult.Failure("未找到可清除的登录凭据（可能已处于未登录状态）")
                    else SwitchResult.Success(SecretStorage.file() ?: java.nio.file.Paths.get(""))
                },
                onFailure = { SwitchResult.Failure("清除失败: ${it.message}") }
            )
            if (logoutResult is SwitchResult.Success) AccountEvents.fireAccountsChanged()
            return logoutResult
        }
    }

    /** 设置账号别名（空字符串表示清除） */
    fun setAlias(uid: String, alias: String): Boolean {
        synchronized(lock) {
            return try {
                val all = listSaved().map { if (it.accountKey == uid) it.copy(alias = alias.trim()) else it }
                if (all.none { it.accountKey == uid }) return false
                writeIndex(all)
                AccountEvents.fireAccountsChanged()
                true
            } catch (e: Exception) {
                log.warn("设置别名失败: $uid", e)
                false
            }
        }
    }

    // ---------------------------------------------------------------
    // 导出 / 导入
    // ---------------------------------------------------------------

    /**
     * 导出账号库为 JSON 字符串（含完整凭据，注意保密）。
     *
     * @param accountKeys 仅导出这些账号；为 null 时导出全部
     */
    fun exportJson(accountKeys: Set<String>? = null): String {
        synchronized(lock) {
            val arr = com.google.gson.JsonArray()
            for (s in listSaved()) {
                if (accountKeys != null && s.accountKey !in accountKeys) continue
                val o = com.google.gson.JsonObject()
                o.addProperty("uid", s.uid)
                o.addProperty("nickname", s.nickname)
                o.addProperty("uin", s.uin)
                o.addProperty("type", s.type)
                o.addProperty("phoneNumber", s.phoneNumber)
                o.addProperty("domain", s.domain)
                o.addProperty("alias", s.alias)
                o.addProperty("importedAt", s.importedAt)
                o.addProperty("rawContent", s.rawContent)
                arr.add(o)
            }
            return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(arr)
        }
    }

    /** 从导出的 JSON 导入账号库，返回导入数量 */
    fun importJson(json: String): Int {
        synchronized(lock) {
            return try {
                val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
                var count = 0
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val raw = if (o.has("rawContent") && !o.get("rawContent").isJsonNull) o.get("rawContent").asString else ""
                    if (raw.isBlank()) continue
                    val parsed = CredentialParser.parseContent(raw) ?: continue
                    val alias = if (o.has("alias") && !o.get("alias").isJsonNull) o.get("alias").asString else ""
                    if (importSnapshot(parsed.copy(alias = alias))) count++
                }
                count
            } catch (e: Exception) {
                log.warn("导入账号库失败", e)
                -1
            }
        }
    }

    // ---------------------------------------------------------------
    // 索引读写
    // ---------------------------------------------------------------

    /** 写索引：跨进程串行化 + 独立 tmp 文件名 + ATOMIC_MOVE */
    private fun writeIndex(items: List<CredentialSnapshot>) {
        val json = CredentialParser.serializeIndex(items)
        val write = { MultiInstanceCoordinator.writeAtomic(indexFile, json) }
        MultiInstanceCoordinator.withLockFor(indexFile) { write() } ?: write()
    }

    companion object {
        /** 全局并发上限：限制消耗明细翻页等重请求占满线程池 */
        private const val MAX_CONCURRENT_REQUESTS = 4
        private val requestLimiter = java.util.concurrent.Semaphore(MAX_CONCURRENT_REQUESTS, true)

        /** 统一的网络请求闸门：计数日志 + 并发上限 */
        private fun <T> limited(kind: String, block: () -> T): T {
            MultiInstanceCoordinator.noteRequest(kind)
            requestLimiter.acquire()
            try {
                return block()
            } finally {
                requestLimiter.release()
            }
        }

        fun getInstance(): AccountLibrary = ApplicationManager.getApplication().getService(AccountLibrary::class.java)
    }
}

sealed class SwitchResult {
    data class Success(val targetFile: Path) : SwitchResult()
    data class Failure(val message: String) : SwitchResult()
}

/** 热切换结果：success=true 表示不需要重启 IDE */
data class HotSwitchResult(
    val success: Boolean,
    /** 失败原因或部分成功提示；完全成功且内核已重启时为 null */
    val message: String?,
)
