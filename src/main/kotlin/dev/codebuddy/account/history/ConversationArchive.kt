package dev.codebuddy.account.history

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把本地历史会话转换成 CodeBuddy 官方「导入对话」可识别的归档文件。
 *
 * 归档结构（对照 CodeBuddy 插件自身的导入校验，勿随意改动）：
 * ```
 * {
 *   "schema": "codebuddy.conversation",
 *   "schemaVersion": 1,
 *   "exportedAt": "<ISO-8601>",
 *   "data": {
 *     "lastMessageAt": "<ISO-8601>",
 *     "conversations": [ { id, type, name, createdAt, lastMessageAt, requests: [
 *         { id, type, state, usage?, messages: [ { id, role, message, extra?, ... } ] } ] } ]
 *   }
 * }
 * ```
 * 约束：
 *  - 单个 .json 只允许 1 个会话，且总大小不超过 20MB
 *  - 批量导入走 .zip（每个 entry 是一个单独的 .json，同样不超过 20MB）
 *  - id 只能由 `[a-zA-Z0-9-_]` 组成；role ∈ user/assistant/tool/system；
 *    request.state ∈ running/complete/aborted/canceled；message 必须是字符串
 *
 * 因此这里直接复用本地存储的原始结构：会话级 `index.json` 给出 requests 与消息顺序，
 * `messages/<messageId>.json` 就是官方导出里的 message 对象（`message` 字段本就是字符串）。
 */
object ConversationArchive {

    private val log = logger<ConversationArchive>()

    private const val SCHEMA = "codebuddy.conversation"
    private const val SCHEMA_VERSION = 1

    /** 官方导入上限：单个归档 20MB */
    private const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024L

    /** 单条消息文件上限，与 HistoryReader 保持一致 */
    private const val MAX_MESSAGE_FILE_BYTES = 2L * 1024 * 1024L

