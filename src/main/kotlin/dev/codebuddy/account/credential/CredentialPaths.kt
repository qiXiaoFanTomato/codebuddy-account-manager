package dev.codebuddy.account.credential

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * CodeBuddy 凭据目录定位。
 *
 * 实测路径（Windows）：
 *   %LOCALAPPDATA%\CodeBuddyExtension\Data\Public\auth\
 *
 * 该目录下会有：
 *   - workbuddy-desktop.info              当前激活的凭据
 *   - workbuddy-desktop.info.bak          上一次的备份
 *   - workbuddy-desktop.<时间戳>.<pid>.<uuid>.info   历史登录快照
 */
object CredentialPaths {

    private const val EXTENSION_DIR = "CodeBuddyExtension"
    private const val AUTH_SUBDIR = "Data/Public/auth"
    private const val ACTIVE_FILE = "workbuddy-desktop.info"

    /** 凭据 auth 目录；若父目录不存在返回 null */
    fun authDir(): Path? {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?: return null
        val dir = Paths.get(localAppData, EXTENSION_DIR, *AUTH_SUBDIR.split("/").toTypedArray())
        return if (Files.isDirectory(dir)) dir else null
    }

    /** CodeBuddy 数据根目录（Data 目录），历史记录按账号 uid 存在其下 */
    fun dataRoot(): Path? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: return null
        val dir = Paths.get(localAppData, EXTENSION_DIR, "Data")
        return if (Files.isDirectory(dir)) dir else null
    }

    /** 当前激活的凭据文件 */
    fun activeFile(): Path? {
        val dir = authDir() ?: return null
        val f = dir.resolve(ACTIVE_FILE)
        return if (Files.isRegularFile(f)) f else null
    }

    /** 列出所有 .info 快照（包含 .info.bak），按最后修改时间倒序 */
    fun listSnapshotFiles(): List<Path> {
        val dir = authDir() ?: return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".info") || it.fileName.toString().endsWith(".info.bak") }
                    .sorted(compareByDescending { Files.getLastModifiedTime(it).toMillis() })
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 插件自己的备份目录，用于存放用户导入/保存的快照 */
    fun libraryDir(baseDir: Path): Path =
        baseDir.resolve("accounts").also { Files.createDirectories(it) }

    /**
     * 某个账号下的所有 history 根目录，顺序即优先级。
     *
     * 实测两种布局并存：
     *   Data/<uid>/JetBrains/<uid>/history      ← 新版 IDE 插件
     *   Data/<uid>/CodeBuddyIDE/<uid>/history   ← 旧版
     */
    fun historyRoots(dataRoot: Path, uid: String): List<Path> =
        listOf("JetBrains", "CodeBuddyIDE")
            .map { dataRoot.resolve(uid).resolve(it).resolve(uid).resolve("history") }
            .filter { Files.isDirectory(it) }
}
