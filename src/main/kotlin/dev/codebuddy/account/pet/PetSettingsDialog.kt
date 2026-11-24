package dev.codebuddy.account.pet

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.components.AccountUiKit
import dev.codebuddy.account.ui.tokens.AccountUiColors
import dev.codebuddy.account.ui.tokens.AccountUiType
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
import javax.swing.SwingUtilities



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
    private val approvalModeCombo = labelCombo(CbApprovalMode.values()) { it.label }
    private val approvalNormalCombo = labelCombo(CbNormalPolicy.values()) { it.label }
    private val approvalDangerCombo = labelCombo(CbDangerPolicy.values()) { it.label }

    /** 按类别配置的策略（含「普通」，共 7 行）；红线两类不在其中，只读展示 */
    private val riskCombos: Map<CbRiskKind, JComboBox<CbRiskPolicy>> =
        PetSettings.CONFIGURABLE_RISK_KINDS.associateWith { kind ->
            labelCombo(CbRiskPolicy.values()) { it.label }
        }

    /** 分类矩阵说明：讲清红线为什么不可配、以及通道边界 */
    private val riskHint = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.micro)
        foreground = AccountUiColors.textMuted
        text = "<html>凭证、扣费不在上面：这两类失败一次不可回滚，永远要你亲手点，" +
            "只有静默运行勾选「放开红线」才能越过。<br>" +
            "分类只对「桌宠弹窗」通道生效；页面代点看不到命令原文，仍按下面的危险命令档位处理。</html>"
    }

    private val silentCheck = JCheckBox("开启静默运行：所有确认直接放行")
    private val silentMinutesSpinner = JSpinner(
        SpinnerNumberModel(
            SilentMode.DEFAULT_MINUTES,
            SilentMode.MIN_MINUTES,
            SilentMode.MAX_MINUTES,
            5,
        )
    )
    private val silentRedlineCheck = JCheckBox("连凭证 / 扣费一起放行（不可回滚，请谨慎）")
    private val silentHint = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.micro)
        foreground = AccountUiColors.textMuted
    }
    private val approvalHint = JBLabel().apply {
        font = AccountUiType.ui(AccountUiType.micro)
        foreground = AccountUiColors.textMuted
    }
    private val hookSecondsSpinner = JSpinner(
        SpinnerNumberModel(
            45,
            PetSettings.MIN_HOOK_SECONDS,
            PetSettings.MAX_HOOK_SECONDS,
            5,
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

    private val quadrantCombo = JComboBox(PetQuadrant.values()).apply {
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
            .addComponent(section("CodeBuddy 确认") {
                addComponent(noticeCheck)
                addLabeledComponent("处理方式：", approvalModeCombo)
                addComponent(approvalHint)
                addLabeledComponent("普通命令（仅页面代点）：", approvalNormalCombo)
                addLabeledComponent("危险命令（仅页面代点）：", approvalDangerCombo)
                addLabeledComponent("桌宠弹窗等待（秒）：", hookSecondsSpinner)
                addLabeledComponent("页面超时放行等待（分钟）：", cbAutoApproveSpinner)
            })
            .addVerticalGap(JBUI.scale(8))
            .addComponent(section("命令分类放行（桌宠弹窗通道）") {
                PetSettings.CONFIGURABLE_RISK_KINDS.forEach { kind ->
                    addLabeledComponent("${kind.label}：", riskCombos.getValue(kind))
                }
                addComponent(riskHint)
            })
            .addVerticalGap(JBUI.scale(8))
            .addComponent(section("静默运行") {
                addComponent(silentCheck)
                addLabeledComponent("最长持续时间（分钟）：", silentMinutesSpinner)
                addComponent(silentRedlineCheck)
                addComponent(silentHint)
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

    /**
     * 枚举下拉统一用中文标签渲染。
     *
     * 直接复用 [AccountUiKit.styleCombo]：固定浅色档案纸底，IDE 深色主题下
     * LAF 的下拉框会变成深底浅字，自己写渲染器迟早漏掉某几项样式。
     */
    private fun <T> labelCombo(values: Array<T>, label: (T) -> String): JComboBox<T> =
        JComboBox(values).apply {
            @Suppress("UNCHECKED_CAST")
            AccountUiKit.styleCombo(this) { item -> (item as? T)?.let(label) ?: "" }
        }

    /**
     * 按处理方式联动：只有当前通道真正用得到的项才可编辑。
     *
     * 危险策略两个通道都可选（三档：一直等人工 / 超时放行 / 自动放行）；
     * 「不干预」时三个策略项全部置灰，避免主人以为它们在生效。
     */
    private fun refreshApprovalState() {
        val mode = approvalModeCombo.selectedItem as? CbApprovalMode ?: CbApprovalMode.NONE
        val pageMode = mode == CbApprovalMode.PAGE
        val danger = approvalDangerCombo.selectedItem as? CbDangerPolicy ?: CbDangerPolicy.ASK
        // 普通 / 危险两档只服务于页面代点：那条通道看不到命令原文，分不了类；
        // 桌宠弹窗通道改由下面的分类矩阵逐类决定
        approvalNormalCombo.isEnabled = pageMode
        approvalDangerCombo.isEnabled = pageMode
        riskCombos.values.forEach { it.isEnabled = mode != CbApprovalMode.NONE }
        hookSecondsSpinner.isEnabled = mode == CbApprovalMode.HOOK
        cbAutoApproveSpinner.isEnabled = pageMode
        // 静默必须有通道去放行：「不干预」时开了也不会生效，直接禁掉避免误解
        silentCheck.isEnabled = mode != CbApprovalMode.NONE
        refreshSilentState()
        val main = when (mode) {
            CbApprovalMode.NONE -> "只提醒，不自作决定"
            CbApprovalMode.PAGE -> "页面自动点「允许」（旧通道），看不到命令原文，按上面的普通 / 危险两档处理"
            CbApprovalMode.HOOK -> "桌宠气泡给同意 / 取消，按「危险命令分类放行」逐类处理"
        }
        val active = mode != CbApprovalMode.NONE
        val auto = pageMode && danger == CbDangerPolicy.AUTO
        val timeoutAllow = pageMode && danger == CbDangerPolicy.ASK_THEN_ALLOW
        val lines = ArrayList<String>()
        lines.add(main)
        if (auto) lines.add("页面通道：危险确认会自动点「允许」；凭证 / 扣费 / 对外分发仍要人工")
        if (timeoutAllow) {
            val wait = "${(cbAutoApproveSpinner.value as? Number)?.toInt() ?: PetSettings.getInstance().cbAutoApproveMinutes} 分钟"
            lines.add("页面通道：危险确认等 $wait 没人管就自动放行；凭证 / 扣费 / 对外分发仍要人工")
        }
        // 分类矩阵里选了「直接放行」的类别要显式列出来，避免主人忘了自己开过
        if (mode == CbApprovalMode.HOOK) {
            val allowKinds = PetSettings.CONFIGURABLE_RISK_KINDS.filter {
                PetSettings.getInstance().riskPolicyOf(it) == CbRiskPolicy.ALLOW
            }
            if (allowKinds.isNotEmpty()) {
                lines.add("分类矩阵直接放行：" + allowKinds.joinToString("、") { it.label } + "（逐条记审计）")
            }
        }
        // 宿主的 [SafetyRule] 在 hook 之外还有一道，hook 的 allow 豁免不了它（实测
        // `Safety rule triggered despite hook presence`）；这类命令桌宠弹常驻卡提醒但不代点
        if (active) {
            lines.add("PowerShell 递归删除 / 脚本执行 / node -e 这类命令由宿主安全规则拦截，桌宠会弹卡一直提醒但不代点，请在宿主弹框里自己点")
            // 扣费与「一直问」的确认同样没有任何通道代点，卡片一直挂着等主人自己点
            lines.add("扣费确认、以及分类里选「问，一直等主人确认」时，桌宠也弹这种常驻卡，不代点")
            // 状态对账兜底：宿主改确认文案 / 并行调用 / 重启错过日志时，按会话状态补一条
            lines.add("如果某次确认没被认出来，桌宠会按 CodeBuddy 的「等确认」状态补一条提醒（只提醒，不代点）")
        }
        // hook 通道在本机宿主不执行时是空操作；通道失效后由页面代点接管，这里如实说明
        if (mode == CbApprovalMode.HOOK) {
            // hook 的 allow 改不了宿主的 needConfirm：宿主 Auto-run 关着时照样弹「确认执行命令」，
            // 普通命令改由页面兜底点「运行」，否则主人每一条都要自己点
            lines.add("宿主若仍弹「确认执行命令」，普通命令由页面自动点「运行」；危险 / 扣费卡仍要主人自己点")
            when {
                ApprovalChannel.isHookBroken() ->
                    lines.add("当前宿主没有执行 hook：桌宠弹窗用不了，已自动改用页面代点（超时按分钟计）")
                PetSettings.getInstance().cbHookConsent == CbHookConsent.DECLINED ->
                    lines.add("还没同意改写 CodeBuddy 配置：看板「启用桌宠弹窗」可随时启用")
                PetSettings.getInstance().cbHookConsent == CbHookConsent.UNKNOWN ->
                    lines.add("还没确认过是否允许改写 CodeBuddy 配置；已存在的 hook 会继续使用")
            }
        }
        approvalHint.text = "<html>" + lines.joinToString("<br>") + "</html>"
    }

    /**
     * 刷新静默区块的文案与可编辑状态。
     *
     * 静默状态不在 [PetSettings] 的普通字段里（时间戳 + 进程令牌），
     * 所以每次都要现读 [SilentMode]，不能只看 checkbox 的勾选值。
     */
    private fun refreshSilentState() {
        val settings = PetSettings.getInstance()
        val active = SilentMode.isActive(settings)
        val left = (SilentMode.remainingMillis(settings) / 60_000L).coerceAtLeast(0L)
        val modeAllowed = silentCheck.isEnabled
        silentHint.text = when {
            !modeAllowed ->
                "<html>当前处理方式是「不干预」，没有通道会替主人点，静默不会生效；先选「桌宠弹窗」或「页面代点」。</html>"
            active -> "<html><b>静默运行中</b>：剩 $left 分钟，已自动放行 ${SilentMode.approvedCount()} 次。" +
                "凭证 / 扣费${if (settings.cbSilentAllowRedline) "已放开" else "仍要人工"}。" +
                "到期或重启 IDE 后自动关闭。</html>"
            else -> "<html>未开启。开启期间所有确认直接放行；到期自动关闭，重启 IDE 也会关闭。" +
                "状态栏会常驻朱红标识，随时可关。</html>"
        }
        silentHint.foreground = if (active) AccountUiColors.seal else AccountUiColors.textMuted
        // 开启期间锁住时长与红线，避免「改了却没生效」；要调整就先关再开
        silentMinutesSpinner.isEnabled = modeAllowed && !active
        silentRedlineCheck.isEnabled = modeAllowed && !active
    }

    /**
     * 切到「桌宠弹窗」时询问是否允许改写宿主配置。
     *
     * 只对「还没问过」的情况弹一次；已经拒绝的不再主动打扰，
     * 想通了的入口是看板「启用桌宠弹窗」按钮。
     */
    private fun askHookConsentIfNeeded() {
        val settings = PetSettings.getInstance()
        if (settings.cbApprovalMode != CbApprovalMode.HOOK) return
        if (settings.cbHookConsent != CbHookConsent.UNKNOWN) return
        if (CodeBuddyHookInstaller.isInstalled()) return
        // 下拉框的监听里直接弹模态框会把事件循环卡住，挪到下一拍
        SwingUtilities.invokeLater {
            PetService.getInstance().requestHookInstall()
            refreshApprovalState()
        }
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
        approvalModeCombo.addActionListener {
            refreshApprovalState()
            apply()
            askHookConsentIfNeeded()
        }
        approvalNormalCombo.addActionListener {
            refreshApprovalState()
            apply()
        }
        approvalDangerCombo.addActionListener {
            refreshApprovalState()
            apply()
        }
        // 分类矩阵：每一行改动即写回 cbRiskRules 并刷新提示
        riskCombos.forEach { (_, combo) ->
            combo.addActionListener {
                refreshApprovalState()
                apply()
            }
        }
        // 静默开关不走 apply()：它管的是时间戳与进程令牌，必须经过二次确认
        silentCheck.addActionListener { onSilentToggled() }
        // 等待秒数 / 分钟数变化要同步刷新提示文案里的具体数值
        hookSecondsSpinner.addChangeListener {
            refreshApprovalState()
            apply()
        }
        cbAutoApproveSpinner.addChangeListener {
            refreshApprovalState()
            apply()
        }
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
        approvalModeCombo.selectedItem = s.cbApprovalMode
        approvalNormalCombo.selectedItem = s.cbApprovalNormal
        approvalDangerCombo.selectedItem = s.cbApprovalDanger
        hookSecondsSpinner.value = s.cbApprovalTimeoutSeconds
        cbAutoApproveSpinner.value = s.cbAutoApproveMinutes
        // 分类矩阵：按已存配置回填，没配过时显示出厂默认
        riskCombos.forEach { (kind, combo) -> combo.selectedItem = s.riskPolicyOf(kind) }
        // 静默状态是「时间戳 + 进程令牌」，不是普通布尔字段，只能现问 SilentMode
        val silent = SilentMode.isActive(s)
        silentCheck.isSelected = silent
        silentRedlineCheck.isSelected = s.cbSilentAllowRedline
        if (silent) {
            val left = (SilentMode.remainingMillis(s) / 60_000L).toInt()
                .coerceIn(SilentMode.MIN_MINUTES, SilentMode.MAX_MINUTES)
            silentMinutesSpinner.value = left
        }
        refreshApprovalState()
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
        approvalModeCombo.selectedItem = CbApprovalMode.NONE
        approvalNormalCombo.selectedItem = CbNormalPolicy.ASK
        approvalDangerCombo.selectedItem = CbDangerPolicy.ASK
        PetSettings.CONFIGURABLE_RISK_KINDS.forEach { kind ->
            riskCombos[kind]?.selectedItem = PetSettings.DEFAULT_RISK_POLICY[kind] ?: CbRiskPolicy.ASK
        }
        // 恢复默认同时关掉静默：留着它等于「重置了但还是全放行」
        silentCheck.isSelected = false
        silentRedlineCheck.isSelected = false
        silentMinutesSpinner.value = SilentMode.DEFAULT_MINUTES
        SilentMode.disable(PetSettings.getInstance())
        hookSecondsSpinner.value = 45
        cbAutoApproveSpinner.value = 5
        refreshApprovalState()
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
        s.cbApprovalMode =
            (approvalModeCombo.selectedItem as? CbApprovalMode) ?: CbApprovalMode.NONE
        // 切到「不干预」时静默自动结束：没有任何通道会放行，留着只会让状态栏显示假的「静默中」
        if (s.cbApprovalMode == CbApprovalMode.NONE && SilentMode.isActive(s)) {
            SilentMode.disable(s, "处理方式已改为「不干预」，静默随之结束")
        }
        s.cbApprovalNormal =
            (approvalNormalCombo.selectedItem as? CbNormalPolicy) ?: CbNormalPolicy.ASK
        s.cbApprovalDanger =
            (approvalDangerCombo.selectedItem as? CbDangerPolicy) ?: CbDangerPolicy.ASK
        // 分类矩阵写回；红线类 setRiskPolicy 内部直接忽略，UI 也没给它下拉
        PetSettings.CONFIGURABLE_RISK_KINDS.forEach { kind ->
            (riskCombos[kind]?.selectedItem as? CbRiskPolicy)?.let { s.setRiskPolicy(kind, it) }
        }
        s.cbApprovalTimeoutSeconds =
            (hookSecondsSpinner.value as? Number)?.toInt() ?: s.cbApprovalTimeoutSeconds
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

    /**
     * 静默开关：勾选 → 二次确认 → 开启；取消勾选 → 立刻关闭。
     *
     * 二次确认不是走形式：弹窗里逐条列出会被放行的类别，并把「静默也拦不住宿主
     * SafetyRule」这条硬约束写在明面上，避免主人开了之后发现还弹窗就以为坏了。
     */
    private fun onSilentToggled() {
        if (loading) return
        val settings = PetSettings.getInstance()
        if (!silentCheck.isSelected) {
            SilentMode.disable(settings)
            refreshApprovalState()
            onChange()
            return
        }
        if (!confirmSilent()) {
            // 拒绝后回滚勾选，避免出现「勾着但没生效」的假象
            loading = true
            silentCheck.isSelected = false
            loading = false
            refreshSilentState()
            return
        }
        val minutes = (silentMinutesSpinner.value as? Number)?.toInt() ?: SilentMode.DEFAULT_MINUTES
        SilentMode.enable(settings, minutes, silentRedlineCheck.isSelected)
        refreshApprovalState()
        onChange()
    }

    /** 开启静默前的风险告知；返回 true 表示主人确认开启 */
    private fun confirmSilent(): Boolean {
        val minutes = (silentMinutesSpinner.value as? Number)?.toInt() ?: SilentMode.DEFAULT_MINUTES
        val redline = silentRedlineCheck.isSelected
        val text = buildString {
            append("<html>开启后所有确认都会直接放行，<b>不再弹桌宠气泡</b>。<br><br>")
            append("放行范围：<br>")
            PetSettings.CONFIGURABLE_RISK_KINDS.forEach { append("· ${it.label}：${it.hint}<br>") }
            append("<br>")
            if (redline) {
                append("<b>已勾选放开红线：凭证与扣费也会自动放行。</b><br>")
            } else {
                append("凭证与扣费仍要你亲手点。<br>")
            }
            append("持续时间 $minutes 分钟；到点自动关闭，重启 IDE 也会关闭。<br>")
            append("PowerShell 递归删除 / 脚本执行 / node -e 仍会被宿主拦一次，桌宠代不了。")
            append("</html>")
        }
        return Messages.showYesNoDialog(
            text,
            "确认开启静默运行",
            "确认开启",
            "取消",
            Messages.getWarningIcon(),
        ) == Messages.YES
    }

    companion object {
        /** 打开设置窗口；[onChange] 用于让 [PetService] 立即应用 */
        fun show(onChange: () -> Unit) {
            PetSettingsDialog(onChange).show()
        }
    }
}
