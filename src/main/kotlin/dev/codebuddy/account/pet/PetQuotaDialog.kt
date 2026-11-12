package dev.codebuddy.account.pet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.ui.AccountUiColors
import dev.codebuddy.account.ui.AccountUiSpacing
import dev.codebuddy.account.ui.AccountUiType
import dev.codebuddy.account.ui.HistoryDialog
import dev.codebuddy.account.ui.JellyButton
import dev.codebuddy.account.ui.JellyButtonVariant
import dev.codebuddy.account.ui.QuotaDetailDialog
import dev.codebuddy.account.ui.UsageDetailDialog
import dev.codebuddy.account.usage.UsageRecord
import dev.codebuddy.account.usage.formatCredit
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.LocalDate
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 桌宠的额度明细窗口（由右键菜单与额度气泡打开）。
 *
 * 内容分两块：
 * 1. **当前账号余额**：剩余 / 总量、百分比、套餐分组
 * 2. **上一轮对话的积分消耗**：最近一次请求的积分与口径说明
 *
 * 口径说明：消耗接口没有会话 ID（见 `UsageRecord` 注释），
 * 无法把消耗精确归属到某个历史会话，因此这里取「最近一次请求」作为上一轮，
 * 并额外给出「同一分钟的合计」，避免一回合多请求时数字看起来对不上。
 */
class PetQuotaDialog(private val project: Project) : DialogWrapper(project, true) {

    private val log = logger<PetQuotaDialog>()

    private val nameLabel = label(AccountUiType.title.toInt(), AccountUiColors.textPrimary, bold = true)
    private val subLabel = label(AccountUiType.caption.toInt(), AccountUiColors.textMuted)
    private val percentLabel = label(AccountUiType.display.toInt(), AccountUiColors.brassInk, bold = true, mono = true)
    private val summaryLabel = label(AccountUiType.body.toInt(), AccountUiColors.textPrimary)
    private val groupsLabel = label(AccountUiType.caption.toInt(), AccountUiColors.textSecondary)
    private val bar = QuotaBar()

    private val turnCreditLabel = label(AccountUiType.title.toInt(), AccountUiColors.textPrimary, bold = true, mono = true)
    private val turnDetailLabel = label(AccountUiType.caption.toInt(), AccountUiColors.textSecondary)
    private val turnPromptLabel = label(AccountUiType.caption.toInt(), AccountUiColors.textMuted)
    private val statusLabel = label(AccountUiType.caption.toInt(), AccountUiColors.textMuted)

    private val refreshButton = JellyButton("刷新额度", JellyButtonVariant.PRIMARY) { refresh() }

    /** 当前账号快照与数据 */
    private var snapshot: CredentialSnapshot? = null
    private var quota: QuotaSummary? = null
    private var lastTurn: LastTurn? = null
    private var loading = false

    init {
        title = "额度明细"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(14, 16, 12, 16)
        panel.isOpaque = false

        panel.add(nameLabel)
        panel.add(vspace(2))
        panel.add(subLabel)
        panel.add(vspace(10))
        panel.add(percentLabel)
        panel.add(vspace(4))
        panel.add(summaryLabel)
        panel.add(vspace(8))
        panel.add(bar)
        panel.add(vspace(6))
        panel.add(groupsLabel)
        panel.add(vspace(12))
        panel.add(sectionTitle("上一轮对话消耗"))
        panel.add(vspace(4))
        panel.add(turnCreditLabel)
        panel.add(vspace(2))
        panel.add(turnDetailLabel)
        panel.add(vspace(2))
        panel.add(turnPromptLabel)
        panel.add(vspace(6))
        panel.add(statusLabel)
        panel.add(vspace(12))

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(refreshButton)
            add(JellyButton("完整额度明细", JellyButtonVariant.SECONDARY) {
                QuotaDetailDialog.show(project)
            })
            add(JellyButton("消耗明细", JellyButtonVariant.SECONDARY) {
                UsageDetailDialog.show(project)
            })
            add(JellyButton("历史对话", JellyButtonVariant.GHOST) { openHistory() })
        }
        panel.add(actions)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        panel.preferredSize = Dimension(JBUI.scale(520), panel.preferredSize.height)
        refresh()
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = refreshButton

