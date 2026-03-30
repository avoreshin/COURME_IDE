package changelogai.core.llm.debug

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class LLMTraceDialog(project: Project) : DialogWrapper(project, false) {

    private val traceList = JBList<LLMCallTrace>()
    private val requestArea = jsonArea()
    private val responseArea = jsonArea()

    init {
        title = "LLM Call Inspector"
        isModal = false
        setOKButtonText("Close")
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        traceList.cellRenderer = TraceCellRenderer()
        traceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        traceList.addListSelectionListener {
            traceList.selectedValue?.let { trace ->
                requestArea.text = trace.requestJson
                requestArea.caretPosition = 0
                responseArea.text = if (trace.error != null) "ERROR: ${trace.error}" else trace.responseJson
                responseArea.caretPosition = 0
            }
        }

        val listPanel = JPanel(BorderLayout(0, 4)).apply {
            preferredSize = Dimension(JBUI.scale(220), 0)
            add(JPanel(BorderLayout()).apply {
                add(JLabel("Calls").apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.WEST)
                add(JButton(AllIcons.Actions.GC).apply {
                    toolTipText = "Clear"
                    preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                    isBorderPainted = false; isContentAreaFilled = false
                    addActionListener { LLMTraceStore.clear(); refresh() }
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(traceList), BorderLayout.CENTER)
        }

        val requestPanel = labeledArea("Request", requestArea)
        val responsePanel = labeledArea("Response", responseArea)

        val jsonSplitter = JBSplitter(true, 0.45f).apply {
            firstComponent = requestPanel
            secondComponent = responsePanel
        }

        val mainSplitter = JBSplitter(false, 0.25f).apply {
            firstComponent = listPanel
            secondComponent = jsonSplitter
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(600))
            border = JBUI.Borders.empty(8)
            add(mainSplitter, BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(okAction)

    private fun refresh() {
        val traces = LLMTraceStore.getAll().reversed()
        val model = DefaultListModel<LLMCallTrace>().also { m -> traces.forEach(m::addElement) }
        traceList.model = model
        if (model.size > 0) traceList.selectedIndex = 0
    }

    private fun jsonArea() = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
    }

    private fun labeledArea(title: String, area: JBTextArea) = JPanel(BorderLayout(0, 4)).apply {
        add(JLabel(title).apply { border = JBUI.Borders.emptyBottom(2) }, BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
    }

    private class TraceCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is LLMCallTrace) {
                val icon = if (value.isSuccess) AllIcons.General.InspectionsOK else AllIcons.General.Error
                val status = if (value.isSuccess) "${value.statusCode}" else value.error?.take(20) ?: "ERR"
                val dur = if (value.durationMs > 0) " ${value.durationMs}ms" else ""
                text = "<html><b>${value.label}</b><br><font color='gray'>$status$dur</font></html>"
                setIcon(icon)
                if (!isSelected) foreground = if (value.isSuccess) JBColor.foreground() else JBColor.RED
            }
            return this
        }
    }
}
