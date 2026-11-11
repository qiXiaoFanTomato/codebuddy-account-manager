package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * 碎碎念配图（原项目 `assets/memes`，27 张）。
 *
 * 资源名按下载顺序编号为 `m01`…`m27`（避开中文文件名在 jar 里的编码问题），
 * 原始文件名与含义见 `PetWhisper` 的映射注释。
 * 图片只在首次展示时解码，缓存最近 4 张，避免常驻几十 MB。
 */
object PetMeme {

    private const val DIR = "/pet/memes/"
    private const val COUNT = 27
    private const val CACHE_IMAGES = 4

    private val log = logger<PetMeme>()

    private val cache = object : LinkedHashMap<String, BufferedImage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>): Boolean =
            size > CACHE_IMAGES
    }

    private val failed = HashSet<String>()

    val ids: List<String> get() = (1..COUNT).map { "m%02d".format(it) }

    fun randomId(): String? = ids.randomOrNull()

    fun image(id: String): BufferedImage? {
        synchronized(cache) { cache[id]?.let { return it } }
        if (id in failed) return null
        return try {
            val stream = PetMeme::class.java.getResourceAsStream(DIR + id + ".png") ?: run {
                failed.add(id)
                return null
            }
            val image = stream.use { ImageIO.read(ByteArrayInputStream(it.readBytes())) }
            if (image == null) {
                failed.add(id)
                return null
            }
            synchronized(cache) { cache[id] = image }
            image
        } catch (e: Exception) {
            log.info("读取表情包失败: $id（${e.message}）")
            failed.add(id)
            null
        }
    }
}
