package dev.codebuddy.account.pet

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 把桌宠接成 CodeBuddy 的 `PreToolUse` hook（**决策通道**，不是模拟点击）。
 *
 * 依据：CodeBuddy 官方 hook 协议 —— 用户级 `~/.codebuddy/settings.json` 里可直接写
 * `PreToolUse` 数组，hook 进程读 stdin 的 payload，向 stdout 输出
 * `hookSpecificOutput.permissionDecision = allow | deny | ask` 即等同于点完「允许」。
 *
 * 本类只负责两件事：
 *  1. 把打包的 [SCRIPT_RESOURCE] 释放到 `~/.codebuddy/codebuddy-pet/approve-hook.mjs`；
 *  2. 在 `~/.codebuddy/settings.json` 里幂等增删一条指向它的 hook（保留主人已有配置）。
 *
 * 决策本身由 [HookApprovalServer] 提供，所以这里不写死任何“放行”逻辑。
 */
object CodeBuddyHookInstaller {

    private val log = logger<CodeBuddyHookInstaller>()

    /** 桌宠审批服务端口，与 approve-hook.mjs 内的常量保持一致 */
    const val PORT = 8793

    /** 宿主侧 hook 超时（秒）；脚本内部自有更短的等待上限 */
    private const val HOOK_TIMEOUT_SECONDS = 90

    private const val SCRIPT_RESOURCE = "/pet/hook/approve-hook.mjs"
    private const val SCRIPT_NAME = "approve-hook.mjs"

    /** 用来识别「这条 hook 是桌宠装的」 */
    private const val MARKER = "codebuddy-pet/approve-hook.mjs"

    private const val EVENT = "PreToolUse"
    private const val SETTINGS_BACKUP = "settings.json.pet-bak"

    /**
     * 只挂「可能触发权限确认」的工具。
     *
     * PreToolUse 对每次工具调用都会执行，若用 `.*` 连 Read / Grep 都拦，
     * 就变成每次读文件都要点一次气泡；而这类工具本来不需要确认。
     */
    private const val MATCHER = "Bash|Write|Edit|MultiEdit|NotebookEdit"

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** `~/.codebuddy/codebuddy-pet` */
    fun dataDir(): Path = userHome().resolve(".codebuddy").resolve("codebuddy-pet")

    /** 释放到本地的 hook 脚本 */
    fun scriptFile(): Path = dataDir().resolve(SCRIPT_NAME)

    /** hook 事件诊断日志（核对宿主真实 payload 用） */
    fun eventsLog(): Path = dataDir().resolve("hook-events.jsonl")

    /** 用户级 CodeBuddy 配置 */
    fun settingsFile(): Path = userHome().resolve(".codebuddy").resolve("settings.json")

    /** 宿主需要 node 来跑 hook 脚本；找不到就整体降级为「不安装」 */
    fun nodeAvailable(): Boolean {
        val path = System.getenv("PATH") ?: return false
        val names = if (isWindows()) listOf("node.exe", "node.cmd", "node.bat") else listOf("node")
        return path.split(File.pathSeparatorChar).any { dir ->
            dir.isNotBlank() && names.any { name -> Files.isRegularFile(Paths.get(dir, name)) }
        }
    }

    /** 当前配置里是否已有桌宠的 hook */
    fun isInstalled(): Boolean = try {
        val root = readSettings() ?: return false
        val event = root.getAsJsonArray(EVENT) ?: return false
        event.any { group -> groupAsObject(group)?.let { containsMarker(it) } == true }
    } catch (e: Exception) {
        log.info("读取 CodeBuddy hook 配置失败: ${e.message}")
        false
    }

