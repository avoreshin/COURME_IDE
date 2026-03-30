package changelogai.feature.gigacodeae.skill

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.util.UUID
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Диалог управления скиллами (системными промптами).
 * Мастер-детальный интерфейс: слева список, справа редактор.
 */
class SkillsDialog(
    project: Project,
    private val onSkillsSaved: (List<SkillDefinition>) -> Unit
) : DialogWrapper(project, true) {

    private val state = SkillState.getInstance()
    private val skills = state.allSkills().toMutableList()

    private val listModel = DefaultListModel<SkillDefinition>()
    private val skillList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SkillListRenderer()
    }

    private val nameField = JTextField()
    private val promptArea = JBTextArea().apply {
        lineWrap = true; wrapStyleWord = true
        rows = 14
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
    }

    private val saveSkillBtn = JButton("Сохранить изменения").apply {
        isEnabled = false
    }
    private val deleteBtn   = JButton(AllIcons.General.Remove).apply {
        toolTipText = "Удалить скилл"; isEnabled = false
        isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
    }

    private var currentSkillId: String? = null
    private var isDirty = false

    init {
        title = "Управление скиллами"
        isModal = true
        init()
        refreshList()
        if (listModel.size() > 0) skillList.selectedIndex = 0
    }

    override fun createCenterPanel(): JComponent {
        // ── Левая панель: список + кнопки управления ──────────────────────
        val addBtn = JButton(AllIcons.General.Add).apply {
            toolTipText = "Новый скилл"
            isBorderPainted = false; isContentAreaFilled = false
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        }
        val resetBtn = JButton(AllIcons.Actions.Rollback).apply {
            toolTipText = "Восстановить встроенные скиллы"
            isBorderPainted = false; isContentAreaFilled = false
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        }

        val listToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            add(addBtn); add(deleteBtn); add(resetBtn)
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(200), JBUI.scale(400))
            minimumSize  = Dimension(JBUI.scale(180), JBUI.scale(300))
            add(listToolbar, BorderLayout.NORTH)
            add(JBScrollPane(skillList), BorderLayout.CENTER)
        }

        // ── Правая панель: редактор ────────────────────────────────────────
        val nameLabel   = JLabel("Название:")
        val promptLabel = JLabel("Системный промпт:")

        val rightPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(0, 8, 0, 0)
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(400))
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            }

            // Строка названия
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            add(nameLabel, gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            add(nameField, gbc)

            // Заголовок промпта
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            add(promptLabel, gbc)

            // Текстовая область промпта
            gbc.gridy = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
            add(JBScrollPane(promptArea).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
            }, gbc)

            // Кнопка сохранить
            gbc.gridy = 3; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.NONE
            gbc.anchor = GridBagConstraints.EAST; gbc.gridwidth = 2
            add(saveSkillBtn, gbc)
        }

        // ── Hint label ────────────────────────────────────────────────────
        val hintLabel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(JLabel(AllIcons.Nodes.Locked))
            add(JLabel("Встроенные скиллы можно изменять, но нельзя удалять").apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
        }

        // ── Общий layout ──────────────────────────────────────────────────
        val content = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(8)
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.CENTER)
            add(hintLabel, BorderLayout.SOUTH)
        }

        // ── Listeners ─────────────────────────────────────────────────────
        skillList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val skill = skillList.selectedValue ?: return@addListSelectionListener
            loadSkillToEditor(skill)
        }

        addBtn.addActionListener { createNewSkill() }

        deleteBtn.addActionListener {
            val skill = skillList.selectedValue ?: return@addActionListener
            if (skill.isBuiltIn) {
                Messages.showInfoMessage(content, "Встроенные скиллы нельзя удалять.", "Удаление")
                return@addActionListener
            }
            val idx = skillList.selectedIndex
            skills.removeIf { it.id == skill.id }
            refreshList()
            skillList.selectedIndex = idx.coerceAtMost(listModel.size() - 1)
        }

        resetBtn.addActionListener {
            val confirm = Messages.showYesNoDialog(
                content, "Восстановить все встроенные скиллы до значений по умолчанию?",
                "Восстановить", Messages.getQuestionIcon()
            )
            if (confirm == Messages.YES) {
                val defaults = SkillDefinition.defaults()
                defaults.forEach { default ->
                    val idx = skills.indexOfFirst { it.id == default.id }
                    if (idx >= 0) skills[idx] = default else skills.add(0, default)
                }
                refreshList()
                loadSkillToEditor(skillList.selectedValue ?: return@addActionListener)
            }
        }

        saveSkillBtn.addActionListener { saveCurrentSkill() }

        val markDirty = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { isDirty = true; saveSkillBtn.isEnabled = true }
            override fun removeUpdate(e: DocumentEvent?) { isDirty = true; saveSkillBtn.isEnabled = true }
            override fun changedUpdate(e: DocumentEvent?) {}
        }
        nameField.document.addDocumentListener(markDirty)
        promptArea.document.addDocumentListener(markDirty)

        return content
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun refreshList() {
        val selectedId = skillList.selectedValue?.id
        listModel.clear()
        skills.forEach { listModel.addElement(it) }
        val idx = skills.indexOfFirst { it.id == selectedId }
        if (idx >= 0) skillList.selectedIndex = idx
    }

    private fun loadSkillToEditor(skill: SkillDefinition) {
        currentSkillId = skill.id
        nameField.text = skill.name
        promptArea.text = skill.systemPrompt
        promptArea.caretPosition = 0
        isDirty = false
        saveSkillBtn.isEnabled = false
        deleteBtn.isEnabled = !skill.isBuiltIn
    }

    private fun saveCurrentSkill() {
        val id = currentSkillId ?: return
        val name = nameField.text.trim().ifEmpty { "Без названия" }
        val prompt = promptArea.text.trim()
        val existing = skills.firstOrNull { it.id == id }
        val updated = (existing ?: SkillDefinition(id = id)).copy(name = name, systemPrompt = prompt)
        val idx = skills.indexOfFirst { it.id == id }
        if (idx >= 0) skills[idx] = updated else skills.add(updated)
        refreshList()
        isDirty = false
        saveSkillBtn.isEnabled = false
    }

    private fun createNewSkill() {
        val newSkill = SkillDefinition(
            id = UUID.randomUUID().toString(),
            name = "Новый скилл",
            systemPrompt = "Ты ассистент. Отвечай на русском языке.",
            isBuiltIn = false
        )
        skills.add(newSkill)
        refreshList()
        skillList.selectedIndex = listModel.size() - 1
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    override fun doOKAction() {
        if (isDirty) saveCurrentSkill()
        state.saveSkills(skills)
        onSkillsSaved(skills)
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent() = skillList

    // ── Renderer ──────────────────────────────────────────────────────────

    private class SkillListRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, hasFocus)
            val skill = value as? SkillDefinition ?: return this
            text = skill.name
            icon = if (skill.isBuiltIn) AllIcons.Nodes.Plugin else AllIcons.Actions.Edit
            border = JBUI.Borders.empty(3, 6)
            return this
        }
    }
}