    private val ID_PATTERN = Regex("^[a-zA-Z0-9-_]+$")
    private val ROLES = setOf("user", "assistant", "tool", "system")
    private val MESSAGE_STATUSES = setOf("active", "deprecate", "deleted")
    private val REQUEST_STATES = setOf("running", "complete", "aborted", "canceled")
    private val USAGE_KEYS = listOf(
        "inputTokens", "outputTokens", "totalTokens", "lastTokens",
        "cacheTokens", "cachedWriteTokens", "cachedMissTokens", "credit",
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

    /** 一个待写入磁盘的归档文件（json 文本 + 会话名，用于生成文件名） */
    data class Artifact(val conversationName: String, val json: String)

    /** 生成结果：成功产出的归档 + 失败原因（会话名：原因） */
    data class Outcome(val artifacts: List<Artifact>, val failures: List<String>)

    /**
     * 为若干会话生成归档文本。可能耗时（要读消息文件），调用方应在后台线程执行。
     */
    fun build(
        conversations: List<HistoryReader.Conversation>,
        cancel: AtomicBoolean? = null,
    ): Outcome {
        val artifacts = mutableListOf<Artifact>()
        val failures = mutableListOf<String>()
        val exportedAt = Instant.now().toString()
        for (conv in conversations) {
            if (cancel?.get() == true) break
            try {
                val json = buildOne(conv, exportedAt)
                if (json == null) {
                    failures.add("${conv.name}：缺少会话索引或消息目录")
                } else {
                    artifacts.add(Artifact(conv.name, json))
                }
            } catch (t: Throwable) {
                log.warn("生成对话归档失败: ${conv.id}", t)
                failures.add("${conv.name}：${t.message ?: t.javaClass.simpleName}")
            }
        }
        return Outcome(artifacts, failures)
    }

    /** 单个会话的归档文本；结构不可用时返回 null，超限时抛异常 */
    private fun buildOne(conv: HistoryReader.Conversation, exportedAt: String): String? {
        val messageDir = conv.messageDir ?: return null
        val convDir = messageDir.parent ?: return null
        val index = readObject(convDir.resolve("index.json")) ?: return null
        val convId = archiveId(conv.id) ?: return null

        val requests = JsonArray()
        val usedRequestIds = HashSet<String>()
        val usedMessageIds = HashSet<String>()
        var budget = MAX_ARCHIVE_BYTES - 1

        val declared = index.getAsJsonArray("requests")
        if (declared != null) {
            for (el in declared) {
                if (!el.isJsonObject) continue
                val r = el.asJsonObject
                val requestId = archiveId(str(r, "id")) ?: continue
                if (!usedRequestIds.add(requestId)) continue

                val idsEl = r.getAsJsonArray("messages")
                val messages = JsonArray()
                if (idsEl != null) {
                    for (idEl in idsEl) {
                        if (!idEl.isJsonPrimitive) continue
                        val messageId = idEl.asString
                        if (messageId.isBlank() || !usedMessageIds.add(messageId)) continue
                        val file = messageDir.resolve("$messageId.json")
                        val size = sizeOf(file)
                        if (size <= 0L || size > MAX_MESSAGE_FILE_BYTES) continue
                        if (size > budget) break
                        val message = readObject(file)?.let { toArchiveMessage(it, messageId) } ?: continue
                        budget -= size
                        messages.add(message)
                    }
                }
                if (messages.size() == 0) continue

                val out = JsonObject()
                out.addProperty("id", requestId)
                out.addProperty("type", str(r, "type")?.takeIf { it.isNotBlank() } ?: conv.type.ifBlank { "craft" })
                out.addProperty("state", str(r, "state")?.takeIf { it in REQUEST_STATES } ?: "complete")
                out.add("messages", messages)
                usageOf(r)?.let { out.add("usage", it) }
                requests.add(out)
            }
        }

        // 没有可用 requests（老版本数据 / 索引损坏）：把消息目录里的文件合成一轮对话
        if (requests.size() == 0) {
            val fallback = buildFallbackRequest(conv, messageDir, usedMessageIds)
            if (fallback != null) requests.add(fallback)
        }
        if (requests.size() == 0) return null

        val conversation = JsonObject()
        conversation.addProperty("id", convId)
        conversation.addProperty("type", conv.type.ifBlank { conv.chatMode.ifBlank { "craft" } })
        conversation.addProperty("name", conv.name.ifBlank { "(未命名)" })
        conversation.addProperty(
            "createdAt",
            conv.createdAt.ifBlank { conv.lastMessageAt }.ifBlank { exportedAt },
        )
        conversation.addProperty(
            "lastMessageAt",
            conv.lastMessageAt.ifBlank { conv.createdAt }.ifBlank { exportedAt },
        )
        copyStringMap(index, "modelMap", conversation)
        copyBooleanMap(index, "maxModeMap", conversation)
        copyStringMap(index, "effortMap", conversation)
        copyStringMap(index, "contextLengthMap", conversation)
        conversation.add("requests", requests)

        val data = JsonObject()
        data.addProperty(
            "lastMessageAt",
            conversation.get("lastMessageAt").asString,
        )
        data.add("conversations", JsonArray().apply { add(conversation) })

        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        root.addProperty("schemaVersion", SCHEMA_VERSION)
        root.addProperty("exportedAt", exportedAt)
        root.add("data", data)

        val json = gson.toJson(root)
        val bytes = json.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > MAX_ARCHIVE_BYTES) {
            throw IllegalStateException("内容超过 20MB 上限（当前 ${bytes / 1024 / 1024}MB）")
        }
        return json
    }