    private fun openHistory() {
        val labels = try {
            AccountLibrary.getInstance().listSaved().associate { it.accountKey to it.displayName }
        } catch (_: Exception) {
            emptyMap()
        }
        HistoryDialog.show(project, labels)
    }

    /** 重新拉额度与今日消耗（网络请求在后台线程） */
    private fun refresh() {
        if (loading) return
        loading = true
        refreshButton.isEnabled = false
        statusLabel.text = "正在读取额度与消耗…"
        statusLabel.foreground = AccountUiColors.textMuted

        ApplicationManager.getApplication().executeOnPooledThread {
            var errorText: String? = null
            val library = AccountLibrary.getInstance()
            val current = try {
                library.currentActive()
            } catch (t: Throwable) {
                log.warn("读取当前账号失败", t)
                null
            }

            val freshQuota = if (current != null) {
                try {
                    library.refreshQuota(current.accountKey)
                } catch (t: Throwable) {
                    log.warn("刷新额度失败", t)
                    null
                }
            } else {
                null
            }
            if (freshQuota?.error != null) errorText = "额度：${freshQuota.error}"

            val usage = if (current != null) {
                try {
                    val today = LocalDate.now().toString()
                    library.fetchUsage(current.accountKey, "$today 00:00:00", "$today 23:59:59")
                } catch (t: Throwable) {
                    log.warn("读取消耗失败", t)
                    null
                }
            } else {
                null
            }
            if (usage?.error != null && errorText == null) errorText = "消耗：${usage.error}"

            if (freshQuota != null && freshQuota.error == null) {
                AccountEvents.fireQuotaChanged()
            }

            ApplicationManager.getApplication().invokeLater {
                snapshot = current
                quota = freshQuota ?: current?.let { library.quotaCache.loadAll()[it.accountKey] }
                lastTurn = usage?.takeIf { it.error == null }?.let { lastTurnOf(it.records) }
                loading = false
                refreshButton.isEnabled = true
                render(errorText ?: usage?.error?.let { "消耗：$it" })
            }
        }
    }

    private fun render(errorText: String?) {
        val snap = snapshot
        if (snap == null) {
            nameLabel.text = "CodeBuddy 未登录"
            subLabel.text = "在账号管理器中导入并切换账号后可用"
            percentLabel.text = "—"
            summaryLabel.text = ""
            groupsLabel.text = ""
            turnCreditLabel.text = "—"
            turnDetailLabel.text = "无当前账号，无法读取消耗"
            turnPromptLabel.text = ""
            bar.percent = 0.0
            statusLabel.text = ""
            return
        }

        nameLabel.text = snap.displayName
        subLabel.text = buildString {
            append(snap.domainHost)
            if (snap.isRefreshTokenExpired) append(" · token 已过期")
        }

        val q = quota
        if (q == null) {
            percentLabel.text = "—"
            percentLabel.foreground = AccountUiColors.textMuted
            summaryLabel.text = "尚未获取额度，点击「刷新额度」查询"
            groupsLabel.text = ""
            bar.percent = 0.0
        } else if (q.error != null) {
            percentLabel.text = "—"
            percentLabel.foreground = AccountUiColors.seal
            summaryLabel.text = "额度查询失败：${q.error}"
            groupsLabel.text = ""
            bar.percent = 0.0
        } else {
            percentLabel.text = String.format(Locale.ROOT, "%.0f%%", q.usableRemainPercent)
            percentLabel.foreground = if (q.usableRemainPercent < PetPainter.LOW_PERCENT) {
                AccountUiColors.seal
            } else {
                AccountUiColors.brassInk
            }
            val unit = q.unit.ifBlank { "credits" }
            summaryLabel.text = "剩余 ${fmt(q.usableRemain)} / ${fmt(q.usableSize)} $unit"
            groupsLabel.text = q.groups(onlyUsable = true).take(3).joinToString("　") {
                "· ${it.name.take(10)} ${fmt(it.remain)}/${fmt(it.size)}"
            }
            bar.percent = q.usableRemainPercent
        }

        val turn = lastTurn
        if (turn == null) {
            turnCreditLabel.text = "—"
            turnCreditLabel.foreground = AccountUiColors.textMuted
            turnDetailLabel.text = "今日暂无消耗记录"
            turnPromptLabel.text = ""
        } else {
            turnCreditLabel.text = "${formatCredit(turn.credit)} 积分"
            turnCreditLabel.foreground = AccountUiColors.textPrimary
            turnDetailLabel.text = buildString {
                append("${turn.time}　${turn.model.ifBlank { "-" }}")
                if (turn.roundCount > 1) {
                    append("　同分钟合计 ${formatCredit(turn.roundCredit)}（${turn.roundCount} 次请求）")
                }
            }
            turnPromptLabel.text = turn.prompt.take(80).let { if (it.isBlank()) "" else "「${it}…」" }
        }

        statusLabel.text = errorText ?: "口径：消耗接口无会话 ID，上一轮按「最近一次请求 + 同分钟合计」计算"
        statusLabel.foreground = if (errorText != null) AccountUiColors.seal else AccountUiColors.textMuted
    }

