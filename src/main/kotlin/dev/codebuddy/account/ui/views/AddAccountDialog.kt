package dev.codebuddy.account.ui.views

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.credential.CredentialParser
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AddAccountService
import dev.codebuddy.account.ui.components.AccountUiKit
import dev.codebuddy.account.ui.components.JellyButton
import dev.codebuddy.account.ui.components.JellyButtonVariant
import dev.codebuddy.account.ui.components.SegmentedTabs
import dev.codebuddy.account.ui.tokens.AccountUiColors
import dev.codebuddy.account.ui.tokens.AccountUiSpacing
import dev.codebuddy.account.ui.tokens.AccountUiType
import dev.codebuddy.account.ui.tokens.baseLabelFont
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * 添加账号窗口：三条入口。
 *
 *  - OAuth 授权：向 `/v2/plugin/auth/state` 申请 state，浏览器完成授权后轮询 `/auth/token` 换取 token；
 *  - Token / JSON：粘贴 `.info` 凭据、账号库导出 JSON 或单个 accessToken；
 *  - 本地导入：扫描 WorkBuddy auth 目录，或手选 `.info` 文件。
 *
 * 协议细节见 [AddAccountService]。窗口只负责交互，落库统一走 [AccountLibrary.importSnapshot]。
 */
class AddAccountDialog(
    private val project: Project?,
    /** 新增/更新条数回调（-1 表示未产生变更） */
    private val onChanged: (Int) -> Unit,
) : DialogWrapper(project, true) {

    private val cancelled = AtomicBoolean(false)
    private var session: AddAccountService.Session? = null

    /** 申请超过 5 秒仍未返回时的兜底提示 */
    private var slowHintTimer: Timer? = null

    private val cards = JPanel(CardLayout())
    private lateinit var tabs: SegmentedTabs

    private val urlField = JBTextField()
    private val oauthStatus = statusLabel()
    private lateinit var openAction: JellyButton
    private val tokenArea = JBTextArea(10, 48)
    private val tokenStatus = statusLabel()
    private val localStatus = statusLabel()

    init {
        title = "添加 CodeBuddy 账号"
        init()
        startOAuth()
    }

    // ---------------------------------------------------------------
    // 布局
    // ---------------------------------------------------------------

    override fun createCenterPanel(): JComponent {
        tabs = AccountUiKit.segmented(TAB_TITLES, 0) { switchTo(it) }

        val tabRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(tabs)
        }

        val head = JPanel(BorderLayout()).apply {
            background = AccountUiColors.cardBg
            border = JBUI.Borders.empty(AccountUiSpacing.md, AccountUiSpacing.cardPadH, 0, AccountUiSpacing.cardPadH)
            add(tabRow, BorderLayout.WEST)
        }

        cards.isOpaque = false
        cards.add(oauthPanel(), TAB_OAUTH)
        cards.add(tokenPanel(), TAB_TOKEN)
        cards.add(localPanel(), TAB_LOCAL)

        return JPanel(BorderLayout()).apply {
            background = AccountUiColors.cardBg
            add(head, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(520))
        }
    }

    private fun switchTo(index: Int) {
        (cards.layout as CardLayout).show(cards, TAB_KEYS[index])
    }

    /**
     * OAuth 授权页：说明 → 授权链接 → 主按钮 → 状态行 → 说明卡。
     *
     * 分区自上而下单列排布，每块都塞进全宽行（[fullRow]），
     * 左边缘与输入框、按钮严格对齐；状态行独占一行，长文案不会挤掉右侧动作。
     */
    private fun oauthPanel(): JComponent {
        val column = stack()

        // ① 说明
        column.add(fullRow {
            add(hint("在浏览器完成授权后，账号会自动添加到本机，无需手动复制 token。"), BorderLayout.CENTER)
        })
        column.add(strut(AccountUiSpacing.md))

        // ② 授权链接：标题行（左标签 / 右重新申请）
        column.add(fullRow {
            add(caption("授权链接"), BorderLayout.WEST)
            add(AccountUiKit.action("重新申请链接") { startOAuth() }, BorderLayout.EAST)
        })
        column.add(strut(AccountUiSpacing.xs))

        urlField.apply {
            isEditable = false
            background = AccountUiColors.itemBg
            foreground = AccountUiColors.textPrimary
            font = AccountUiType.ui(AccountUiType.label)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(AccountUiColors.rule),
                JBUI.Borders.empty(4, 8),
            )
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(28))
        }
        val copyBtn = AccountUiKit.button("复制", JellyButtonVariant.SECONDARY, null, "复制授权链接") {
            copyUrl()
        }
        column.add(fullRow {
            add(urlField, BorderLayout.CENTER)
            add(copyBtn, BorderLayout.EAST)
        })
        column.add(strut(AccountUiSpacing.xs))

        column.add(fullRow {
            add(
                caption(
                    "授权有效期 ${AddAccountService.TIMEOUT_SECONDS} 秒 · " +
                        "轮询间隔 ${AddAccountService.POLL_INTERVAL_MS / 1000.0} 秒"
                ).apply { foreground = AccountUiColors.textFaint },
                BorderLayout.CENTER,
            )
        })
        column.add(strut(AccountUiSpacing.md))

        // ③ 主按钮：整行主色，点了就去浏览器
        val openBtn = AccountUiKit.button(
            "在浏览器中打开",
            JellyButtonVariant.PRIMARY,
            null,
            "在默认浏览器打开 CodeBuddy 授权页",
        ) { openInBrowser() }.apply { isEnabled = false }
        column.add(fullRow { add(openBtn, BorderLayout.CENTER) })
        column.add(strut(AccountUiSpacing.md))

        // ④ 状态行：独占一行，颜色 + 文案双通道
        column.add(fullRow(heightDp = AccountUiSpacing.xl) { add(oauthStatus, BorderLayout.CENTER) })
        column.add(strut(AccountUiSpacing.md))

        // ⑤ 说明卡
        column.add(noticeCard())
        column.add(Box.createVerticalGlue())

        // 记住按钮引用：申请成功后启用
        openAction = openBtn
        return column
    }

    /** Token / JSON 粘贴页 */
    private fun tokenPanel(): JComponent {
        val column = stack()
        column.add(fullRow {
            add(hint("支持 .info 凭据 JSON、账号库导出 JSON，或单个 accessToken。"), BorderLayout.CENTER)
        })
        column.add(strut(AccountUiSpacing.sm))

        tokenArea.apply {
            lineWrap = true
            wrapStyleWord = true
            background = AccountUiColors.cardBg
            foreground = AccountUiColors.textPrimary
            font = AccountUiType.ui(AccountUiType.label)
            border = JBUI.Borders.empty(AccountUiSpacing.sm)
        }
        val scroll = AccountUiKit.styleScroll(JBScrollPane(tokenArea), AccountUiColors.cardBg).apply {
            border = JBUI.Borders.customLine(AccountUiColors.rule)
        }
        // 输入区占满剩余高度，这行不套 fullRow（否则会被压成首选高度）
        val scrollRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(scroll, BorderLayout.CENTER)
        }
        column.add(scrollRow)
        column.add(strut(AccountUiSpacing.md))

        val addBtn = AccountUiKit.button("添加账号", JellyButtonVariant.PRIMARY, null, "解析并保存到账号库") {
            submitText()
        }
        column.add(fullRow { add(addBtn, BorderLayout.CENTER) })
        column.add(strut(AccountUiSpacing.sm))

        column.add(fullRow(heightDp = AccountUiSpacing.xl) { add(tokenStatus, BorderLayout.CENTER) })
        return column
    }

    /** 本地导入页 */
    private fun localPanel(): JComponent {
        val column = stack()
        column.add(fullRow {
            add(hint("扫描本机 auth 目录与 IDE 已登录凭据，也可直接选择 .info 文件。"), BorderLayout.CENTER)
        })
        column.add(strut(AccountUiSpacing.md))

        val scanBtn = AccountUiKit.button("扫描 auth 目录", JellyButtonVariant.PRIMARY, null, "导入本机可发现的凭据") {
            scanAuthDir()
        }
        column.add(fullRow { add(scanBtn, BorderLayout.CENTER) })
        column.add(strut(AccountUiSpacing.sm))

        val pickBtn = AccountUiKit.button("选择 .info 文件…", JellyButtonVariant.SECONDARY, null, "从磁盘选择凭据文件") {
            pickFiles()
        }
        column.add(fullRow { add(pickBtn, BorderLayout.CENTER) })
        column.add(strut(AccountUiSpacing.sm))

        column.add(fullRow(heightDp = AccountUiSpacing.xl) { add(localStatus, BorderLayout.CENTER) })
        column.add(Box.createVerticalGlue())
        return column
    }

    /** OAuth 页脚说明卡：三条要点，整块弱底、无阴影 */
    private fun noticeCard(): JComponent {
        val card = AccountUiKit.card(fill = AccountUiColors.itemBg)
        val column = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(AccountUiSpacing.sm, AccountUiSpacing.md)
        }
        column.add(bold("仅授权 IDE 登录信息"))
        column.add(strut(AccountUiSpacing.xs))
        listOf(
            "在浏览器完成 OAuth 后即可添加账号并用于 IDE 切换。",
            "添加后在账号页刷新额度即可拉取资源包数据。",
            "账号卡片将按资源包展示额度、进度和刷新/到期时间。",
        ).forEach { line ->
            column.add(fullRow { add(hint("· $line"), BorderLayout.CENTER) })
        }
        card.add(column, BorderLayout.CENTER)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    // ---------------------------------------------------------------
    // 版式辅助
    // ---------------------------------------------------------------

    /** 页面统一纵向容器：撑满宽度、自上而下堆叠 */
    private fun stack(): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(AccountUiSpacing.lg, AccountUiSpacing.cardPadH)
    }

    /**
     * 撑满整行的行容器。
     *
     * BoxLayout 按子组件首选宽度定位单行文字，看起来会缩在中间；
     * 统一套一层全宽 BorderLayout 行，左边缘才与输入框、按钮严格对齐。
     * [heightDp] 给定时行高固定（用于状态行），否则取当前首选高度。
     */
    private fun fullRow(
        heightDp: Int? = null,
        gapDp: Int = AccountUiSpacing.sm,
        build: JPanel.() -> Unit,
    ): JPanel = JPanel(BorderLayout(JBUI.scale(gapDp), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        build()
        maximumSize = Dimension(Int.MAX_VALUE, heightDp?.let { JBUI.scale(it) } ?: preferredSize.height)
    }

    /** 纵向留白 */
    private fun strut(dp: Int): Component = Box.createVerticalStrut(JBUI.scale(dp))

    /** 区块小标签：辅助说明字号 + 弱文本色 */
    private fun caption(text: String): JLabel = JLabel(text).apply {
        font = AccountUiType.ui(AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        alignmentX = Component.LEFT_ALIGNMENT
        horizontalAlignment = SwingConstants.LEADING
    }

    private fun hint(text: String): JLabel = JLabel(text).apply {
        font = AccountUiType.ui(AccountUiType.label)
        foreground = AccountUiColors.textSecondary
        // BoxLayout 会把 JLabel 拉成整行宽，只设 alignmentX 不够，文字对齐也要显式给
        alignmentX = Component.LEFT_ALIGNMENT
        horizontalAlignment = SwingConstants.LEADING
    }

    private fun bold(text: String): JLabel = JLabel(text).apply {
        font = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.label)
        foreground = AccountUiColors.ink
        alignmentX = Component.LEFT_ALIGNMENT
        horizontalAlignment = SwingConstants.LEADING
    }

    private fun statusLabel(): JLabel = JLabel(" ").apply {
        font = AccountUiType.ui(AccountUiType.label)
        foreground = AccountUiColors.textSecondary
    }

    /**
     * 后台线程回 EDT 更新界面。
     *
     * **必须用 [ModalityState.any]**：本窗口是模态对话框，`invokeLater(runnable)` 默认排的是
     * NON_MODAL 任务，会被模态阻塞到窗口关闭为止 —— 表现就是后台早已拿到结果，
     * 界面却一直停在「正在申请授权链接…」。
     */
    private fun uiLater(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any())
    }

    // ---------------------------------------------------------------
    // OAuth 流程
    // ---------------------------------------------------------------

    private fun startOAuth() {
        session = null
        oauthStatus.foreground = AccountUiColors.textSecondary
        oauthStatus.text = "正在申请授权链接…"
        urlField.text = ""
        if (::openAction.isInitialized) openAction.isEnabled = false
        startSlowHint()

        ApplicationManager.getApplication().executeOnPooledThread {
            // 兜住 Throwable：否则异常被线程池吞掉，状态行会永远停在「正在申请…」
            val result = try {
                AddAccountService.start()
            } catch (e: Throwable) {
                Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName, e))
            }
            uiLater {
                if (cancelled.get()) return@uiLater
                slowHintTimer?.stop()
                result.fold(
                    onSuccess = { s ->
                        session = s
                        urlField.text = s.loginUrl
                        urlField.caretPosition = 0
                        if (::openAction.isInitialized) openAction.isEnabled = true
                        oauthStatus.foreground = AccountUiColors.textSecondary
                        oauthStatus.text = "等待授权完成…"
                        pollToken(s)
                    },
                    onFailure = { e ->
                        oauthStatus.foreground = AccountUiColors.danger
                        oauthStatus.text = "申请授权链接失败：${e.message ?: "未知错误"}"
                    },
                )
            }
        }
    }

    /** 申请迟迟不返回时给出可操作提示，避免界面像卡死 */
    private fun startSlowHint() {
        slowHintTimer?.stop()
        slowHintTimer = Timer(5000) {
            if (session == null && !cancelled.get()) {
                oauthStatus.foreground = AccountUiColors.warning
                oauthStatus.text = "仍在申请授权链接…如长时间无响应，请检查 IDE 的 HTTP 代理设置"
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun pollToken(session: AddAccountService.Session) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                AddAccountService.pollToken(session) { cancelled.get() }
            } catch (e: Throwable) {
                Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName, e))
            }
            if (cancelled.get()) return@executeOnPooledThread

            result.fold(
                onSuccess = { token ->
                    val account = AddAccountService.fetchAccount(token.accessToken, session.state, token.domain)
                        ?: AddAccountService.resolveByToken(token.accessToken).getOrNull()
                    if (account == null) {
                        uiLater {
                            oauthStatus.foreground = AccountUiColors.danger
                            oauthStatus.text = "已获取 token，但无法获取账号信息，请改用 Token / JSON 粘贴完整凭据。"
                        }
                        return@fold
                    }
                    saveAll(listOf(AddAccountService.buildSnapshot(token, account))) { message ->
                        oauthStatus.foreground = AccountUiColors.danger
                        oauthStatus.text = message
                    }
                },
                onFailure = { e ->
                    uiLater {
                        oauthStatus.foreground = AccountUiColors.danger
                        oauthStatus.text = e.message ?: "授权失败"
                    }
                },
            )
        }
    }

    private fun openInBrowser() {
        val url = session?.loginUrl ?: urlField.text.trim()
        if (url.isEmpty()) {
            oauthStatus.foreground = AccountUiColors.danger
            oauthStatus.text = "还没有可用的授权链接，请先重新申请。"
            return
        }
        BrowserUtil.browse(url)
        oauthStatus.foreground = AccountUiColors.textSecondary
        oauthStatus.text = "已在浏览器打开授权页，完成后此窗口会自动更新"
    }

    private fun copyUrl() {
        val url = urlField.text.trim()
        if (url.isEmpty()) return
        val selection = java.awt.datatransfer.StringSelection(url)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
        oauthStatus.foreground = AccountUiColors.textSecondary
        oauthStatus.text = "授权链接已复制"
    }

    // ---------------------------------------------------------------
    // Token / JSON
    // ---------------------------------------------------------------

    private fun submitText() {
        when (val parsed = AddAccountService.parseInput(tokenArea.text)) {
            is AddAccountService.InputParse.Snapshots -> {
                setTokenStatus("正在保存 ${parsed.list.size} 个账号…", AccountUiColors.textSecondary)
                saveAll(parsed.list)
            }

            is AddAccountService.InputParse.TokenOnly -> addByToken(parsed.token)

            is AddAccountService.InputParse.Invalid ->
                setTokenStatus(parsed.message, AccountUiColors.danger)
        }
    }

    private fun addByToken(token: String) {
        setTokenStatus("正在校验 token…", AccountUiColors.textSecondary)
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = AddAccountService.resolveByToken(token)
            info.fold(
                onSuccess = { account ->
                    saveAll(listOf(AddAccountService.snapshotFromToken(token, account)))
                },
                onFailure = { e ->
                    setTokenStatus(e.message ?: "token 校验失败", AccountUiColors.danger)
                },
            )
        }
    }

    private fun setTokenStatus(text: String, tone: java.awt.Color) {
        uiLater {
            tokenStatus.foreground = tone
            tokenStatus.text = text
        }
    }

    // ---------------------------------------------------------------
    // 本地导入
    // ---------------------------------------------------------------

    private fun scanAuthDir() {
        setLocalStatus("正在扫描 auth 目录…", AccountUiColors.textSecondary)
        ApplicationManager.getApplication().executeOnPooledThread {
            val count = runCatching { AccountLibrary.getInstance().importFromAuthDir() }.getOrDefault(-1)
            uiLater {
                when {
                    count < 0 -> {
                        localStatus.foreground = AccountUiColors.danger
                        localStatus.text = "扫描失败，请查看 IDE 日志"
                    }

                    count == 0 -> {
                        localStatus.foreground = AccountUiColors.textSecondary
                        localStatus.text = "auth 目录下没有新的可导入凭据"
                    }

                    else -> finish(count)
                }
            }
        }
    }

    private fun pickFiles() {
        // chooseFiles=true, chooseMultiple=true：一次可选多个 .info
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle("选择 CodeBuddy 凭据文件")
        val files = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project)
        if (files.isEmpty()) return

        setLocalStatus("正在导入 ${files.size} 个文件…", AccountUiColors.textSecondary)
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshots = files.mapNotNull { file ->
                runCatching { CredentialParser.parse(Paths.get(file.path)) }.getOrNull()
            }
            if (snapshots.isEmpty()) {
                uiLater {
                    localStatus.foreground = AccountUiColors.danger
                    localStatus.text = "选中的文件里没有可识别的凭据"
                }
                return@executeOnPooledThread
            }
            saveAll(snapshots) { message -> setLocalStatus(message, AccountUiColors.danger) }
        }
    }

    private fun setLocalStatus(text: String, tone: java.awt.Color) {
        localStatus.foreground = tone
        localStatus.text = text
    }

    // ---------------------------------------------------------------
    // 落库
    // ---------------------------------------------------------------

    /**
     * 后台批量写入账号库，成功后关闭窗口并回报条数。
     *
     * [onFail] 在 EDT 回调，由调用方决定把失败提示写到哪个 tab 的状态行。
     */
    private fun saveAll(
        snapshots: List<CredentialSnapshot>,
        onFail: (String) -> Unit = { showTokenError(it) },
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val library = AccountLibrary.getInstance()
            val saved = snapshots.count { runCatching { library.importSnapshot(it) }.getOrDefault(false) }
            uiLater {
                if (saved > 0) finish(saved) else onFail("保存失败，请查看 IDE 日志")
            }
        }
    }

    private fun showTokenError(message: String) {
        tokenStatus.foreground = AccountUiColors.danger
        tokenStatus.text = message
    }

    private fun finish(count: Int) {
        onChanged(count)
        close(OK_EXIT_CODE)
    }

    // ---------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------

    override fun createActions(): Array<Action> {
        cancelAction.putValue(Action.NAME, "关闭")
        return arrayOf(cancelAction)
    }

    override fun doCancelAction() {
        cancelled.set(true)
        super.doCancelAction()
    }

    override fun dispose() {
        cancelled.set(true)
        slowHintTimer?.stop()
        super.dispose()
    }

    companion object {
        private const val TAB_OAUTH = "oauth"
        private const val TAB_TOKEN = "token"
        private const val TAB_LOCAL = "local"
        private val TAB_KEYS = listOf(TAB_OAUTH, TAB_TOKEN, TAB_LOCAL)
        private val TAB_TITLES = listOf("OAuth 授权", "Token / JSON", "本地导入")

        /** 打开窗口；[onChanged] 在成功写入账号库后回调条数 */
        fun show(project: Project?, onChanged: (Int) -> Unit) {
            AddAccountDialog(project, onChanged).show()
        }
    }
}
