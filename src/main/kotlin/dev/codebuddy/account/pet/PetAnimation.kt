package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * 一个动画的容器文件（`*.petanim`），由 `tools` 侧的转换脚本从原版 webm 生成。
 *
 * 文件结构（小端）：
 * ```
 * magic  "PETA"
 * int32  version      固定 2
 * int32  fps
 * int32  width        裁剪后的帧宽
 * int32  height       裁剪后的帧高
 * int32  offsetX      该裁剪框在 462×260 原画框中的左上角 X
 * int32  offsetY
 * int32  frameCount
 * int32  maskScale    掩码相对帧的降采样倍数（1/2/3）
 * int32  reserved
 * int32[count*2]      每帧 (jpgLen, maskLen)
 * bytes[]             帧数据：JPEG 颜色层 + PNG 灰度掩码，逐帧连续排列
 * ```
 *
 * 为什么要拆成 JPEG + 掩码：VP9-alpha 在 JVM 里没有可用解码器，
 * 而 ImageIO 能原生读 JPEG / PNG；整帧存 PNG-24 体积是这套方案的 4 倍以上。
 * 颜色层做过一次「透明像素借用邻居颜色」外扩，避免 JPEG 在 alpha 边缘发黑。
 */
class PetAnimation private constructor(
    val name: String,
    val fps: Int,
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int,
    val maskScale: Int,
    private val source: ByteArray,
    private val jpgOffsets: IntArray,
    private val jpgLengths: IntArray,
    private val maskOffsets: IntArray,
    private val maskLengths: IntArray,
) {

    val frameCount: Int get() = jpgLengths.size

    /** 已解码帧缓存：整段动画全解码内存不够，只保留最近若干帧 */
    private val cache = object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean =
            size > CACHE_FRAMES
    }

    private val lock = Any()

    /** 取第 [index] 帧（越界自动取模），解码结果带 ARGB 透明度 */
    fun frame(index: Int): BufferedImage? {
        if (frameCount == 0) return null
        val i = Math.floorMod(index, frameCount)
        synchronized(lock) { cache[i]?.let { return it } }
        val decoded = try {
            decode(i)
        } catch (e: Exception) {
            log.warn("解码动画帧失败: $name #$i", e)
            null
        } ?: return null
        synchronized(lock) { cache[i] = decoded }
        return decoded
    }

    /**
     * 预解码后续若干帧（后台线程调用）：
     * 25fps 下每帧只有 40ms 预算，把解码挪出 EDT 才不会掉帧。
     */
    fun prefetch(vararg indexes: Int) {
        if (frameCount == 0) return
        for (raw in indexes) {
            val i = Math.floorMod(raw, frameCount)
            synchronized(lock) { if (cache.containsKey(i)) continue }
            try {
                val decoded = decode(i) ?: continue
                synchronized(lock) { cache[i] = decoded }
            } catch (_: Exception) {
                // 预解码失败不影响正常播放
            }
        }
    }

    private fun decode(i: Int): BufferedImage? {
        val jpg = source.copyOfRange(jpgOffsets[i], jpgOffsets[i] + jpgLengths[i])
        val mask = source.copyOfRange(maskOffsets[i], maskOffsets[i] + maskLengths[i])

        val color = ImageIO.read(ByteArrayInputStream(jpg)) ?: return null
        val alpha = ImageIO.read(ByteArrayInputStream(mask))

        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(color, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        if (alpha == null) return out

        // 掩码的灰度值即 alpha；降采样的掩码按帧尺寸放大回来。
        // 用整型数组批量读写，避免逐像素 getRGB/setRGB 的颜色模型转换开销。
        val aw = alpha.width
        val ah = alpha.height
        val alphaPixels = alpha.getRGB(0, 0, aw, ah, null, 0, aw)
        val pixels = out.getRGB(0, 0, width, height, null, 0, width)
        for (y in 0 until height) {
            val maskRow = ((y.toLong() * ah) / height).toInt() * aw
            val rowStart = y * width
            for (x in 0 until width) {
                val a = alphaPixels[maskRow + ((x.toLong() * aw) / width).toInt()] and 0xFF
                val p = rowStart + x
                if (a != 255) pixels[p] = (a shl 24) or (pixels[p] and 0x00FFFFFF)
            }
        }
        out.setRGB(0, 0, width, height, pixels, 0, width)
        return out
    }

    companion object {

        private val log = logger<PetAnimation>()

        /** 每段动画保留的解码帧数（约 34k 像素/帧，48 帧约 6MB 上限） */
        private const val CACHE_FRAMES = 48

        private const val MAGIC = "PETA"
        private const val HEADER_SIZE = 40

        /** 只读文件头，用于算摆放区域，不解码任何帧 */
        fun readHeader(name: String, stream: InputStream): Header? {
            return try {
                DataInputStream(stream).use { input ->
                    val magic = ByteArray(4)
                    input.readFully(magic)
                    if (String(magic, Charsets.US_ASCII) != MAGIC) return null
                    Header(
                        version = input.readIntLe(),
                        fps = input.readIntLe(),
                        width = input.readIntLe(),
                        height = input.readIntLe(),
                        offsetX = input.readIntLe(),
                        offsetY = input.readIntLe(),
                        frameCount = input.readIntLe(),
                        maskScale = input.readIntLe().coerceAtLeast(1),
                    )
                }
            } catch (e: Exception) {
                log.warn("读取动画头失败: $name", e)
                null
            }
        }

        fun load(name: String, stream: InputStream): PetAnimation? {
            return try {
                val bytes = stream.use { it.readBytes() }
                if (bytes.size < HEADER_SIZE) return null
                if (String(bytes, 0, 4, Charsets.US_ASCII) != MAGIC) return null

                fun i32(at: Int): Int =
                    (bytes[at].toInt() and 0xFF) or
                        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[at + 3].toInt() and 0xFF) shl 24)

                val fps = i32(8).coerceIn(1, 60)
                val w = i32(12)
                val h = i32(16)
                val ox = i32(20)
                val oy = i32(24)
                val count = i32(28)
                val maskScale = i32(32).coerceAtLeast(1)
                if (w <= 0 || h <= 0 || count <= 0) return null

                val indexStart = HEADER_SIZE
                var cursor = indexStart + count * 8
                val jpgOffsets = IntArray(count)
                val jpgLengths = IntArray(count)
                val maskOffsets = IntArray(count)
                val maskLengths = IntArray(count)
                for (i in 0 until count) {
                    val at = indexStart + i * 8
                    val jl = i32(at)
                    val ml = i32(at + 4)
                    jpgOffsets[i] = cursor
                    jpgLengths[i] = jl
                    cursor += jl
                    maskOffsets[i] = cursor
                    maskLengths[i] = ml
                    cursor += ml
                }
                if (cursor > bytes.size) {
                    log.warn("动画数据不完整: $name（需要 $cursor 字节，实际 ${bytes.size}）")
                    return null
                }

                PetAnimation(
                    name = name,
                    fps = fps,
                    width = w,
                    height = h,
                    offsetX = ox,
                    offsetY = oy,
                    maskScale = maskScale,
                    source = bytes,
                    jpgOffsets = jpgOffsets,
                    jpgLengths = jpgLengths,
                    maskOffsets = maskOffsets,
                    maskLengths = maskLengths,
                )
            } catch (e: Exception) {
                log.warn("加载动画失败: $name", e)
                null
            }
        }

        private fun DataInputStream.readIntLe(): Int {
            val b0 = read()
            val b1 = read()
            val b2 = read()
            val b3 = read()
            if (b3 < 0) throw java.io.EOFException()
            return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
        }
    }

    /** 文件头信息（不解码帧） */
    data class Header(
        val version: Int,
        val fps: Int,
        val width: Int,
        val height: Int,
        val offsetX: Int,
        val offsetY: Int,
        val frameCount: Int,
        val maskScale: Int,
    )
}
