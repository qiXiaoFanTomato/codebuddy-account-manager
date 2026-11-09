package dev.codebuddy.account.scan

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫描选项。
 *
 * @param recursive           是否递归子目录
 * @param followLinks         是否跟随符号链接（默认关闭，避免环路与重复统计）
 * @param maxDepth            最大递归深度，根目录为 0
 * @param includeDirectories  是否把目录本身也作为条目回调
 * @param nameFilters         文件名过滤（小写；`.json` 视为扩展名，其余按子串匹配），为空表示不过滤
 * @param maxFiles            文件数上限，达到后停止并标记 truncated
 * @param skipDirectoryNames  需要整棵跳过的目录名（大小写不敏感），用于剪掉消息目录等无关子树
 */
data class ScanOptions(
    val recursive: Boolean = true,
    val followLinks: Boolean = false,
    val maxDepth: Int = 32,
    val includeDirectories: Boolean = false,
    val nameFilters: Set<String> = emptySet(),
    val maxFiles: Int = 200_000,
    val skipDirectoryNames: Set<String> = emptySet(),
)

/** 扫描到的一个条目 */
data class ScanEntry(
    val path: Path,
    /** 相对根目录的路径，用于界面展示 */
    val relativePath: String,
    val name: String,
    val size: Long,
    val modifiedAtMillis: Long,
    val isDirectory: Boolean,
)

/** 扫描进度快照 */
data class ScanProgress(
    val files: Int,
    val directories: Int,
    val currentPath: String,
)

/** 单条失败项（权限不足、读取失败等） */
data class ScanError(val path: String, val reason: String)

/** 扫描结束后的汇总 */
data class ScanSummary(
    val root: Path,
    val files: Int,
    val directories: Int,
    val totalBytes: Long,
    /** 被跳过的条目数（权限不足 / 读取失败） */
    val skipped: Int,
    val elapsedMs: Long,
    /** 是否被用户取消 */
    val cancelled: Boolean,
    /** 是否因达到 maxFiles 上限而提前结束 */
    val truncated: Boolean,
    val errors: List<ScanError>,
)

/**
 * 扫描回调。
 *
 * 所有回调都在扫描线程触发，实现方若要更新界面需自行切回 EDT。
 */
interface ScanListener {

    /** 发现一个条目，立即回调（不等扫描结束） */
    fun onEntry(entry: ScanEntry) {}

    /** 进度更新 */
    fun onProgress(progress: ScanProgress) {}

    /** 条目被跳过（权限不足、读取失败等），扫描继续 */
    fun onSkipped(path: Path, reason: String) {}

    /** 扫描结束（含取消 / 出错） */
    fun onFinished(summary: ScanSummary) {}

    companion object {
        /** 空实现 */
        val NONE: ScanListener = object : ScanListener {}
    }
}

/**
 * 目录扫描内核：边扫边回调，不做全量收集后再返回。
 *
 * 关键点：
 *  - 基于 `Files.walkFileTree`，每访问到一个文件立刻回调，界面可实时追加；
 *  - 单个条目失败（权限不足等）只跳过该项并记入 errors，不中断整体扫描；
 *  - 根目录不存在 / 不是目录时直接返回带错误信息的汇总；
 *  - 支持取消：回调返回 TERMINATE 或抛 CancellationException 时立即中止。
 */
object DirectoryScanner {

