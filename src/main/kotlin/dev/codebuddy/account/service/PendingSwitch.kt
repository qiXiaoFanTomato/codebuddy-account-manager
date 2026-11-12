package dev.codebuddy.account.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 待生效的凭据操作。
 *
 * 为什么需要它：
 * IDEA 运行期间，CodeBuddy 插件把 SecretStorage 的状态缓存在内存里。
 * IDEA 正常退出时可能把内存中的旧状态写回 secret-storage.xml，
 * 覆盖掉我们做的切换或登出操作。
 *
 * 解决方式：
 *  - 操作时记录一份 pending-switch.json（含 action）
 *  - 下次 IDE 启动时检查，若文件状态与预期不符，则补做一次
 */
object PendingSwitch {

    private val log = logger<PendingSwitch>()

    const val ACTION_WRITE = "write"
    const val ACTION_CLEAR = "clear"

    private val file: Path by lazy {
        PathManager.getConfigDir().resolve("codebuddy-accounts").resolve("pending-switch.json")
    }

    data class Pending(
        val action: String,
        val uid: String,
        val nickname: String,
        /** write 时为要写入的 JSON；clear 时为空 */
        val json: String,
        val createdAt: Long,
    )

    fun saveWrite(uid: String, nickname: String, json: String) = save(ACTION_WRITE, uid, nickname, json)

    fun saveClear() = save(ACTION_CLEAR, "", "", "")

    private fun save(action: String, uid: String, nickname: String, json: String) {
        val o = JsonObject()
        o.addProperty("action", action)
        o.addProperty("uid", uid)
        o.addProperty("nickname", nickname)
        o.addProperty("json", json)
        o.addProperty("createdAt", System.currentTimeMillis())
        // 跨进程串行化 + tmp/ATOMIC_MOVE，避免多开时读到半文件或写互相覆盖
        val write = { MultiInstanceCoordinator.writeAtomic(file, o.toString()) }
        try {
            MultiInstanceCoordinator.withLockFor(file) { write() } ?: write()
        } catch (e: Exception) {
            log.warn("写入 pending-switch 失败", e)
        }
    }

    fun load(): Pending? {
        if (!Files.isRegularFile(file)) return null
        return try {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
            Pending(
                action = root.get("action")?.asString ?: ACTION_WRITE,
                uid = root.get("uid")?.asString ?: "",
                nickname = root.get("nickname")?.asString ?: "",
                json = root.get("json")?.asString ?: "",
                createdAt = root.get("createdAt")?.asLong ?: 0L,
            )
        } catch (e: Exception) {
            log.warn("读取 pending-switch 失败", e)
            null
        }
    }

    fun clear() {
        try {
            val remove = { Files.deleteIfExists(file) }
            MultiInstanceCoordinator.withLockFor(file) { remove() } ?: remove()
        } catch (_: Exception) { }
    }
}
