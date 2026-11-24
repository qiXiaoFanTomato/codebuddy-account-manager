package dev.codebuddy.account.pet

import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Window
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.Timer
import javax.swing.text.JTextComponent

/**
 * 宿主原生确认框探测器（同进程兜底）。
 *
 * 宿主的 `[SafetyRule]` 有时既**不写日志**、也不让会话进入 `waiting_user_input`：
 * 2026-11-20 实测 `curl` 那条命令只有 hook 的 allow 记录，日志里既没有
 * `Dangerous command detected`，也没有 `waiting_user_input` —— 日志通道与状态对账都无信号，
 * 但宿主确实弹了「包含危险命令，是否仍要运行？」。
 *
 * 那个框是本进程的 Swing 原生对话框（聊天面板走 JCEF，确认框走 Swing），所以这里直接扫窗口树
 * 把它认出来。**只提醒、不代点**：那是宿主的原生三选一，插件里的点击注入点不到。
 */
object HostConfirmWatcher {

    private val log = logger<HostConfirmWatcher>()

    /** 命中特征：宿主原生框的标题或正文 */
    private val HINTS = listOf("包含危险命令", "是否仍要运行")

    /** 轮询间隔：只在 EDT 里扫对话框，1 秒足够且开销可忽略 */
    private const val INTERVAL_MS = 1_000

    /** 组件树扫描深度与文本长度上限：只认对话框，不碰 IDE 主窗口的巨型内容树 */
    private const val MAX_DEPTH = 5
    private const val MAX_TEXT = 400

    private var timer: Timer? = null

    /** 当前这一张框是否已经提醒过（框消失后复位） */
    private var notified = false

    fun start() {
        if (timer != null) return
        val t = Timer(INTERVAL_MS) { scan() }
        t.isRepeats = true
        t.start()
        timer = t
        notified = false
    }

    fun stop() {
        timer?.stop()
        timer = null
        notified = false
    }

    private fun scan() {
        val text = try {
            findDialogText()
        } catch (e: Exception) {
            log.warn("扫描宿主确认框失败", e)
            null
        }
        if (text == null) {
            // 框没了：收起提醒卡（只收自己弹的那张，别的卡由各自通道收）
            if (notified) {
                notified = false
                PetService.getInstance().dismissManualNotice()
            }
            return
        }
        if (notified) return
        notified = true
        try {
            CbEventCenter.onHostDialogConfirm(text)
        } catch (e: Exception) {
            log.warn("宿主确认框提醒失败", e)
        }
    }

    private fun findDialogText(): String? {
        for (w in Window.getWindows()) {
            if (!w.isShowing || w !is Dialog) continue
            val title = w.title.orEmpty()
            if (HINTS.any { title.contains(it) }) return title
            val body = StringBuilder()
            collect(w, body, 0)
            val text = body.toString()
            if (HINTS.any { text.contains(it) }) return text.take(200)
        }
        return null
    }

    private fun collect(c: Component?, out: StringBuilder, depth: Int) {
        if (c == null || depth > MAX_DEPTH || out.length > MAX_TEXT) return
        when (c) {
            is JLabel -> out.append(c.text.orEmpty()).append('\n')
            is AbstractButton -> out.append(c.text.orEmpty()).append('\n')
            is JTextComponent -> out.append(c.text.orEmpty()).append('\n')
            else -> Unit
        }
        if (c is Container) {
            val children = c.components ?: return
            for (child in children) collect(child, out, depth + 1)
        }
    }
}