    /** 索引里没有 requests 时：按文件时间排序，所有消息放进一轮 complete 请求 */
    private fun buildFallbackRequest(
        conv: HistoryReader.Conversation,
        messageDir: Path,
        usedMessageIds: MutableSet<String>,
    ): JsonObject? {
        val files = try {
            Files.list(messageDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        } catch (_: Exception) {
            return null
        }
        if (files.isEmpty()) return null
        val ordered = files.sortedBy { mtimeOf(it) }
        val messages = JsonArray()
        var budget = MAX_ARCHIVE_BYTES - 1
        for (file in ordered) {
            val name = file.fileName?.toString() ?: continue
            if (!name.endsWith(".json", ignoreCase = true)) continue
            val messageId = name.substring(0, name.length - 5)
            if (!usedMessageIds.add(messageId)) continue
            val size = sizeOf(file)
            if (size <= 0L || size > MAX_MESSAGE_FILE_BYTES) continue
            if (size > budget) break
            val message = readObject(file)?.let { toArchiveMessage(it, messageId) } ?: continue
            budget -= size
            messages.add(message)
        }
        if (messages.size() == 0) return null
        val requestId = archiveId("r-${conv.id}") ?: "r-${Instant.now().toEpochMilli()}"
        return JsonObject().apply {
            addProperty("id", requestId)
            addProperty("type", conv.type.ifBlank { "craft" })
            addProperty("state", "complete")
            add("messages", messages)
        }
    }

    /**
     * 本地消息文件 -> 官方归档里的 message 对象。
     * 只保留校验会用到的字段，避免把内部私有字段带出去。
     */
    private fun toArchiveMessage(source: JsonObject, messageId: String): JsonObject? {
        val id = archiveId(str(source, "id")) ?: archiveId(messageId) ?: return null
        val role = (str(source, "role") ?: "").lowercase()
        if (role !in ROLES) return null
        val message = str(source, "message") ?: synthesizeMessage(source, role) ?: return null

        val out = JsonObject()
        out.addProperty("id", id)
        out.addProperty("role", role)
        out.addProperty("message", message)
        str(source, "status")?.takeIf { it in MESSAGE_STATUSES }?.let { out.addProperty("status", it) }
        str(source, "extra")?.let { out.addProperty("extra", it) }
        source.get("isNewFile")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.let { out.add("isNewFile", it) }
        source.get("references")?.takeIf { it.isJsonArray }?.let { out.add("references", it) }
        str(source, "createdAt")?.takeIf { it.isNotBlank() }?.let { out.addProperty("createdAt", it) }
        return out
    }

    /** 老格式把 content 直接写在文件里：包成标准的 message 字符串 */
    private fun synthesizeMessage(source: JsonObject, role: String): String? {
        val content = source.get("content") ?: source.get("text") ?: return null
        if (content.isJsonNull) return null
        val wrapper = JsonObject()
        wrapper.addProperty("role", role)
        wrapper.add("content", content)
        return wrapper.toString()
    }

    /** usage 只保留允许的数值键，其它一律丢弃（校验要求有限数字或 null） */
    private fun usageOf(request: JsonObject): JsonObject? {
        val usage = request.get("usage") ?: return null
        if (usage.isJsonNull) return null
        if (!usage.isJsonObject) return null
        val source = usage.asJsonObject
        val out = JsonObject()
        for (key in USAGE_KEYS) {
            val el = source.get(key) ?: continue
            if (el.isJsonNull) {
                out.add(key, el)
            } else if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber &&
                el.asDouble.isFinite()
            ) {
                out.add(key, el)
            }
        }
        return if (out.size() == 0) null else out
    }

    private fun copyStringMap(source: JsonObject, key: String, target: JsonObject) {
        val map = source.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        if (map.entrySet().any { (_, v) -> !v.isJsonPrimitive || !v.asJsonPrimitive.isString }) return
        target.add(key, map)
    }

    private fun copyBooleanMap(source: JsonObject, key: String, target: JsonObject) {
        val map = source.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        if (map.entrySet().any { (_, v) -> !v.isJsonPrimitive || !v.asJsonPrimitive.isBoolean }) return
        target.add(key, map)
    }

    /** 单个归档写文件 */
    fun writeJson(artifact: Artifact, target: Path) {
        Files.writeString(target, artifact.json, StandardCharsets.UTF_8)
    }

    /** 批量写 zip：每个 entry 是官方要求的一会话一文件结构 */
    fun writeZip(artifacts: List<Artifact>, target: Path) {
        val stamp = stampFmt.format(Instant.now())
        val used = HashSet<String>()
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            for (artifact in artifacts) {
                val base = sanitizeFileName(artifact.conversationName).ifBlank { "conversation" }
                var entry = "${base}_$stamp.json"
                var seq = 2
                while (!used.add(entry)) {
                    entry = "${base}_${stamp}_$seq.json"
                    seq++
                }
                zip.putNextEntry(ZipEntry(entry))
                zip.write(artifact.json.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** 生成与官方一致的默认文件名：<会话名>_<yyyyMMddHHmmss>.json */
    fun defaultFileName(conversationName: String, ext: String): String {
        val base = sanitizeFileName(conversationName).ifBlank { "conversation" }
        return "${base}_${stampFmt.format(Instant.now())}.$ext"
    }

    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_").trim().take(40)

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    private fun archiveId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (ID_PATTERN.matches(raw)) raw else null
    }

    private fun readObject(file: Path): JsonObject? = try {
        if (!Files.isRegularFile(file)) null
        else JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            .takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun sizeOf(file: Path): Long = try {
        if (Files.isRegularFile(file)) Files.size(file) else 0L
    } catch (_: Exception) {
        0L
    }

    private fun mtimeOf(file: Path): Long = try {
        Files.getLastModifiedTime(file).toMillis()
    } catch (_: Exception) {
        0L
    }

    private fun str(o: JsonObject, key: String): String? = try {
        val el: JsonElement = o.get(key) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) null else el.asString
    } catch (_: Exception) {
        null
    }
}
