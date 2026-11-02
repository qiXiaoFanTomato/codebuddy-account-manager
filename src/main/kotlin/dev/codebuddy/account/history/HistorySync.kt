package dev.codebuddy.account.history

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import dev.codebuddy.account.credential.CredentialPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 历史会话索引同步。
 *
 * 目录结构（实测）：历史索引按「账号 + 版本」分布，两种布局并存
 *   Data/<uid>/JetBrains/<uid>/history/<ws>/index.json       ← 新版（主）
 *   Data/<uid>/CodeBuddyIDE/<uid>/history/<ws>/index.json    ← 旧版
 *
 * 本类的作用：把各账号的会话索引与本地已有的「全量索引」合并，
 * 让切号后侧边栏也能看到全部历史会话。
 *
 * [copyMessages] = true 时，额外把目标账号缺失的会话目录（含 messages）从源账号复制过来，
 * 规避「列表看得到、点开空白」的问题。复制只新增、不覆盖、不删除。
 */
object HistorySync {

    private val log = logger<HistorySync>()

    /** 单个会话最多复制的文件数，防止异常大目录拖死 IO */
    private const val MAX_FILES_PER_CONV = 2000

    /** 合并后的统计 */
    data class Result(
        val workspacesScanned: Int,
        val workspacesUpdated: Int,
        val conversationsAdded: Int,
        val error: String? = null,
        /** 实际复制了消息目录的会话数 */
        val conversationsCopied: Int = 0,
    )

    /** 会话对象 + 其来源目录（用于复制消息） */
    private data class ConvEntry(val obj: JsonObject, val sourceDir: Path?)

    /**
     * 以所有已知账号的数据为基础，把索引合并进 [targetUid]。
     *
     * @param dataRoot      CodeBuddyExtension/Data 目录
     * @param targetUid     要补齐索引的目标账号（通常是即将激活 / 当前激活的账号）
     * @param allUids       所有已知账号 uid，用于汇集全量索引
     * @param copyMessages  是否复制目标账号缺失的会话消息目录
     */
    fun syncIndexes(
        dataRoot: Path,
        targetUid: String,
        allUids: Collection<String>,
        copyMessages: Boolean = false,
    ): Result {
        return try {
            // 收集所有账号下 每个工作区 -> 会话列表
            val merged = mutableMapOf<String, MutableMap<String, ConvEntry>>() // ws -> (convId -> entry)

            var scanned = 0
            for (uid in allUids) {
                for (historyRoot in CredentialPaths.historyRoots(dataRoot, uid)) {
                    val wsDirs = try {
                        Files.list(historyRoot).use { it.toList() }
                    } catch (e: Exception) {
                        log.warn("列出历史工作区失败: $historyRoot", e)
                        continue
                    }
                    for (wsDir in wsDirs) {
                        if (!Files.isDirectory(wsDir)) continue
                        scanned++
                        val ws = wsDir.fileName.toString()
                        val indexFile = wsDir.resolve("index.json")
                        if (!Files.isRegularFile(indexFile)) continue
                        val convMap = merged.getOrPut(ws) { mutableMapOf() }
                        try {
                            val root = JsonParser.parseString(Files.readString(indexFile, StandardCharsets.UTF_8)).asJsonObject
                            val arr = root.getAsJsonArray("conversations") ?: continue
                            for (el in arr) {
                                if (!el.isJsonObject) continue
                                val o = el.asJsonObject
                                val id = o.get("id")?.asString ?: continue
                                // 后写入的覆盖先写入的：优先保留消息更晚的
                                val existing = convMap[id]
                                if (existing == null || lastMessageAt(o) > lastMessageAt(existing.obj)) {
                                    val dir = wsDir.resolve(id)
                                    convMap[id] = ConvEntry(o, if (Files.isDirectory(dir)) dir else null)
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("读取索引失败: $indexFile", e)
                        }
                    }
                }
            }

            // 写回到目标账号（优先新版 JetBrains 目录）
            var updated = 0
            var added = 0
            var copied = 0
            val targetHistory = CredentialPaths.historyRoots(dataRoot, targetUid).firstOrNull()
            if (targetHistory == null) {
                return Result(scanned, 0, 0, "目标账号历史目录不存在（Data/$targetUid/JetBrains|CodeBuddyIDE）")
            }

            for ((ws, convMap) in merged) {
                val wsDir = targetHistory.resolve(ws)
                // 只处理目标账号已存在的工作区目录，避免凭空创建
                if (!Files.isDirectory(wsDir)) continue

                val indexFile = wsDir.resolve("index.json")
                val existingIds = mutableSetOf<String>()
                val existingCurrent: String? = try {
                    if (Files.isRegularFile(indexFile)) {
                        val root = JsonParser.parseString(Files.readString(indexFile, StandardCharsets.UTF_8)).asJsonObject
                        root.getAsJsonArray("conversations")?.forEach { el ->
                            el.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asString?.let { existingIds.add(it) }
                        }
                        root.get("current")?.takeIf { !it.isJsonNull }?.asString
                    } else null
                } catch (_: Exception) { null }

                val toAdd = convMap.keys - existingIds
                if (toAdd.isEmpty()) continue

                // 重建索引
                val root = JsonObject()
                val arr = JsonArray()
                for ((_, entry) in convMap) arr.add(entry.obj)
                root.add("conversations", arr)
                if (existingCurrent != null) root.addProperty("current", existingCurrent)

                // 覆盖前留一份可回滚备份
                if (Files.isRegularFile(indexFile)) {
                    try {
                        Files.copy(indexFile, wsDir.resolve("index.json.bak"), StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: Exception) {
                        log.warn("备份索引失败: $indexFile", e)
                    }
                }

                val tmp = wsDir.resolve("index.json.tmp-sync")
                Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8)
                try {
                    Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING)
                }
                updated++
                added += toAdd.size

                // 补齐缺失的会话消息目录
                if (copyMessages) {
                    for (id in toAdd) {
                        val src = convMap[id]?.sourceDir ?: continue
                        val dst = wsDir.resolve(id)
                        if (Files.isDirectory(dst.resolve("messages"))) continue
                        try {
                            copyDir(src, dst)
                            copied++
                        } catch (e: Exception) {
                            log.warn("复制会话目录失败: $src -> $dst", e)
                        }
                    }
                }
            }

            Result(scanned, updated, added, null, copied)
        } catch (e: Exception) {
            log.warn("同步历史索引失败", e)
            Result(0, 0, 0, e.message)
        }
    }

    /** 递归复制目录；已存在的目标文件跳过（只新增，不覆盖） */
    private fun copyDir(src: Path, dst: Path): Int {
        var count = 0
        Files.createDirectories(dst)
        Files.walk(src).use { stream ->
            for (f in stream) {
                if (count >= MAX_FILES_PER_CONV) break
                val target = dst.resolve(src.relativize(f).toString())
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target)
                    continue
                }
                if (Files.exists(target)) continue
                Files.createDirectories(target.parent)
                Files.copy(f, target)
                count++
            }
        }
        return count
    }

    private fun lastMessageAt(o: JsonObject): Long = try {
        val s = o.get("lastMessageAt")?.asString ?: return 0L
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) { 0L }
}
