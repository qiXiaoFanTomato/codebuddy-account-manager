package dev.codebuddy.account.pet

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.ui.AccountUiColors
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * 快捷短语管理窗口（仿 WorkDaddy「快捷短语」页）。
 *
 * 交互口径：
 *  - 左侧列表按展示顺序排列，双击条目直接发送到 CodeBuddy 输入框；
 *  - 右侧编辑标题与正文，切换条目 / 关闭窗口 / 发送前都会自动保存，不需要额外「确定」；
 *  - 正文为空的条目不会被保存，避免列表里堆空壳；
 *  - 「仅填入」只写进输入框等主人自己改，「发送」写完立即回车 / 点发送按钮。
 *
 * 发送动作走 [QuickPhraseSender]，是否真的发出去由页面侧回执；
 * 找不到聊天面板时直接提示先打开 CodeBuddy 侧边栏。
 */
class QuickPhraseDialog(
    private val projectName: String? = null,
) : DialogWrapper(true) {

    private val entries: MutableList<QuickPhraseStore.Phrase> = QuickPhraseStore.list().toMutableList()
    private val model = DefaultListModel<QuickPhraseStore.Phrase>()
    private val phraseList = JBList(model)
    private val titleField = JBTextField()
    private val textArea = JTextArea(10, 36)

    /** 回填期间抑制选中事件，避免「一选中就保存」把正在编辑的内容覆盖掉 */
    private var loading = false

    init {
        title = "快捷短语"
        titleField.toolTipText = "标题留空时自动取正文首行"
        // 空库先给一条空占位并选中，主人打开就能直接写，不用先点「新增」
        if (entries.isEmpty()) entries.add(QuickPhraseStore.Phrase("新短语", ""))
        entries.forEach(model::addElement)
        init()
        bind()
        phraseList.selectedIndex = 0
        fillEditor(0)
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(360))
            add(JBScrollPane(phraseList), BorderLayout.CENTER)
            add(listButtons(), BorderLayout.SOUTH)
        }

        val editor = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JLabel("标题"), BorderLayout.WEST)
                add(titleField, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            textArea.lineWrap = true
            textArea.wrapStyleWord = true
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(editorButtons(), BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            add(left, BorderLayout.WEST)
            add(editor, BorderLayout.CENTER)
            add(
                JBLabel("双击条目直接发送；切换条目自动保存").apply {
                    foreground = AccountUiColors.textMuted
                },
                BorderLayout.SOUTH,
            )
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(420))
        }
    }

    private fun listButtons(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(button("新增") { addPhrase() })
        add(button("删除") { removePhrase() })
        add(button("上移") { move(-1) })
        add(button("下移") { move(1) })
    }

    private fun editorButtons(): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(button("保存修改") { commit() })
        add(button("仅填入") { deliver(autoSend = false) })
        add(button("发送") { deliver(autoSend = true) })
    }

    private fun button(text: String, action: () -> Unit): JButton =
        JButton(text).apply { addActionListener { action() } }

    private fun bind() {
        phraseList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        phraseList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val phrase = value as? QuickPhraseStore.Phrase
                val preview = phrase?.text?.replace(Regex("\\s+"), " ")?.take(26).orEmpty()
                val html = "<html><b>${escape(phrase?.title.orEmpty())}</b><br>" +
                    "<font color='#8A8A8A'>${escape(preview)}</font></html>"
                return super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus)
            }
        }
        phraseList.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        phraseList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) deliver(autoSend = true)
            }
        })
    }

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 列表选中变化：先保存上一条，再回填当前条 */
    private fun showSelected() {
        if (loading) return
        commit()
        fillEditor(phraseList.selectedIndex)
    }

    /** 把编辑区回填为指定条目；-1 表示清空 */
    private fun fillEditor(index: Int) {
        loading = true
        if (index in entries.indices) {
            titleField.text = entries[index].title
            textArea.text = entries[index].text
        } else {
            titleField.text = ""
            textArea.text = ""
        }
        loading = false
    }

    /** 把编辑区内容写回选中条目；正文为空时保留原值，不误删 */
    private fun commit(): Boolean {
        val index = phraseList.selectedIndex
        if (index !in entries.indices) return false
        val phrase = QuickPhraseStore.normalize(
            QuickPhraseStore.Phrase(titleField.text, textArea.text)
        ) ?: return false
        if (phrase == entries[index]) return true
        entries[index] = phrase
        QuickPhraseStore.save(entries)
        refresh(index)
        return true
    }

    private fun refresh(select: Int) {
        loading = true
        model.clear()
        entries.forEach(model::addElement)
        if (entries.isNotEmpty()) {
            phraseList.selectedIndex = select.coerceIn(0, entries.size - 1)
        }
        loading = false
        fillEditor(if (entries.isEmpty()) -1 else phraseList.selectedIndex)
    }

    private fun addPhrase() {
        commit()
        // 编辑区有内容但列表里还没有对应条目：先作为新条目落库，别丢
        if (phraseList.selectedIndex !in entries.indices) {
            QuickPhraseStore.normalize(QuickPhraseStore.Phrase(titleField.text, textArea.text))?.let {
                entries.add(it)
                QuickPhraseStore.save(entries)
            }
        }
        // 再插一条空占位让主人继续写，正文为空时不落库
        entries.add(QuickPhraseStore.Phrase("新短语", ""))
        QuickPhraseStore.save(entries)
        refresh(entries.size - 1)
        titleField.requestFocusInWindow()
    }

    private fun removePhrase() {
        val index = phraseList.selectedIndex
        if (index !in entries.indices) return
        val phrase = entries[index]
        val answer = Messages.showYesNoDialog(
            "删除快捷短语「${phrase.title}」？",
            "删除快捷短语",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        entries.removeAt(index)
        QuickPhraseStore.save(entries)
        // 删空了补一条空占位，主人可以接着写，不用再点「新增」
        if (entries.isEmpty()) entries.add(QuickPhraseStore.Phrase("新短语", ""))
        refresh(index)
    }

    private fun move(delta: Int) {
        commit()
        val index = phraseList.selectedIndex
        val target = index + delta
        if (index !in entries.indices || target !in entries.indices) return
        val moved = entries.removeAt(index)
        entries.add(target, moved)
        QuickPhraseStore.save(entries)
        refresh(target)
    }

    /** 发送 / 仅填入：先保存编辑内容，再把正文写进 CodeBuddy 输入框 */
    private fun deliver(autoSend: Boolean) {
        val text = textArea.text.trim()
        if (text.isEmpty()) {
            PetNotifier.warn("快捷短语", "先写一条短语再发送")
            return
        }
        val index = phraseList.selectedIndex
        QuickPhraseStore.normalize(QuickPhraseStore.Phrase(titleField.text, text))?.let { phrase ->
            if (index in entries.indices) entries[index] = phrase else entries.add(phrase)
            QuickPhraseStore.save(entries)
            refresh(if (index in entries.indices) index else entries.size - 1)
        }
        if (!QuickPhraseSender.send(text, autoSend, projectName)) {
            PetNotifier.warn(
                "快捷短语",
                "没找到 CodeBuddy 聊天面板：先在 IDE 里打开 CodeBuddy 侧边栏对话，再试一次",
            )
            return
        }
        PetNotifier.notify(
            PetNotifier.Level.INFO,
            if (autoSend) "已发到 CodeBuddy" else "已填入 CodeBuddy 输入框",
            text.lineSequence().first().trim().take(60),
        )
    }

    override fun createActions(): Array<Action> {
        cancelAction.putValue(Action.NAME, "关闭")
        return arrayOf(cancelAction)
    }

    /** 关闭前再保存一次，避免编辑完直接关窗丢内容 */
    override fun doCancelAction() {
        commit()
        super.doCancelAction()
    }

    companion object {
        fun show(projectName: String?) {
            QuickPhraseDialog(projectName).show()
        }
    }
}
