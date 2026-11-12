package dev.codebuddy.account.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.growth.GrowthActions
import dev.codebuddy.account.growth.GrowthService
import dev.codebuddy.account.growth.GrowthTracker
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * 成长中心窗口：任务明细表 + 成长动作按钮。
 *
 * 顶部动作对**选中账号**（下拉里可选「全部账号」）执行：
 *  - 一键完成：接取未接取任务 → 行为上报类任务自动完成 → 领取已达标的奖励
 *  - 旅行巡检：无猫则领养，有猫则派出 / 到站领奖
 *  - 活跃上报：发一条对话活跃事件（点亮连登与对话类任务）
 *  - 全部保活：用 refreshToken 刷新 accessToken 并写回账号库
 *  - 连登兑换 / 抽奖：成长中心其它一键动作
 *
 * 表格里双击某一行 = 该任务的「一键完成」（仅支持行为上报的任务）；
 * 需在客户端完成的任务会给出跳转链接。
 */
class GrowthCenterDialog(private val project: Project?) : DialogWrapper(project, false) {

    private val accountSelector = JComboBox<String>()
    private val tableModel = object : DefaultTableModel(
        arrayOf("账号", "任务", "状态", "进度", "奖励", "任务码", "可执行动作"),
        0,
    ) {
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    private val table = JBTable(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // 与其它弹窗共用一套表头 / 斑马纹 / hover 行样式
        AccountTableStyle.apply(this, IntArray(0))
    }

    private val statusLabel = JBLabel("就绪").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, AccountUiType.caption)
        foreground = AccountUiColors.textMuted
    }

    /** 行号 → 跳转链接 */
    private val jumpUrls = HashMap<Int, String>()

    /** 下拉项文案 → 账号 key */
    private val accountKeyByLabel = LinkedHashMap<String, String>()

    init {
        title = "成长中心"
        init()
        val labels = ArrayList<String>()
        accountKeyByLabel[ALL_ACCOUNTS_LABEL] = ALL_ACCOUNTS
        labels += ALL_ACCOUNTS_LABEL
        AccountLibrary.getInstance().listSaved().forEach { snapshot ->
            var label = snapshot.displayName.ifBlank { snapshot.accountKey }
            if (accountKeyByLabel.containsKey(label)) label = "$label (${snapshot.accountKey})"
            accountKeyByLabel[label] = snapshot.accountKey
            labels += label
        }
        accountSelector.model = DefaultComboBoxModel(labels.toTypedArray())
        accountSelector.selectedIndex = 0
        accountSelector.addActionListener { reload() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) onRowActivated()
            }
        })
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.background = AccountUiColors.panelBg

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply { isOpaque = false }
        toolbar.add(JLabel("账号：").apply { foreground = AccountUiColors.textSecondary })
        toolbar.add(AccountUiKit.styleCombo(accountSelector) { it?.toString().orEmpty() })
        toolbar.add(actionButton("一键完成") { runAction { cb -> GrowthTracker.completeAll(selectedKeys(), cb) } })
        toolbar.add(actionButton("旅行巡检") { runAction { cb -> GrowthTracker.travelAll(selectedKeys(), cb) } })
        toolbar.add(actionButton("活跃上报") { runAction { cb -> GrowthTracker.activityAll(selectedKeys(), cb) } })
        toolbar.add(actionButton("活跃状态") { runBackground { checkActiveStatus() } })
        toolbar.add(actionButton("全部保活") { runAction { cb -> GrowthTracker.keepaliveAll(selectedKeys(), cb) } })
        toolbar.add(actionButton("连登兑换") { runBackground { redeemStreak() } })
        toolbar.add(actionButton("补签昨日") { runAction { cb -> GrowthTracker.makeupAll(selectedKeys(), cb) } })
        toolbar.add(actionButton("抽奖") { runBackground { drawLottery() } })
        toolbar.add(actionButton("刷新") { reload() })

        root.add(toolbar, BorderLayout.NORTH)
        root.add(AccountUiKit.styleScroll(JBScrollPane(table), AccountUiColors.cardBg), BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        root.border = JBUI.Borders.empty(8)
        return root
    }

    private fun actionButton(text: String, onClick: () -> Unit): JComponent =
        JellyButton(text, JellyButtonVariant.SECONDARY, null, text, onClick)

    /** 当前选中账号（空集合 = 全部账号） */
    private fun selectedKeys(): Set<String> {
        val label = accountSelector.selectedItem as? String ?: return emptySet()
        val key = accountKeyByLabel[label] ?: ALL_ACCOUNTS
        return if (key == ALL_ACCOUNTS) emptySet() else setOf(key)
    }

    /** 执行一个成长动作（Tracker 内部异步执行，完成后回调汇总） */
    private fun runAction(start: ((String) -> Unit) -> Unit) {
        if (!AccountSettings.getInstance().growthEnabled) {
            status("成长中心已在设置中关闭")
            return
        }
        status("执行中…")
        start { summary ->
            status(summary)
            reload()
        }
    }

    /** 执行本地同步动作（后台线程 + EDT 回写状态） */
    private fun runBackground(block: () -> String) {
        if (!AccountSettings.getInstance().growthEnabled) {
            status("成长中心已在设置中关闭")
            return
        }
        status("执行中…")
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val summary = try {
                block()
            } catch (t: Throwable) {
                "执行失败：${t.message ?: t.javaClass.simpleName}"
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                status(summary)
                reload()
            })
        }
    }

    /** 成长计划与活跃状态：查当日活跃 + 连登天数，查询结果由桌宠播报 */
    private fun checkActiveStatus(): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val tokens = library.accessTokenMap()
        val keys = selectedKeys()
        val parts = ArrayList<String>()
        var failed = 0
        for (s in library.listSaved()) {
            if (keys.isNotEmpty() && s.accountKey !in keys) continue
            val token = tokens[s.accountKey]
            if (token.isNullOrBlank()) {
                parts.add("${s.displayName}：无 accessToken")
                failed++
                continue
            }
            val active = GrowthService.todayActive(s.domain, token, s.uid, settings.growthEnterpriseId)
            val streak = GrowthService.streak(s.domain, token, s.uid, settings.growthEnterpriseId)
            val state = when {
                !active.ok -> "活跃状态查询失败（${active.error}）"
                active.active -> "今日已活跃"
                else -> "今日未活跃"
            }
            val tail = if (streak.ok) "连登 ${streak.days} 天" else "连登未知"
            parts.add("${s.displayName}：$state，$tail")
            if (!active.ok) failed++
        }
        if (parts.isEmpty()) return "账号库为空"
        val summary = parts.joinToString("；")
        if (failed == 0) dev.codebuddy.account.pet.CbEventCenter.onGrowthStatus(summary)
        return summary
    }

    /** 连登兑换：兑换所有已解锁（连续登录天数足够）的档位 */
    private fun redeemStreak(): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val tokens = library.accessTokenMap()
        val keys = selectedKeys()
        var ok = 0
        var fail = 0
        val notes = ArrayList<String>()
        for (s in library.listSaved()) {
            if (keys.isNotEmpty() && s.accountKey !in keys) continue
            val token = tokens[s.accountKey] ?: continue
            val st = GrowthService.streak(s.domain, token, s.uid, settings.growthEnterpriseId)
            if (!st.ok) {
                fail++
                notes.add("${s.displayName}：${st.error}")
                continue
            }
            for (tier in st.tiers) {
                if (tier.days > st.days) continue
                val r = GrowthService.redeem(s.domain, token, s.uid, tier.tier, settings.growthEnterpriseId)
                if (r.ok) ok++ else {
                    fail++
                    notes.add("${s.displayName} ${tier.tier}：${r.error}")
                }
            }
        }
        return "兑换 $ok 次" + if (fail > 0) "，失败 $fail 次（${notes.take(2).joinToString("；")}）" else ""
    }

    /** 抽奖一次 */
    private fun drawLottery(): String {
        val settings = AccountSettings.getInstance()
        val library = AccountLibrary.getInstance()
        val tokens = library.accessTokenMap()
        val keys = selectedKeys()
        var ok = 0
        var fail = 0
        val notes = ArrayList<String>()
        for (s in library.listSaved()) {
            if (keys.isNotEmpty() && s.accountKey !in keys) continue
            val token = tokens[s.accountKey] ?: continue
            val r = GrowthService.lotteryDraw(s.domain, token, s.uid, settings.growthEnterpriseId)
            if (r.ok) ok++ else {
                fail++
                notes.add("${s.displayName}：${r.error}")
            }
        }
        return "抽奖 $ok 次" + if (fail > 0) "，失败 $fail 次（${notes.take(2).joinToString("；")}）" else ""
    }

    /** 双击行：可自动完成的任务直接执行，否则尝试打开跳转链接 */
    private fun onRowActivated() {
        val row = table.selectedRow
        if (row < 0) return
        val accountKey = tableModel.getValueAt(row, 0) as? String ?: return
        val taskCode = tableModel.getValueAt(row, 5) as? String ?: return
        val title = tableModel.getValueAt(row, 1)?.toString().orEmpty()
        if (taskCode.isBlank() || !GrowthActions.isAutoSupported(taskCode)) {
            status("「$title」需在客户端完成")
            openJumpUrl(row)
            return
        }
        status("执行中：$title")
        // Tracker 内部已在后台线程执行，完成后在 EDT 回调
        GrowthTracker.completeTask(accountKey, taskCode) { summary ->
            status(summary)
            reload()
        }
    }

    /** 打开任务的跳转链接（workbuddy:// 协议） */
    private fun openJumpUrl(row: Int) {
        val url = jumpUrls[row] ?: return
        try {
            Desktop.getDesktop().browse(URI.create(url))
        } catch (t: Throwable) {
            status("无法打开链接：$url")
        }
    }

    /** 重新拉取并刷新表格 */
    private fun reload() {
        val st = GrowthTracker.state()
        tableModel.rowCount = 0
        jumpUrls.clear()
        var row = 0
        for (item in st.items) {
            for (task in item.tasks) {
                val action = when {
                    task.state == GrowthService.TaskState.CLAIMED -> "已领取"
                    task.claimable -> "领取"
                    GrowthActions.isAutoSupported(task.taskCode) -> task.taskCode
                    else -> ""
                }
                tableModel.addRow(
                    arrayOf(
                        item.accountKey,
                        task.title.ifBlank { task.taskCode },
                        task.state.label,
                        task.progressText,
                        "${task.rewardCredit.toInt()} 分 / ${task.rewardEnergy.toInt()} 能量",
                        task.taskCode,
                        action,
                    )
                )
                task.jumpUrl?.let { jumpUrls[row] = it }
                row++
            }
        }
        tableModel.fireTableDataChanged()
        if (st.loaded) {
            status(
                "共 ${st.totalTasks} 个任务 · 已完成 ${st.claimedTasks} · 可领取 ${st.claimableTasks}" +
                    if (st.claimableCredit > 0) "（待领 ${st.claimableCredit.toInt()} 分）" else ""
            )
        }
    }

    private fun status(text: String) {
        statusLabel.text = text
    }

    private companion object {
        const val ALL_ACCOUNTS = "__all__"
        const val ALL_ACCOUNTS_LABEL = "全部账号"
    }
}
