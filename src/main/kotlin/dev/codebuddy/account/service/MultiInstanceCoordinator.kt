package dev.codebuddy.account.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 多开（同机多个 IDE 进程）协调基座。
 *
 * 目录固定在系统临时目录，与 IDE 产品、版本、config 目录无关，
 * 因此「同 IDE 多实例」与「IDEA + PyCharm 混开」都会命中同一份协调状态。
 * 跨机器场景没有共享文件系统，不在本类的能力范围内。
 *
 * 提供四类能力：
 *  1. 短时跨进程 [FileLock]：保护共享文件的「读-改-写」临界区
 *  2. 租约式主实例判定：自动刷新只由一个进程执行，进程退出后租约过期可接管
 *  3. 最小间隔闸门 / 按日标记：抑制跨进程重复请求与重复写操作
 *  4. 共享配额结果缓存：非主实例可复用主实例的刷新结果，界面不留空
 */
object MultiInstanceCoordinator {

    private val log = logger<MultiInstanceCoordinator>()

    /** 主实例租约时长，需大于单轮全账号刷新耗时 */
    const val LEASE_MS = 10 * 60_000L
    /** 租约续期间隔 */
    const val RENEW_MS = 60_000L
    /** 共享配额结果有效期，与默认自动刷新间隔对齐 */
    const val QUOTA_SHARED_TTL_MS = 15 * 60_000L
    /** 同一账号自动配额刷新的最小间隔，防止多进程同刻重复 */
    const val QUOTA_MIN_INTERVAL_MS = 30_000L

    private val instanceUuid: String = UUID.randomUUID().toString().take(8)
    private val pid: Long = ProcessHandle.current().pid()
    private val jvmLock = Any()
    /** 同线程已持有的锁名，支持「读-改-写」外层持锁时内层重复进入 */
    private val heldLocks = ThreadLocal.withInitial { mutableSetOf<String>() }

    private val baseDir: Path by lazy {
        Paths.get(System.getProperty("java.io.tmpdir"), "codebuddy-account")
    }
    private val stateFile: Path by lazy { baseDir.resolve("coordinator.json") }

    /** 协调状态固定用同一把锁，保证租约与闸门读写的原子性 */
    private const val STATE_LOCK_NAME = "coordinator-state"

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** 日志用的实例标识，便于区分多开进程的请求来源 */
    fun instanceTag(): String = "pid=$pid inst=$instanceUuid"

    data class LeaderState(
        val isLeader: Boolean,
        val ownerPid: Long,
        val remainingMs: Long,
    )

