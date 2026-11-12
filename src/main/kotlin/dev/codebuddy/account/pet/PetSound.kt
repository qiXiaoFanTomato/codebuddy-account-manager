package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.io.BufferedInputStream
import java.util.concurrent.Executors
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/**
 * 点击音效：播放随插件打包的 `pet/click-duck.wav`（来自 dsh-pet 原素材）。
 *
 * 用 [javax.sound.sampled.Clip] 播放，异步且在失败时静默降级：
 * 没有可用混音器（远程桌面 / 无声卡）时不影响交互。
 */
object PetSound {

    private val log = logger<PetSound>()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "codebuddy-pet-sound").apply { isDaemon = true }
    }

    fun playClick() {
        if (!PetSettings.getInstance().clickSoundEnabled) return
        executor.execute { playOnce() }
    }

    /** 对话完成提醒：同一音效连播两声，比单次点击更抓耳 */
    fun playNotice() {
        if (!PetSettings.getInstance().clickSoundEnabled) return
        executor.execute {
            playOnce()
            try {
                Thread.sleep(NOTICE_SECOND_GAP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            playOnce()
        }
    }

    private fun playOnce() {
        try {
            val raw = PetSound::class.java.getResourceAsStream(SOUND_PATH) ?: return
            BufferedInputStream(raw).use { input ->
                val audio = AudioSystem.getAudioInputStream(input)
                audio.use {
                    val clip = AudioSystem.getClip()
                    clip.addLineListener { e ->
                        if (e.type == LineEvent.Type.STOP) clip.close()
                    }
                    clip.open(it)
                    clip.start()
                }
            }
        } catch (e: Exception) {
            log.info("播放音效失败: ${e.message}")
        }
    }

    private const val SOUND_PATH = "/pet/click-duck.wav"

    /** 两声提醒之间的间隔 */
    private const val NOTICE_SECOND_GAP_MS = 220L
}
