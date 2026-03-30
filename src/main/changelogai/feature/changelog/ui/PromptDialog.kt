package changelogai.feature.changelog.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import changelogai.core.settings.PluginState
import changelogai.core.settings.model.CommitLanguage
import changelogai.feature.changelog.PromptLoader
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class PromptDialog(private val project: Project) : DialogWrapper(project, true) {

    private val promptFile: File = resolvePromptFile()
    private val textArea = JBTextArea().apply {
        lineWrap = true; wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    init {
        title = "Edit Prompt"
        setOKButtonText("Save")
        init()
        loadPrompt()
    }

    override fun createCenterPanel(): JComponent {
        val pathLabel = JLabel(promptFile.absolutePath).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            font = font.deriveFont(Font.PLAIN, font.size - 1f)
            border = JBUI.Borders.emptyBottom(6)
        }
        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(700, 400)
        }
        return JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(pathLabel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    override fun createLeftSideActions() = arrayOf(
        object : javax.swing.AbstractAction("Reset to default") {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                promptFile.delete()
                loadPrompt()
            }
        },
        object : javax.swing.AbstractAction("Open in editor") {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                savePrompt()
                val vf = LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(promptFile.absolutePath) ?: return
                FileEditorManager.getInstance(project).openFile(vf, true)
                close(OK_EXIT_CODE)
            }
        }
    )

    override fun doOKAction() {
        savePrompt()
        super.doOKAction()
    }

    private fun loadPrompt() {
        textArea.text = PromptLoader.load(project, promptFilename())
        textArea.caretPosition = 0
    }

    private fun savePrompt() {
        promptFile.parentFile.mkdirs()
        promptFile.writeText(textArea.text)
    }

    private fun resolvePromptFile(): File {
        val basePath = project.basePath ?: error("No project basePath")
        return File(basePath, ".changelogai/${promptFilename()}")
    }

    private fun promptFilename() = when (PluginState.getInstance().commitLanguage) {
        CommitLanguage.Russian -> "prompt_ru.md"
        CommitLanguage.English -> "prompt_en.md"
    }
}
