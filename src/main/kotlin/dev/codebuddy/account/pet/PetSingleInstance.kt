package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * 跨进程单例：多个 IDE 实例同时打开时，全机只允许一个桌宠。
 *
 * IDEA 的 `@Service(Level.APPLICATION)` 只在**单进程内**唯一，多开无效，
 * 因此必须用操作系统级别的独占资源：
 *
 * 1. **文件独占锁**（唯一权威）：`<tmp>/codebuddy-pet/pet.lock` 上的 [FileLock]。
 *    路径在系统临时目录，与 IDE 产品、版本、配置目录无关，
 *    所以 IDEA 多开、IDEA + PyCharm 混开都会命中同一把锁。
 *    进程异常退出时 OS 自动释放锁，不会出现「永久没有桌宠」的死局。
 * 2. **固定回环端口**（辅助）：锁的持有者额外绑定 127.0.0.1 上的固定端口，
 *    作为第二道校验，也为后续「其他实例把点击事件转发给持有者」留出通道。
 *    端口绑定失败不影响判定，锁仍然是唯一标准。
 */
class PetSingleInstance {

    private val log = logger<PetSingleInstance>()

    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var server: ServerSocket? = null

    /** 当前进程是否持有桌宠所有权（锁仍有效才算） */
    val isOwner: Boolean
        get() = fileLock?.isValid == true

    /**
     * 尝试成为桌宠持有者。
     *
     * @return true 表示本进程持有所有权（可能是刚刚获取，也可能是此前已持有）
     */
    fun tryAcquire(): Boolean {
        if (isOwner) return true
        return try {
            val file = lockFile()
            Files.createDirectories(file.parent)
            val ch = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                ch.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                ch.close()
                false
            } else {
                channel = ch
                fileLock = lock
                bindPort()
                log.info("已获得桌宠所有权: $file")
                true
            }
        } catch (e: Exception) {
            log.warn("获取桌宠所有权失败", e)
            false
        }
    }

    /** 释放所有权：关闭端口与锁，锁文件本身保留（下次直接复用） */
    fun release() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null

        try {
            fileLock?.release()
        } catch (_: Exception) {
        }
        fileLock = null

        try {
            channel?.close()
        } catch (_: Exception) {
        }
        channel = null
    }

    private fun lockFile(): Path =
        Paths.get(System.getProperty("java.io.tmpdir"), LOCK_DIR, LOCK_NAME)

    /** 绑定回环端口作为辅助标记；失败只记日志，不影响所有权 */
    private fun bindPort() {
        if (server != null) return
        server = try {
            ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())
        } catch (e: Exception) {
            log.info("桌宠标记端口不可用（不影响单例判定）: ${e.message}")
            null
        }
    }

    companion object {
        private const val LOCK_DIR = "codebuddy-pet"
        private const val LOCK_NAME = "pet.lock"

        /** 固定端口，仅作为所有权辅助标记 */
        private const val PORT = 47653
    }
}
