package dev.codebuddy.account.pet

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import java.awt.Component
import java.awt.Dimension
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
    private val soundCheck = JCheckBox("点击音效（dsh-pet 原素材 click-duck.wav）")
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
    private val whisperCheck = JCheckBox("每隔几分钟冒一句碎碎念台词")
    private val whisperMinutesSpinner = JSpinner(
        SpinnerNumberModel(
            5,
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
            .addComponent(enabledCheck)
            .addVerticalGap(JBUI.scale(6))
            .addComponent(badgeCheck)
            .addComponent(topCheck)
            .addComponent(animCheck)
            .addComponent(soundCheck)
            .addComponent(physicsCheck)
            .addComponent(randomCheck)
            .addLabeledComponent("随机动作间隔（分钟）：", randomMinutesSpinner)
            .addComponent(noticeCheck)
            .addComponent(whisperCheck)
            .addLabeledComponent("碎碎念间隔（分钟）：", whisperMinutesSpinner)
            .addVerticalGap(JBUI.scale(6))
            .addLabeledComponent("尺寸（相对原版宽度 %）：", scaleSpinner)
            .addLabeledComponent("不透明度（%）：", opacitySpinner)
            .addLabeledComponent("停靠位置：", quadrantCombo)
            .addVerticalGap(JBUI.scale(8))
            .addComponent(resetButton)
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

        panel.preferredSize = Dimension(JBUI.scale(460), panel.preferredSize.height)
        loadFromSettings()
        bindLiveApply()
        return panel
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
        whisperCheck.addActionListener { apply() }
        whisperMinutesSpinner.addChangeListener { apply() }
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
        whisperCheck.isSelected = s.whisperEnabled
        whisperMinutesSpinner.value = s.whisperMinutes
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
        whisperCheck.isSelected = true
        whisperMinutesSpinner.value = 5
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
        s.whisperEnabled = whisperCheck.isSelected
        s.whisperMinutes = (whisperMinutesSpinner.value as? Number)?.toInt() ?: s.whisperMinutes
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
