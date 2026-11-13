package dev.codebuddy.account.pet

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 桌宠专属设置窗口（右键桌宠 → 「桌宠设置…」）。
 *
 * 所有选项**改动即生效**并立即持久化，不需要点「确定」等二次确认：
 * 「确定 / 取消」只负责关闭窗口，避免出现「改了但没生效」的困惑。
 *
 * 与全局设置页的关系：全局页只保留「显示桌面宠物」总开关（宠物隐藏时仍可从这里重新开启），
 * 其余外观与交互项都在这里维护，避免同一个字段两处 UI。
 */
class PetSettingsDialog(private val onChange: () -> Unit) : DialogWrapper(true) {

    private val enabledCheck = JCheckBox("显示桌面宠物（关闭后释放全机唯一占用，其他 IDE 实例可接管）")
    private val badgeCheck = JCheckBox("在宠物身上显示剩余额度读数")
    private val topCheck = JCheckBox("窗口置顶")
    private val animCheck = JCheckBox("启用待机动画（呼吸 / 眨眼）")
    private val soundCheck = JCheckBox("音效：点击桌宠 / CodeBuddy 对话完成提醒（dsh-pet 原素材 click-duck.wav）")
    private val physicsCheck = JCheckBox("拖拽物理：松手后受重力下落，快速甩出会抛出并反弹")
    private val randomCheck = JCheckBox("每隔几分钟自己做一个随机小动作")
    private val randomMinutesSpinner = JSpinner(
        SpinnerNumberModel(
            3,
            PetSettings.MIN_RANDOM_MINUTES,
            PetSettings.MAX_RANDOM_MINUTES,
            1,
        )
    )
    private val noticeCheck = JCheckBox("系统提示由桌宠承载（关闭后退回 IDE 气泡通知）")
    private val cbAutoApproveSpinner = JSpinner(
        SpinnerNumberModel(
            5,
            PetSettings.MIN_AUTO_APPROVE_MINUTES,
            PetSettings.MAX_AUTO_APPROVE_MINUTES,
            1,
        )
    )
    private val whisperCheck = JCheckBox("每隔几分钟冒一句碎碎念台词")
    private val whisperMinutesSpinner = JSpinner(
        SpinnerNumberModel(
            5,
            PetSettings.MIN_RANDOM_MINUTES,
            PetSettings.MAX_RANDOM_MINUTES,
            1,
        )
    )
    private val jokeCheck = JCheckBox("每隔几分钟主动给主人讲个笑话（在线中文优先）")
    private val jokeMinutesSpinner = JSpinner(
        SpinnerNumberModel(
            10,
            PetSettings.MIN_RANDOM_MINUTES,
            PetSettings.MAX_RANDOM_MINUTES,
            1,
        )
    )

    private val scaleSpinner = JSpinner(
        SpinnerNumberModel(
            100,
            PetSettings.MIN_SCALE,
            PetSettings.MAX_SCALE,
            10,
        )
    )
    private val opacitySpinner = JSpinner(
        SpinnerNumberModel(100, PetSettings.MIN_OPACITY, 100, 5)
    )