    // ---------------------------------------------------------------
    // 组件
    // ---------------------------------------------------------------

    private fun sectionTitle(titleText: String): JLabel {
        val l = label(AccountUiType.label.toInt(), AccountUiColors.textMuted, bold = true)
        l.text = titleText
        return l
    }

    private fun label(sizePx: Int, color: java.awt.Color, bold: Boolean = false, mono: Boolean = false): JLabel =
        JLabel().apply {
            font = Font(
                if (mono) Font.MONOSPACED else Font.SANS_SERIF,
                if (bold) Font.BOLD else Font.PLAIN,
                sizePx,
            )
            foreground = color
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }

    private fun vspace(dp: Int): Component = JPanel().apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Integer.MAX_VALUE, JBUI.scale(dp))
        preferredSize = Dimension(1, JBUI.scale(dp))
    }

    private fun fmt(v: Double): String = QuotaPackage.fmt(v)

    /** 余额进度条：轨道 + 黄铜充盈 + 9px 刻度 */
    private class QuotaBar : JComponent() {

        var percent: Double = 0.0
            set(value) {
                field = value.coerceIn(0.0, 100.0)
                repaint()
            }

        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(10))
            maximumSize = Dimension(JBUI.scale(480), JBUI.scale(10))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = JBUI.scale(6)
            val y = (height - h) / 2
            g2.color = AccountUiColors.track
            g2.fillRoundRect(0, y, width, h, h, h)
            val fillW = (width * percent / 100.0).toInt()
            if (fillW > 0) {
                g2.color = AccountUiColors.brass
                g2.fillRoundRect(0, y, fillW.coerceAtLeast(h), h, h, h)
            }
            g2.color = AccountUiColors.tick
            g2.stroke = BasicStroke(1f)
            var x = JBUI.scale(9)
            while (x < width) {
                g2.drawLine(x, y - JBUI.scale(1), x, y + h + JBUI.scale(1))
                x += JBUI.scale(9)
            }
            g2.dispose()
        }
    }

    /** 上一轮消耗的聚合结果 */
    private data class LastTurn(
        val credit: Double,
        val time: String,
        val model: String,
        val prompt: String,
        val roundCredit: Double,
        val roundCount: Int,
    )

    companion object {

        private val TIME_SLICE = 16 // "yyyy-MM-dd HH:mm"

        private fun lastTurnOf(records: List<UsageRecord>): LastTurn? {
            val latest = records.maxByOrNull { it.requestTime } ?: return null
            val minute = latest.requestTime.take(TIME_SLICE)
            val round = records.filter { it.requestTime.take(TIME_SLICE) == minute }
            return LastTurn(
                credit = latest.credit,
                time = latest.requestTime,
                model = latest.model,
                prompt = latest.displayPrompt,
                roundCredit = round.sumOf { it.credit },
                roundCount = round.size,
            )
        }

        fun show(project: Project) {
            PetQuotaDialog(project).show()
        }
    }
}
