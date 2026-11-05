package dev.codebuddy.account.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class DirectoryScannerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun scanStreamsEntriesAndSummarizes() {
        Files.writeString(tempDir.resolve("a.txt"), "hello")
        Files.createDirectories(tempDir.resolve("sub"))
        Files.writeString(tempDir.resolve("sub/b.json"), "{}")

        val entries = mutableListOf<ScanEntry>()
        var finished = false

        val summary = DirectoryScanner.scan(
            tempDir,
            ScanOptions(),
            object : ScanListener {
                override fun onEntry(entry: ScanEntry) {
                    entries += entry
                }

                override fun onFinished(summary: ScanSummary) {
                    finished = true
                }
            },
        )

        assertTrue(finished)
        assertEquals(2, summary.files)
        assertEquals(2, summary.directories)
        assertEquals(0, summary.skipped)
        assertTrue(summary.errors.isEmpty())
        assertTrue(entries.any { it.name == "a.txt" })
        assertTrue(entries.any { it.name == "b.json" && it.relativePath.contains("sub") })
    }

    @Test
    fun missingDirectoryIsReportedAsError() {
        val missing = tempDir.resolve("not-exist")

        val summary = DirectoryScanner.scan(missing)

        assertEquals(0, summary.files)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.errors.size)
        assertTrue(summary.errors.first().reason.contains("不存在"))
    }

    @Test
    fun nonDirectoryPathIsReportedAsError() {
        val file = tempDir.resolve("plain.txt")
        Files.writeString(file, "x")

        val summary = DirectoryScanner.scan(file)

        assertEquals(0, summary.files)
        assertEquals(1, summary.skipped)
        assertTrue(summary.errors.first().reason.contains("不是目录"))
    }

    @Test
    fun nameFilterKeepsOnlyMatchedFiles() {
        Files.writeString(tempDir.resolve("a.json"), "{}")
        Files.writeString(tempDir.resolve("b.txt"), "x")

        val entries = mutableListOf<ScanEntry>()
        DirectoryScanner.scan(
            tempDir,
            ScanOptions(nameFilters = setOf(".json")),
            object : ScanListener {
                override fun onEntry(entry: ScanEntry) {
                    entries += entry
                }
            },
        )

        assertEquals(1, entries.size)
        assertEquals("a.json", entries.first().name)
    }

    @Test
    fun cancelFlagStopsScan() {
        repeat(20) { Files.writeString(tempDir.resolve("f$it.txt"), "x") }
        val cancel = AtomicBoolean(false)

        val summary = DirectoryScanner.scan(
            tempDir,
            ScanOptions(),
            object : ScanListener {
                override fun onEntry(entry: ScanEntry) {
                    cancel.set(true)
                }
            },
            cancel,
        )

        assertTrue(summary.cancelled)
        assertTrue(summary.files < 20)
    }
}