    private val quadrantCombo = JComboBox(PetQuadrant.entries.toTypedArray()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list,
                (value as? PetQuadrant)?.label ?: "",
                index,
                isSelected,
                cellHasFocus,
            )
        }
    }

    /** 回填期间抑制「改动即生效」，避免打开窗口就写一遍设置 */
    private var loading = false

    init {
        title = "桌宠设置"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val resetButton = JButton("恢复默认外观").apply {
            addActionListener { restoreDefaults() }
        }

        val panel = FormBuilder.createFormBuilder()
            .addComponent(section("显示与外观") {
                addComponent(enabledCheck)
                addComponent(badgeCheck)
                addComponent(topCheck)
                addComponent(animCheck)
                addComponent(soundCheck)
                addComponent(physicsCheck)
                addLabeledComponent("尺寸（相对原版宽度 %）：", scaleSpinner)
                addLabeledComponent("不透明度（%）：", opacitySpinner)
                addLabeledComponent("停靠位置：", quadrantCombo)
            })
            .addVerticalGap(JBUI.scale(8))
            .addComponent(section("桌宠自身行为") {
                addComponent(randomCheck)
                addLabeledComponent("随机动作间隔（分钟）：", randomMinutesSpinner)
                addComponent(whisperCheck)
                addLabeledComponent("碎碎念间隔（分钟）：", whisperMinutesSpinner)
                addComponent(jokeCheck)
                addLabeledComponent("主动讲笑话间隔（分钟）：", jokeMinutesSpinner)
            })
            .addVerticalGap(JBUI.scale(8))
            .addComponent(section("提示联动") {
                addComponent(noticeCheck)
                addLabeledComponent("超时代点等待（分钟）：", cbAutoApproveSpinner)
            })
            .addVerticalGap(JBUI.scale(8))
            .addComponent(buttonRow(resetButton))
            .addVerticalGap(JBUI.scale(8))
            .addComponent(
                JBLabel(
                    "<html>桌宠形象与素材来自 <a href=\"https://github.com/PC2005-cloud/dsh-pet\">dsh-pet</a>" +
                        "（PC2005-cloud）：代码 MIT，形象 / 素材 CC BY-NC-SA 4.0（署名 · 非商业 · 相同方式共享）。<br>" +
                        "形象为社区二创 DeepSeek「大肥鱼」，原型 @上善无形、设计 @ZipZipPipe；本插件免费非商用，素材仅做格式转换。<br>" +
                        "完整声明见 <a href=\"https://github.com/qiXiaoFanTomato/codebuddy-account-manager/blob/main/THIRD-PARTY-NOTICES.md\">THIRD-PARTY-NOTICES.md</a>。</html>"
                ).apply { foreground = AccountUiColors.textMuted }
            )
            .panel

        panel.preferredSize = Dimension(JBUI.scale(470), panel.preferredSize.height)
        loadFromSettings()
        bindLiveApply()
        return JBScrollPane(panel).apply {
            preferredSize = Dimension(JBUI.scale(510), JBUI.scale(560))
        }
    }

    /** 带标题边框的设置分组；分组内维持原来的 FormBuilder 布局 */
    private fun section(title: String, block: FormBuilder.() -> Unit): JPanel {
        val form = FormBuilder.createFormBuilder()
        form.block()
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createTitledBorder(title)
            add(form.panel, BorderLayout.CENTER)
        }
    }

    private fun buttonRow(vararg buttons: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            buttons.forEach { add(it) }
        }

    override fun getPreferredFocusedComponent(): JComponent = scaleSpinner

    override fun doOKAction() {
        apply()
        super.doOKAction()
    }

    private fun bindLiveApply() {
        enabledCheck.addActionListener { apply() }
        badgeCheck.addActionListener { apply() }
        topCheck.addActionListener { apply() }
        animCheck.addActionListener { apply() }
        soundCheck.addActionListener { apply() }
        physicsCheck.addActionListener { apply() }
        randomCheck.addActionListener { apply() }
        randomMinutesSpinner.addChangeListener { apply() }
        noticeCheck.addActionListener { apply() }
        cbAutoApproveSpinner.addChangeListener { apply() }
        whisperCheck.addActionListener { apply() }
        whisperMinutesSpinner.addChangeListener { apply() }
        jokeCheck.addActionListener { apply() }
        jokeMinutesSpinner.addChangeListener { apply() }
        scaleSpinner.addChangeListener { apply() }
        opacitySpinner.addChangeListener { apply() }
        quadrantCombo.addActionListener { apply() }
    }

    private fun loadFromSettings() {
        loading = true
        val s = PetSettings.getInstance()
        enabledCheck.isSelected = s.enabled
        badgeCheck.isSelected = s.showQuotaBadge
        topCheck.isSelected = s.alwaysOnTop
        animCheck.isSelected = s.animationsEnabled
        soundCheck.isSelected = s.clickSoundEnabled
        physicsCheck.isSelected = s.physicsEnabled
        randomCheck.isSelected = s.randomActionsEnabled
        randomMinutesSpinner.value = s.randomActionMinutes
        noticeCheck.isSelected = s.noticePetEnabled
        cbAutoApproveSpinner.value = s.cbAutoApproveMinutes
        whisperCheck.isSelected = s.whisperEnabled
        whisperMinutesSpinner.value = s.whisperMinutes
        jokeCheck.isSelected = s.jokeEnabled
        jokeMinutesSpinner.value = s.jokeMinutes
        scaleSpinner.value = s.scalePercent
        opacitySpinner.value = s.opacityPercent
        quadrantCombo.selectedItem = s.quadrant
        loading = false
    }

    private fun restoreDefaults() {
        loading = true
        scaleSpinner.value = 100
        opacitySpinner.value = 100
        topCheck.isSelected = true
        badgeCheck.isSelected = true
        animCheck.isSelected = true
        soundCheck.isSelected = true
        physicsCheck.isSelected = true
        randomCheck.isSelected = true
        randomMinutesSpinner.value = 3
        noticeCheck.isSelected = true
        cbAutoApproveSpinner.value = 5
        whisperCheck.isSelected = true
        whisperMinutesSpinner.value = 5
        jokeCheck.isSelected = true
        jokeMinutesSpinner.value = 10
        quadrantCombo.selectedItem = PetQuadrant.BOTTOM_RIGHT
        loading = false
        apply()
    }

    private fun apply() {
        if (loading) return
        val s = PetSettings.getInstance()
        s.enabled = enabledCheck.isSelected
        s.showQuotaBadge = badgeCheck.isSelected
        s.alwaysOnTop = topCheck.isSelected
        s.animationsEnabled = animCheck.isSelected
        s.clickSoundEnabled = soundCheck.isSelected
        s.physicsEnabled = physicsCheck.isSelected
        s.randomActionsEnabled = randomCheck.isSelected
        s.randomActionMinutes = (randomMinutesSpinner.value as? Number)?.toInt() ?: s.randomActionMinutes
        s.noticePetEnabled = noticeCheck.isSelected
        s.cbAutoApproveMinutes =
            (cbAutoApproveSpinner.value as? Number)?.toInt() ?: s.cbAutoApproveMinutes
        s.whisperEnabled = whisperCheck.isSelected
        s.whisperMinutes = (whisperMinutesSpinner.value as? Number)?.toInt() ?: s.whisperMinutes
        s.jokeEnabled = jokeCheck.isSelected
        s.jokeMinutes = (jokeMinutesSpinner.value as? Number)?.toInt() ?: s.jokeMinutes
        s.scalePercent = (scaleSpinner.value as? Number)?.toInt() ?: s.scalePercent
        s.opacityPercent = (opacitySpinner.value as? Number)?.toInt() ?: s.opacityPercent
        s.quadrant = (quadrantCombo.selectedItem as? PetQuadrant) ?: PetQuadrant.BOTTOM_RIGHT
        onChange()
    }

    companion object {
        /** 打开设置窗口；[onChange] 用于让 [PetService] 立即应用 */
        fun show(onChange: () -> Unit) {
            PetSettingsDialog(onChange).show()
        }
    }
}
