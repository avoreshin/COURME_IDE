package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsPipelineList(
    private val onPipelineSelected: (JenkinsPipeline) -> Unit
) {

    private val listModel = DefaultListModel<JenkinsPipeline>()
    private val list = JBList(listModel).apply {
        cellRenderer = PipelineCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    val component: JScrollPane = JScrollPane(list).apply {
        border = JBUI.Borders.empty()
    }

    init {
        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                list.selectedValue?.let { onPipelineSelected(it) }
            }
        }
    }

    fun setPipelines(pipelines: List<JenkinsPipeline>) {
        listModel.clear()
        pipelines.forEach { listModel.addElement(it) }
    }

    private inner class PipelineCellRenderer : ListCellRenderer<JenkinsPipeline> {
        override fun getListCellRendererComponent(
            list: JList<out JenkinsPipeline>, value: JenkinsPipeline, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(JBUI.scale(6), 0))
            panel.border = JBUI.Borders.empty(4, 8)
            panel.isOpaque = true
            panel.background = if (isSelected) list.selectionBackground else list.background

            val statusIcon = JLabel(statusIcon(value.status))
            val nameLabel = JLabel(value.name).apply {
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
            val buildLabel = JLabel(value.lastBuild?.let { "#${it.number}" } ?: "—").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            }

            panel.add(statusIcon, BorderLayout.WEST)
            panel.add(nameLabel, BorderLayout.CENTER)
            panel.add(buildLabel, BorderLayout.EAST)
            return panel
        }

        private fun statusIcon(status: BuildStatus): Icon = when (status) {
            BuildStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
            BuildStatus.FAILURE -> AllIcons.RunConfigurations.TestFailed
            BuildStatus.UNSTABLE -> AllIcons.RunConfigurations.TestIgnored
            BuildStatus.IN_PROGRESS -> AllIcons.Process.Step_1
            BuildStatus.ABORTED -> AllIcons.RunConfigurations.TestIgnored
            BuildStatus.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
        }
    }
}
