package changelogai.feature.changelog.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import git4idea.GitCommit
import changelogai.core.llm.debug.LLMTraceDialog
import changelogai.feature.changelog.ChangelogViewModel
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

class ChangelogTab(private val project: Project) {

    private val viewModel = ChangelogViewModel(project)

    private val commitList = JBList<GitCommit>()
    private val selectionLabel = JLabel("0 / 0").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.PLAIN, font.size - 1f)
    }

    private val previewArea = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val loadingPanel = JBLoadingPanel(BorderLayout(), project).also {
        it.add(JBScrollPane(previewArea), BorderLayout.CENTER)
    }

    private val generateButton = JButton("Generate", AllIcons.Actions.Execute).apply {
        isEnabled = false
        horizontalTextPosition = SwingConstants.RIGHT
    }
    private val saveButton = iconButton(AllIcons.Actions.MenuSaveall, "Save to CHANGELOG.md").apply { isEnabled = false }
    private val copyButton = iconButton(AllIcons.Actions.Copy, "Copy to clipboard").apply { isEnabled = false }
    private val promptButton = iconButton(AllIcons.Actions.Edit, "Edit prompt")
    private val traceButton = iconButton(AllIcons.Actions.Show, "LLM Call Inspector")

    private val statusIcon = JLabel()
    private val statusLabel = JLabel(" ").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    val panel: JPanel = buildLayout()

    init {
        bindViewModel()
        viewModel.loadCommits()
    }

    private fun bindViewModel() {
        viewModel.onCommitsLoaded = { commits ->
            val model = DefaultListModel<GitCommit>().also { m -> commits.forEach(m::addElement) }
            commitList.model = model
            if (commits.isNotEmpty()) commitList.setSelectionInterval(0, minOf(9, commits.size - 1))
            generateButton.isEnabled = true
            updateSelectionLabel()
            setStatus("${commits.size} commits loaded", StatusType.OK)
        }
        viewModel.onCommitsError = { msg -> setStatus(msg, StatusType.ERROR) }
        viewModel.onGenerating = {
            generateButton.isEnabled = false; saveButton.isEnabled = false
            copyButton.isEnabled = false; previewArea.text = ""
            loadingPanel.startLoading()
            setStatus("Generating…", StatusType.LOADING)
        }
        viewModel.onGenerationResult = { result ->
            loadingPanel.stopLoading()
            previewArea.text = result; previewArea.caretPosition = 0
            generateButton.isEnabled = true; saveButton.isEnabled = true; copyButton.isEnabled = true
            setStatus("Done", StatusType.OK)
        }
        viewModel.onGenerationInfo = { message ->
            loadingPanel.stopLoading()
            previewArea.text = message; previewArea.caretPosition = 0
            generateButton.isEnabled = true; saveButton.isEnabled = false; copyButton.isEnabled = false
            setStatus(message, StatusType.INFO)
        }
        viewModel.onGenerationError = { msg ->
            loadingPanel.stopLoading()
            generateButton.isEnabled = true
            setStatus(msg, StatusType.ERROR)
        }
        viewModel.onSaved = { setStatus("Saved to CHANGELOG.md", StatusType.OK) }
    }

    private fun buildLayout(): JPanel {
        commitList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        commitList.cellRenderer = CommitCellRenderer()
        commitList.addListSelectionListener { updateSelectionLabel() }

        val refreshBtn = iconButton(AllIcons.Actions.Refresh, "Refresh") { viewModel.loadCommits() }
        val selectAllBtn = iconButton(AllIcons.Actions.Selectall, "Select all") {
            commitList.setSelectionInterval(0, commitList.model.size - 1)
        }
        val deselectAllBtn = iconButton(AllIcons.Actions.Unselectall, "Deselect all") {
            commitList.clearSelection()
        }

        generateButton.addActionListener { viewModel.generateChangelog(commitList.selectedValuesList) }
        saveButton.addActionListener { viewModel.saveToFile(previewArea.text) }
        promptButton.addActionListener { PromptDialog(project).show() }
        traceButton.addActionListener { LLMTraceDialog(project).show() }
        copyButton.addActionListener {
            if (previewArea.text.isNotBlank()) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(previewArea.text), null)
                setStatus("Copied to clipboard", StatusType.OK)
            }
        }

        val commitListPanel = JPanel(BorderLayout(0, 4)).apply {
            preferredSize = Dimension(0, JBUI.scale(200))
            add(JPanel(BorderLayout()).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                    add(JLabel("Commits")); add(selectionLabel)
                }, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2)).apply {
                    add(selectAllBtn); add(deselectAllBtn); add(refreshBtn)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JBScrollPane(commitList), BorderLayout.CENTER)
        }

        val previewPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(JLabel("Preview"))
            }, BorderLayout.NORTH)
            add(loadingPanel, BorderLayout.CENTER)
        }

        val bottomBar = JPanel(BorderLayout(0, 2)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(generateButton); add(saveButton); add(copyButton); add(promptButton); add(traceButton)
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(statusIcon); add(statusLabel)
            }, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(commitListPanel, BorderLayout.NORTH)
            add(previewPanel, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }
    }

    private fun updateSelectionLabel() {
        val total = commitList.model.size
        val selected = commitList.selectedIndices.size
        selectionLabel.text = "$selected / $total"
    }

    private enum class StatusType { OK, ERROR, INFO, LOADING }

    private fun setStatus(message: String, type: StatusType) {
        statusLabel.text = message
        statusIcon.icon = when (type) {
            StatusType.OK -> AllIcons.General.InspectionsOK
            StatusType.ERROR -> AllIcons.General.Error
            StatusType.INFO -> AllIcons.General.Information
            StatusType.LOADING -> null
        }
        statusLabel.foreground = when (type) {
            StatusType.ERROR -> JBUI.CurrentTheme.NotificationError.foregroundColor()
            else -> JBUI.CurrentTheme.Label.disabledForeground()
        }
    }

    private fun iconButton(icon: Icon, tooltip: String, action: (() -> Unit)? = null) =
        JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            action?.let { addActionListener { it() } }
        }

    private class CommitCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is GitCommit) {
                val escaped = value.subject.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                val author = value.author.name
                val fg = JBUI.CurrentTheme.Label.disabledForeground()
                val grayHex = "#%02x%02x%02x".format(fg.red, fg.green, fg.blue)
                text = "<html><b>${value.id.toShortString()}</b>&nbsp;$escaped&nbsp;<font color='$grayHex'>— $author</font></html>"
                toolTipText = value.subject
            }
            return this
        }
    }
}
