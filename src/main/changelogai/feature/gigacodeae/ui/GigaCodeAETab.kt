package changelogai.feature.gigacodeae.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import changelogai.core.llm.debug.LLMTraceDialog
import changelogai.feature.gigacodeae.ChatSession
import changelogai.feature.gigacodeae.ChatViewModel
import changelogai.feature.gigacodeae.UiMessage
import changelogai.feature.gigacodeae.skill.SkillDefinition
import changelogai.feature.gigacodeae.skill.SkillState
import changelogai.feature.gigacodeae.skill.SkillsDialog
import changelogai.feature.spec.SpecCodeBridge
import java.awt.*
import javax.swing.*

class GigaCodeAETab(private val project: Project) {

    private val viewModel = ChatViewModel(project)
    private val chatPanel = ChatPanel(project)
    private val inputPanel = InputPanel(
        project = project,
        onSend = { text -> viewModel.send(text) },
        onStop = { viewModel.stop() },
        onModeChanged = { mode -> viewModel.orchestratorMode = mode }
    )

    // Скилл ассистента
    private val modeCombo = JComboBox<SkillDefinition>().apply {
        maximumSize = Dimension(JBUI.scale(180), JBUI.scale(26))
        preferredSize = Dimension(JBUI.scale(180), JBUI.scale(26))
        toolTipText = "Скилл ассистента (системный промпт)"
        addActionListener {
            val skill = selectedItem as? SkillDefinition ?: return@addActionListener
            viewModel.currentSkill = skill
        }
    }