    /**
     * 扫描 [root] 目录。
     *
     * @param root     根目录
     * @param options  扫描选项
     * @param listener 回调（后台线程）
     * @param cancel   取消标志，置为 true 后扫描尽快中止
     * @return 扫描汇总
     */
    fun scan(
        root: Path,
        options: ScanOptions = ScanOptions(),
        listener: ScanListener = ScanListener.NONE,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ): ScanSummary {
        val started = System.nanoTime()

        var files = 0
        var directories = 0
        var totalBytes = 0L
        var skipped = 0
        var truncated = false
        val errors = ArrayList<ScanError>()

        fun finish(): ScanSummary {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val summary = ScanSummary(
                root = root,
                files = files,
                directories = directories,
                totalBytes = totalBytes,
                skipped = skipped,
                elapsedMs = elapsed,
                cancelled = cancel.get(),
                truncated = truncated,
                errors = errors.toList(),
            )
            listener.onFinished(summary)
            return summary
        }

        // 根目录本身先做校验：不存在 / 不是目录时没有可遍历内容
        if (!Files.exists(root)) {
            val reason = "目录不存在"
            errors += ScanError(root.toString(), reason)
            skipped++
            listener.onSkipped(root, reason)
            return finish()
        }
        if (!Files.isDirectory(root)) {
            val reason = "不是目录"
            errors += ScanError(root.toString(), reason)
            skipped++
            listener.onSkipped(root, reason)
            return finish()
        }

        fun relativeOf(path: Path): String = try {
            root.relativize(path).toString()
        } catch (_: IllegalArgumentException) {
            path.fileName?.toString() ?: path.toString()
        }

        fun emit(path: Path, attrs: BasicFileAttributes, isDir: Boolean) {
            val entry = ScanEntry(
                path = path,
                relativePath = relativeOf(path),
                name = path.fileName?.toString() ?: path.toString(),
                size = if (isDir) 0L else attrs.size(),
                modifiedAtMillis = attrs.lastModifiedTime().toMillis(),
                isDirectory = isDir,
            )
            listener.onEntry(entry)
        }

        val visitor = object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancel.get()) return FileVisitResult.TERMINATE
                val dirName = dir.fileName?.toString()
                if (dirName != null && options.skipDirectoryNames.any { it.equals(dirName, ignoreCase = true) }) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                directories++
                if (options.includeDirectories && dir != root) {
                    emit(dir, attrs, true)
                }
                listener.onProgress(ScanProgress(files, directories, dir.toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancel.get()) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                if (!matches(file, options.nameFilters)) return FileVisitResult.CONTINUE

                files++
                totalBytes += attrs.size()
                emit(file, attrs, false)
                listener.onProgress(ScanProgress(files, directories, file.toString()))

                if (files >= options.maxFiles) {
                    truncated = true
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            /** 权限不足 / 文件被占用：跳过该项，继续扫描其余内容 */
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                skipped++
                val reason = exc.message ?: exc.javaClass.simpleName
                errors += ScanError(file.toString(), reason)
                listener.onSkipped(file, reason)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    skipped++
                    val reason = exc.message ?: exc.javaClass.simpleName
                    errors += ScanError(dir.toString(), reason)
                    listener.onSkipped(dir, reason)
                }
                return FileVisitResult.CONTINUE
            }
        }

        try {
            val visitOptions =
                if (options.followLinks) EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                else EnumSet.noneOf(FileVisitOption::class.java)
            val depth = if (options.recursive) options.maxDepth.coerceAtLeast(1) else 1
            Files.walkFileTree(root, visitOptions, depth, visitor)
        } catch (e: NoSuchFileException) {
            skipped++
            val reason = e.message ?: "目录不存在"
            errors += ScanError(root.toString(), reason)
            listener.onSkipped(root, reason)
        } catch (e: AccessDeniedException) {
            skipped++
            val reason = e.message ?: "权限不足"
            errors += ScanError(root.toString(), reason)
            listener.onSkipped(root, reason)
        } catch (e: IOException) {
            skipped++
            val reason = e.message ?: e.javaClass.simpleName
            errors += ScanError(root.toString(), reason)
            listener.onSkipped(root, reason)
        }

        return finish()
    }

    /** 名称过滤：`.json` 之类按扩展名匹配，其余按子串匹配（大小写不敏感） */
    private fun matches(file: Path, filters: Set<String>): Boolean {
        if (filters.isEmpty()) return true
        val name = file.fileName?.toString()?.lowercase() ?: return false
        return filters.any { raw ->
            val f = raw.trim().lowercase()
            if (f.isEmpty()) return@any false
            if (f.startsWith(".")) name.endsWith(f) else name.contains(f)
        }
    }
}