    /**
     * 释放脚本并写入 hook 配置（幂等）。
     *
     * @return true 表示配置已就绪
     */
    fun ensureInstalled(): Boolean {
        if (!nodeAvailable()) {
            log.info("未找到 node，跳过 CodeBuddy hook 安装")
            return false
        }
        val script = scriptFile()
        return try {
            Files.createDirectories(script.parent)
            val bytes = CodeBuddyHookInstaller::class.java
                .getResourceAsStream(SCRIPT_RESOURCE)
                ?.use { it.readBytes() }
                ?: run {
                    log.warn("插件资源缺失: $SCRIPT_RESOURCE")
                    return false
                }
            if (!Files.exists(script) || !Files.readAllBytes(script).contentEquals(bytes)) {
                Files.write(script, bytes)
            }
            writeHookConfig(script)
        } catch (e: Exception) {
            log.warn("安装 CodeBuddy hook 失败", e)
            false
        }
    }

    /** 移除桌宠写入的 hook，保留其它配置 */
    fun uninstall(): Boolean {
        val file = settingsFile()
        val root = try {
            readSettings() ?: return true
        } catch (e: Exception) {
            log.warn("卸载 CodeBuddy hook 失败：配置无法解析", e)
            return false
        }
        val event = root.getAsJsonArray(EVENT) ?: return true
        removeOwnEntries(event)
        if (event.size() == 0) root.remove(EVENT)
        return try {
            writeSettings(file, root)
            true
        } catch (e: Exception) {
            log.warn("写回 CodeBuddy 配置失败", e)
            false
        }
    }

    // ------------------------------------------------------------------

    private fun writeHookConfig(script: Path): Boolean {
        val file = settingsFile()
        val root = try {
            readSettings() ?: JsonObject()
        } catch (e: Exception) {
            // 配置存在但解析失败：绝不能覆盖主人的配置
            log.warn("CodeBuddy 配置无法解析，放弃写入 hook", e)
            return false
        }
        Files.createDirectories(file.parent)

        val event = root.getAsJsonArray(EVENT) ?: JsonArray().also { root.add(EVENT, it) }
        removeOwnEntries(event)

        val hook = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", commandOf(script))
            addProperty("description", MARKER)
            addProperty("timeout", HOOK_TIMEOUT_SECONDS)
        }
        val group = JsonObject().apply {
            addProperty("matcher", MATCHER)
            add("hooks", JsonArray().apply { add(hook) })
        }
        event.add(group)
        writeSettings(file, root)
        return true
    }

    /** `node "<abs path>" PreToolUse`；路径统一正斜杠，node 在 Windows 也接受 */
    private fun commandOf(script: Path): String =
        "node \"${script.toAbsolutePath().toString().replace('\\', '/')}\" $EVENT"

    private fun removeOwnEntries(event: JsonArray) {
        for (index in event.size() - 1 downTo 0) {
            val group = groupAsObject(event[index]) ?: continue
            val hooks = group.getAsJsonArray("hooks") ?: continue
            for (h in hooks.size() - 1 downTo 0) {
                val hook = groupAsObject(hooks[h]) ?: continue
                if (containsMarker(hook)) hooks.remove(h)
            }
            if (hooks.size() == 0) event.remove(index)
        }
    }

    private fun containsMarker(group: JsonObject): Boolean {
        val command = group.get("command")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        return command.contains(MARKER)
    }

    private fun groupAsObject(element: com.google.gson.JsonElement): JsonObject? =
        if (element.isJsonObject) element.asJsonObject else null

    private fun readSettings(): JsonObject? {
        val file = settingsFile()
        if (!Files.exists(file)) return null
        val text = Files.readString(file, StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return null
        val parsed = JsonParser.parseString(text)
        if (!parsed.isJsonObject) throw IllegalStateException("settings.json 顶层不是对象")
        return parsed.asJsonObject
    }

    private fun writeSettings(file: Path, root: JsonObject) {
        Files.createDirectories(file.parent)
        if (Files.exists(file)) {
            val backup = file.resolveSibling(SETTINGS_BACKUP)
            if (!Files.exists(backup)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.writeString(file, gson.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8)
    }

    private fun userHome(): Path = Paths.get(System.getProperty("user.home"))

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