    /**
     * 获取跨进程文件锁并执行 [action]。
     * 锁文件按 [name] 派生，不同共享文件互不影响；限时等待，
     * 拿不到锁返回 null，由调用方降级处理，不阻塞业务流程。
     */
    fun <T> withLock(name: String, action: () -> T): T? = synchronized(jvmLock) {
        // 同线程重入：外层「读-改-写」已持锁时，内层无需再抢同一把文件锁
        val held = heldLocks.get()
        if (name in held) return@synchronized action()
        var channel: FileChannel? = null
        var lock: FileLock? = null
        try {
            Files.createDirectories(baseDir)
            val file = baseDir.resolve("lock-" + Integer.toHexString(name.hashCode()) + ".lock")
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            var attempt = 0
            while (lock == null && attempt < LOCK_RETRIES) {
                lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    Thread.sleep(LOCK_RETRY_MS)
                    attempt++
                }
            }
            if (lock == null) {
                log.warn("获取跨进程锁超时: $name, ${instanceTag()}")
                null
            } else {
                held.add(name)
                try {
                    action()
                } finally {
                    held.remove(name)
                }
            }
        } catch (e: Exception) {
            log.warn("跨进程锁操作失败: $name", e)
            null
        } finally {
            try { lock?.release() } catch (_: Exception) { }
            try { channel?.close() } catch (_: Exception) { }
        }
    }

    /** 按目标文件路径派生一把锁，不同共享文件互不影响 */
    fun <T> withLockFor(target: Path, action: () -> T): T? =
        withLock("file-" + Integer.toHexString(target.toString().lowercase().hashCode()), action)

    /** 协调状态（租约、闸门、按日标记）专用锁 */
    private fun <T> withStateLock(action: () -> T): T? = withLock(STATE_LOCK_NAME, action)

    /** 原子写入：tmp + ATOMIC_MOVE，失败退化为普通覆盖 */
    fun writeAtomic(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp-" + System.nanoTime())
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 判定本进程是否为主实例并续约。
     * 租约过期时允许其它进程接管，无需长期持有文件锁。
     * 锁不可用时降级为 true，避免出现所有实例都不刷新的死局。
     */
    fun isLeader(): Boolean = withStateLock {
        val state = readState()
        val now = System.currentTimeMillis()
        val mine = state.str("leaderUuid") == instanceUuid
        val expired = state.lng("leaseUntil") <= now
        if (!mine && !expired) return@withStateLock false
        state.addProperty("leaderUuid", instanceUuid)
        state.addProperty("leaderPid", pid)
        state.addProperty("leaseUntil", now + LEASE_MS)
        writeState(state)
        true
    } ?: true

    /** 定时续约：仅当前持有者写，不抢占其它进程的租约 */
    fun renewLeader() {
        withStateLock {
            val state = readState()
            if (state.str("leaderUuid") != instanceUuid) return@withStateLock
            state.addProperty("leaseUntil", System.currentTimeMillis() + LEASE_MS)
            writeState(state)
        }
    }

    /** 只读的主实例状态，供设置页展示 */
    fun leaderState(): LeaderState = withStateLock {
        val state = readState()
        val now = System.currentTimeMillis()
        val mine = state.str("leaderUuid") == instanceUuid && state.lng("leaseUntil") > now
        LeaderState(
            isLeader = mine,
            ownerPid = state.lng("leaderPid"),
            remainingMs = (state.lng("leaseUntil") - now).coerceAtLeast(0L),
        )
    } ?: LeaderState(true, pid, 0L)

    /**
     * 最小间隔闸门：同一 [kind + key] 在 [intervalMs] 内只放行一次。
     * 失败时调用 [releaseClaim] 允许立即重试。
     */
    fun claim(kind: String, key: String, intervalMs: Long): Boolean = withStateLock {
        val state = readState()
        val claims = state.obj("claims")
        val k = "$kind|$key"
        val now = System.currentTimeMillis()
        if (now - claims.lng(k) < intervalMs) return@withStateLock false
        claims.addProperty(k, now)
        state.add("claims", claims)
        writeState(state)
        true
    } ?: true

    /** 释放闸门占用，使失败操作可以立即重试 */
    fun releaseClaim(kind: String, key: String) {
        withStateLock {
            val state = readState()
            val claims = state.obj("claims")
            if (claims.remove("$kind|$key") != null) {
                state.add("claims", claims)
                writeState(state)
            }
        }
    }

    /** 按日标记：同一 [kind + key] 当天只放行一次，用于签到等幂等性弱的写接口 */
    fun claimDaily(kind: String, key: String, date: String): Boolean = withStateLock {
        val state = readState()
        val daily = state.obj("daily")
        val k = "$kind|$key|$date"
        if (daily.has(k)) return@withStateLock false
        daily.addProperty(k, System.currentTimeMillis())
        // 清掉非当天的旧标记，避免状态文件无限增长
        daily.keySet().filterNot { it.endsWith("|$date") }.forEach { daily.remove(it) }
        state.add("daily", daily)
        writeState(state)
        true
    } ?: true

    /** 释放按日标记，写操作失败时允许重试 */
    fun releaseDaily(kind: String, key: String, date: String) {
        withStateLock {
            val state = readState()
            val daily = state.obj("daily")
            if (daily.remove("$kind|$key|$date") != null) {
                state.add("daily", daily)
                writeState(state)
            }
        }
    }

    /**
     * 全局共享的配额结果缓存，按账号库指纹隔离。
     * 账号库变化会换一份新缓存，旧数据自然失效。
     */
    fun sharedQuotaCache(accountKeys: Collection<String>): QuotaCache? {
        if (accountKeys.isEmpty()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(accountKeys.sorted().joinToString("|").toByteArray(StandardCharsets.UTF_8))
            val id = digest.take(6).joinToString("") { "%02x".format(it) }
            val dir = baseDir.resolve("shared-$id")
            Files.createDirectories(dir)
            QuotaCache(dir.resolve("quota-cache.json"))
        } catch (e: Exception) {
            log.warn("创建共享配额缓存失败", e)
            null
        }
    }

    /** 接口调用计数：低频打日志，避免日志本身成为资源开销 */
    fun noteRequest(kind: String) {
        val n = counters.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet()
        if (n == 1L || n % 50L == 0L) {
            log.info("接口调用统计 [$kind] 累计 $n 次，${instanceTag()}")
        }
    }

    /** 当前进程的接口调用计数快照 */
    fun requestStats(): Map<String, Long> = counters.mapValues { it.value.get() }

    private fun readState(): JsonObject {
        if (!Files.isRegularFile(stateFile)) return JsonObject()
        return try {
            val el = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8))
            if (el.isJsonObject) el.asJsonObject else JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
    }

    private fun writeState(state: JsonObject) {
        writeAtomic(stateFile, state.toString())
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.lng(key: String): Long = try {
        if (has(key) && !get(key).isJsonNull) get(key).asLong else 0L
    } catch (_: Exception) {
        0L
    }

    private fun JsonObject.obj(key: String): JsonObject =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

    private const val LOCK_RETRIES = 20
    private const val LOCK_RETRY_MS = 50L
}
