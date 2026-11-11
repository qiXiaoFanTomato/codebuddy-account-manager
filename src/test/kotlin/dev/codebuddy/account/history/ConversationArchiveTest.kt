package dev.codebuddy.account.history

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * 归档结构必须与 CodeBuddy 官方「导入对话」的校验一致：
 * schema / schemaVersion / 单会话 / id 字符集 / role / request.state / message 为字符串。
 */
class ConversationArchiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun buildsSingleConversationArchive() {
        val conv = writeFixture()
        val outcome = ConversationArchive.build(listOf(conv))

        assertTrue(outcome.failures.isEmpty(), "不应有失败：${outcome.failures}")
        assertEquals(1, outcome.artifacts.size)

        val root = JsonParser.parseString(outcome.artifacts.first().json).asJsonObject
        assertEquals("codebuddy.conversation", root.get("schema").asString)
        assertEquals(1, root.get("schemaVersion").asInt)
        assertTrue(root.get("exportedAt").asString.isNotBlank())

        val data = root.getAsJsonObject("data")
        assertEquals("2026-11-10T00:00:00.000Z", data.get("lastMessageAt").asString)
        val conversations = data.getAsJsonArray("conversations")
        assertEquals(1, conversations.size())

        val conversation = conversations[0].asJsonObject
        assertEquals("c1", conversation.get("id").asString)
        assertEquals("craft", conversation.get("type").asString)
        assertEquals("测试会话", conversation.get("name").asString)
        assertEquals("auto", conversation.getAsJsonObject("modelMap").get("craft").asString)
        assertTrue(!conversation.getAsJsonObject("maxModeMap").get("craft").asBoolean)

        val requests = conversation.getAsJsonArray("requests")
        assertEquals(1, requests.size())
        val request = requests[0].asJsonObject
        assertEquals("r1", request.get("id").asString)
        assertEquals("complete", request.get("state").asString)
        val usage = request.getAsJsonObject("usage")
        assertEquals(10, usage.get("inputTokens").asInt)
        assertEquals(1.5, usage.get("credit").asDouble)
        // 非法字段必须被丢弃，否则官方导入会判定 usage 无效
        assertNull(usage.get("bogus"))

        val messages = request.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        val first = messages[0].asJsonObject
        assertEquals("m1", first.get("id").asString)
        assertEquals("user", first.get("role").asString)
        // message 必须是字符串（内部仍是 JSON 文本），不能是对象
        assertTrue(first.get("message").isJsonPrimitive)
        assertTrue(first.get("message").asString.contains("你好"))
        assertEquals("{\"requestId\":\"r1\"}", first.get("extra").asString)
    }

    @Test
    fun writesZipWithOneEntryPerConversation() {
        val conv = writeFixture()
        val outcome = ConversationArchive.build(listOf(conv, conv))
        assertEquals(2, outcome.artifacts.size)

        val zip = tempDir.resolve("batch.zip")
        ConversationArchive.writeZip(outcome.artifacts, zip)
        assertTrue(Files.isRegularFile(zip))

        val names = mutableListOf<String>()
        ZipInputStream(Files.newInputStream(zip)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                names.add(entry.name)
                assertTrue(entry.name.endsWith(".json"))
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        assertEquals(2, names.size)
        assertEquals(names.size, names.toSet().size, "zip entry 名不能重复")
        assertTrue(names.all { it.startsWith("测试会话_") })
    }

    /** 造一份与真实目录一致的本地会话（工作区 index.json + 会话 index.json + messages/） */
    private fun writeFixture(): HistoryReader.Conversation {
        val root = tempDir.resolve("uid")
        val uid = "13e50313"
        val ws = "0c1b9e4d"
        val history = root.resolve("JetBrains").resolve(uid).resolve("history")
        val wsDir = history.resolve(ws)
        Files.createDirectories(wsDir)
        Files.writeString(
            wsDir.resolve("index.json"),
            """
            {
              "conversations": [
                {
                  "id": "c1",
                  "type": "craft",
                  "name": "测试会话",
                  "createdAt": "2026-11-01T00:00:00.000Z",
                  "lastMessageAt": "2026-11-10T00:00:00.000Z",
                  "modelMap": { "craft": "auto" },
                  "maxModeMap": { "craft": false }
                }
              ],
              "current": "c1"
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val convDir = wsDir.resolve("c1")
        val messageDir = convDir.resolve("messages")
        Files.createDirectories(messageDir)
        Files.writeString(
            convDir.resolve("index.json"),
            """
            {
              "messages": [
                { "id": "m1", "type": "text", "role": "user", "isComplete": true },
                { "id": "m2", "type": "text", "role": "assistant", "isComplete": false }
              ],
              "requests": [
                {
                  "id": "r1",
                  "type": "craft",
                  "messages": ["m1", "m2"],
                  "state": "complete",
                  "usage": { "inputTokens": 10, "credit": 1.5, "bogus": "x" }
                }
              ]
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            messageDir.resolve("m1.json"),
            """
            {
              "id": "m1",
              "role": "user",
              "message": "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}",
              "extra": "{\"requestId\":\"r1\"}"
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            messageDir.resolve("m2.json"),
            """
            {
              "id": "m2",
              "role": "assistant",
              "message": "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"收到\"}]}"
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val result = HistoryReader.listAllConversations(root)
        assertEquals(1, result.conversations.size, "应只识别到 1 个会话")
        return result.conversations.first()
    }
}
