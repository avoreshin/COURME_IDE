package changelogai.feature.spec.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder

/**
 * Панель «Следующие шаги» — появляется внизу AssessmentPanel после завершения оценки.
 */
class AssessmentActionsPanel(
    private val onSaveMd:     () -> Unit,
    private val onRefine:     () -> Unit,
    private val onEdit:       () -> Unit,
    private val onConfluence: () -> Unit,
    private val onJira:       () -> Unit
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = CompoundBorder(
            MatteBorder(1, 0, 0, 0, JBColor.border()),
            EmptyBorder(JBUI.scale(8), JBUI.scale(12), JBUI.scale(8), JBUI.scale(12))
        )

        val label = JLabel("Следующие шаги:").apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(12))
            border = EmptyBorder(0, 0, 0, JBUI.scale(8))
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(actionBtn("Сохранить MD", AllIcons.Actions.Download, onSaveMd))
            add(actionBtn("Доработать", AllIcons.Actions.Execute, onRefine))
            add(actionBtn("Редактировать", AllIcons.Actions.Edit, onEdit))
            add(actionBtn("→ Confluence", AllIcons.Vcs.Push, onConfluence))
            add(actionBtn("→ Jira задачи", AllIcons.Toolwindows.ToolWindowTodo, onJira))
        }

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(label, BorderLayout.WEST)
            add(btnPanel, BorderLayout.CENTER)
        }
        add(row, BorderLayout.CENTER)
    }

    private fun actionBtn(text: String, icon: Icon, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            font = font.deriveFont(Font.PLAIN).deriveFont(JBUI.scale(12).toFloat())
            horizontalTextPosition = SwingConstants.RIGHT
            addActionListener { action() }
        }
}
