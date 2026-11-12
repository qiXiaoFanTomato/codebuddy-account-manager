package dev.codebuddy.account.credential

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.service.MultiInstanceCoordinator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * IDEA 版 CodeBuddy 插件的凭据存储。
 *
 * 关键事实（经实测验证）：
 * - 凭据不在 %LOCALAPPDATA%\CodeBuddyExtension（那是 WorkBuddy 桌面端）
 * - IDEA 插件使用自定义 PersistentStateComponent，把会话 JSON 存在
 *   <config>/options/secret-storage.xml 的 Entry 里
 * - Entry key 固定为：Tencent-Cloud.coding-copilot.new.accessToken
 * - value 是 JSON 字符串（XML 转义后）
 */
object SecretStorage {

    private val log = logger<SecretStorage>()

    const val ENTRY_KEY = "Tencent-Cloud.coding-copilot.new.accessToken"

    fun file(): Path? {
        return try {
            val configDir = PathManager.getConfigDir()
            val f = configDir.resolve("options").resolve("secret-storage.xml")
            if (Files.isRegularFile(f)) f else fallback()
        } catch (_: Exception) {
            fallback()
        }
    }

    private fun fallback(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val f = Paths.get(home, "AppData", "Roaming", "JetBrains", "IntelliJIdea2026.2", "options", "secret-storage.xml")
        return if (Files.isRegularFile(f)) f else null
    }

    /** 读取当前激活的凭据快照 */
    fun readCurrent(): CredentialSnapshot? {
        val f = file() ?: return null
        return try {
            val xml = Files.readString(f, StandardCharsets.UTF_8)
            val json = extractEntryValue(xml, ENTRY_KEY) ?: return null
            CredentialParser.parseContent(json, f.toString())
        } catch (e: Exception) {
            log.warn("读取 secret-storage 失败", e)
            null
        }
    }

    /** 从 XML 中提取指定 key 的 value（已反转义） */
    fun extractEntryValue(xml: String, key: String): String? {
        val regex = Regex("<Entry\\s+key=\"" + Regex.escape(key) + "\"\\s+value=\"([^\"]*)\"\\s*/>")
        val m = regex.find(xml) ?: return null
        return unescapeXml(m.groupValues[1])
    }

    /** 写入新的凭据 JSON，替换掉旧条目；跨进程串行化「读-改-写」 */
    fun writeCurrent(snapshotJson: String): Result<Path> {
        val f = file() ?: return Result.failure(IllegalStateException("找不到 secret-storage.xml，请先登录一次 CodeBuddy"))
        val locked = MultiInstanceCoordinator.withLockFor(f) { writeCurrentLocked(f, snapshotJson) }
        return locked ?: writeCurrentLocked(f, snapshotJson)
    }

    private fun writeCurrentLocked(f: Path, snapshotJson: String): Result<Path> {
        return try {
            val xml = Files.readString(f, StandardCharsets.UTF_8)
            val escaped = escapeXml(snapshotJson)
            val newEntry = "<Entry key=\"" + ENTRY_KEY + "\" value=\"" + escaped + "\" />"
            val regex = Regex("<Entry\\s+key=\"" + Regex.escape(ENTRY_KEY) + "\"\\s+value=\"[^\"]*\"\\s*/>")

            val newXml = if (regex.containsMatchIn(xml)) {
                regex.replace(xml) { newEntry }
            } else {
                xml.replaceFirst("<Scores>", "<Scores>\n      " + newEntry)
            }

            val backup = f.resolveSibling("secret-storage.xml.bak-" + System.currentTimeMillis())
            Files.copy(f, backup, StandardCopyOption.REPLACE_EXISTING)

            val tmp = f.resolveSibling("secret-storage.xml.tmp-" + System.nanoTime())
            Files.writeString(tmp, newXml, StandardCharsets.UTF_8)
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
            }
            Result.success(f)
        } catch (e: Exception) {
            log.warn("写入 secret-storage 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 强制清除登录态：删除认证相关条目，保留模型/UI 缓存。
     *
     * 返回被删除的 key 数量。
     */
    fun clearCredentials(): Result<Int> {
        val f = file() ?: return Result.failure(IllegalStateException("找不到 secret-storage.xml"))
        val locked = MultiInstanceCoordinator.withLockFor(f) { clearCredentialsLocked(f) }
        return locked ?: clearCredentialsLocked(f)
    }

    private fun clearCredentialsLocked(f: Path): Result<Int> {
        return try {
            val xml = Files.readString(f, StandardCharsets.UTF_8)
            var removed = 0

            val regex = Regex("<Entry\\s+key=\"(Tencent-Cloud\\.coding-copilot[^\"]*)\"\\s+value=\"[^\"]*\"\\s*/>\\s*")
            val newXml = regex.replace(xml) {
                removed++
                ""
            }

            if (removed == 0) return Result.success(0)

            val backup = f.resolveSibling("secret-storage.xml.bak-" + System.currentTimeMillis())
            Files.copy(f, backup, StandardCopyOption.REPLACE_EXISTING)

            val tmp = f.resolveSibling("secret-storage.xml.tmp-" + System.nanoTime())
            Files.writeString(tmp, newXml, StandardCharsets.UTF_8)
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
            }
            log.info("已清除 CodeBuddy 凭据条目: " + removed)
            Result.success(removed)
        } catch (e: Exception) {
            log.warn("清除凭据失败", e)
            Result.failure(e)
        }
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun unescapeXml(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