    // Хедер
    private val sessionCombo = JComboBox<ChatSession>().apply {
        renderer = SessionComboRenderer()
        maximumSize = Dimension(JBUI.scale(220), JBUI.scale(26))
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(26))
        toolTipText = "История чатов"
    }
    private var ignoreComboEvents = false

    // Статус-строка под чатом
    private val statusLabel = JLabel(" ").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.ITALIC, 11f)
        border = JBUI.Borders.empty(2, 4)
    }

    val panel: JPanel = buildLayout()

    // Bridge listener reference (to remove it later if needed)
    private val specBridgeListener: (String) -> Unit = { prd ->
        SwingUtilities.invokeLater {
            // Switch to CODE_ASSISTANT skill
            val skills = SkillState.getInstance().allSkills()
            val codeSkill = skills.firstOrNull { it.id == "CODE_ASSISTANT" } ?: skills.firstOrNull()
            if (codeSkill != null) {
                val idx = (0 until modeCombo.itemCount).firstOrNull {
                    (modeCombo.getItemAt(it) as? SkillDefinition)?.id == codeSkill.id
                }
                if (idx != null) modeCombo.selectedIndex = idx
            }
            // Prefill input with PRD
            inputPanel.setPrefilledText(
                "Напиши код по следующему техническому заданию:\n\n$prd"
            )
        }
    }

    init {
        bindViewModel()
        refreshSkillCombo()
        refreshSessionCombo()
        SpecCodeBridge.addListener(specBridgeListener)
        chatPanel.onSaveToFile = { _ -> viewModel.send("сохрани в файл") }
        chatPanel.onCommandResult = { cmd, output ->
            val short = if (cmd.length > 60) cmd.take(57) + "…" else cmd
            chatPanel.addMessage(UiMessage(
                role = "assistant",
                content = "**▶ $short**\n```\n$output\n```"
            ))
        }
    }

    // ── ViewModel bindings ────────────────────────────────────────────────

    private fun bindViewModel() {
        viewModel.onMessageAdded = { msg ->
            if (msg.role == "assistant") chatPanel.addMessageStreaming(msg)
            else chatPanel.addMessage(msg)
        }
        viewModel.onToolCallCard = { msg -> chatPanel.addMessage(msg) }
        viewModel.onStatusUpdate = { text ->
            statusLabel.text = text.ifBlank { " " }
            statusLabel.icon = if (text.isNotBlank()) AllIcons.Actions.Execute else null
        }
        viewModel.onTypingChanged = { typing ->
            chatPanel.setTyping(typing)
            inputPanel.setLoading(typing)
            if (!typing) statusLabel.icon = null
        }
        viewModel.onError = { msg ->
            chatPanel.addMessage(UiMessage(role = "assistant", content = "**Ошибка:** $msg"))
            inputPanel.setLoading(false)
            statusLabel.text = " "
            statusLabel.icon = null
        }
        viewModel.onSessionsChanged = { refreshSessionCombo() }
        viewModel.onAgentLog = { event -> chatPanel.addLogEvent(event) }
        viewModel.onMcpStatusChanged = { text ->
            statusLabel.text = text
            statusLabel.icon = if (text.startsWith("MCP:")) AllIcons.Nodes.Plugin else null
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private fun buildLayout(): JPanel {
        val newChatBtn = iconButton(AllIcons.General.Add, "Новый чат") {
            viewModel.newSession()
            chatPanel.clearMessages()
        }
        val delChatBtn = iconButton(AllIcons.General.Remove, "Удалить чат") {
            viewModel.deleteSession(viewModel.activeSession.id)
            chatPanel.clearMessages()
        }
        val traceBtn = iconButton(AllIcons.Actions.Show, "LLM Call Inspector") {
            LLMTraceDialog(project).show()
        }
        val mcpBtn = iconButton(AllIcons.Actions.Refresh, "Переподключить MCP-серверы") {
            viewModel.reconnectMcp()
        }
        val editSkillBtn = iconButton(AllIcons.Actions.Edit, "Редактировать скиллы") {
            SkillsDialog(project) { updatedSkills ->
                refreshSkillCombo(updatedSkills)
            }.show()
        }

        sessionCombo.addActionListener {
            if (ignoreComboEvents) return@addActionListener
            val selected = sessionCombo.selectedItem as? ChatSession ?: return@addActionListener
            if (selected.id == viewModel.activeSession.id) return@addActionListener
            viewModel.loadSession(selected.id)
            chatPanel.clearMessages()
            selected.messages.forEach { msg -> chatPanel.addMessage(msg.toUiMessage()) }
        }

        val toolbar = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                add(newChatBtn); add(delChatBtn); add(sessionCombo); add(modeCombo); add(editSkillBtn)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(mcpBtn); add(traceBtn)
            }, BorderLayout.EAST)
        }

        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(statusLabel)
        }

        return JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(6)
            add(toolbar, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 4)).apply {
                add(chatPanel, BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, 2)).apply {
                    isOpaque = false
                    add(statusBar, BorderLayout.NORTH)
                    add(inputPanel, BorderLayout.CENTER)
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        }
    }

    // ── Skills ────────────────────────────────────────────────────────────

    private fun refreshSkillCombo(skills: List<SkillDefinition> = SkillState.getInstance().allSkills()) {
        val currentId = (modeCombo.selectedItem as? SkillDefinition)?.id
            ?: viewModel.currentSkill.id
        modeCombo.removeAllItems()
        skills.forEach { modeCombo.addItem(it) }
        val idx = skills.indexOfFirst { it.id == currentId }
        modeCombo.selectedIndex = if (idx >= 0) idx else 0
        viewModel.currentSkill = modeCombo.selectedItem as? SkillDefinition
            ?: skills.firstOrNull() ?: return
    }

    // ── Sessions ──────────────────────────────────────────────────────────

    private fun refreshSessionCombo() {
        ignoreComboEvents = true
        sessionCombo.removeAllItems()
        val sessions = viewModel.sessions
        sessions.forEach { sessionCombo.addItem(it) }
        val activeIdx = sessions.indexOfFirst { it.id == viewModel.activeSession.id }
        if (activeIdx >= 0) sessionCombo.selectedIndex = activeIdx
        ignoreComboEvents = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit) =
        JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            addActionListener { action() }
        }

    private class SessionComboRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, hasFocus)
            if (value is ChatSession) {
                text = value.title.take(30).let { if (value.title.length > 30) "$it…" else it }
                toolTipText = value.title
            }
            return this
        }
    }
}

private fun changelogai.core.llm.model.ChatMessage.toUiMessage() =
    UiMessage(
        role = role,
        content = content ?: functionCall?.let { "→ ${it.name}" } ?: "",
        functionCall = functionCall
    )
