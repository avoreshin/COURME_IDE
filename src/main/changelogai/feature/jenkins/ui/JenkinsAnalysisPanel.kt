package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.JenkinsChatBridge
import changelogai.feature.jenkins.model.JenkinsAnalysis
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsAnalysisPanel {

    private val rootCauseArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(Color(22, 27, 34), Color(22, 27, 34))
        foreground = JBColor(Color(248, 166, 79), Color(248, 166, 79))
        border = JBUI.Borders.empty(6)
        font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
    }
    private val suggestionsArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(Color(22, 27, 34), Color(22, 27, 34))
        foreground = JBColor(Color(201, 209, 217), Color(201, 209, 217))
        border = JBUI.Borders.empty(6)
        font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
    }
    private val continueBtn = JButton("Продолжить в GigaCodeAE", AllIcons.Actions.Forward).apply {
        isVisible = false
    }
    private val spinner = JLabel("Анализирую...", AllIcons.Process.Step_1, SwingConstants.LEFT)
    private val contentWrapper = JPanel(CardLayout())
    private var lastAnalysis: JenkinsAnalysis? = null
    private var lastLog: String = ""

    val component: JPanel = buildLayout()

    private fun buildLayout(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4)

        val header = JLabel("AI-анализ", AllIcons.Actions.Find, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        }

        val causePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Причина:").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
            add(rootCauseArea, BorderLayout.CENTER)
        }
        val suggestPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Рекомендации:").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
            add(suggestionsArea, BorderLayout.CENTER)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(causePanel)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(suggestPanel)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(continueBtn)
        }

        val spinnerPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            isOpaque = false
            add(spinner)
        }

        contentWrapper.isOpaque = false
        contentWrapper.add(spinnerPanel, "loading")
        contentWrapper.add(content, "content")
        (contentWrapper.layout as CardLayout).show(contentWrapper, "content")

        continueBtn.addActionListener {
            val analysis = lastAnalysis ?: return@addActionListener
            JenkinsChatBridge.sendToChat(buildChatPrompt(analysis, lastLog))
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(contentWrapper, BorderLayout.CENTER)
        return panel
    }

    fun setLoading(loading: Boolean) {
        val layout = contentWrapper.layout as CardLayout
        if (loading) {
            layout.show(contentWrapper, "loading")
        } else {
            layout.show(contentWrapper, "content")
            continueBtn.isVisible = lastAnalysis != null
        }
    }

    fun setAnalysis(analysis: JenkinsAnalysis, originalLog: String) {
        lastAnalysis = analysis
        lastLog = originalLog
        rootCauseArea.text = analysis.rootCause
        val suggestionsText = analysis.suggestions.joinToString("\n") { "• $it" }
        val filesText = if (analysis.relatedFiles.isNotEmpty())
            "\nСвязанные файлы:\n" + analysis.relatedFiles.joinToString("\n") { "  - $it" }
        else ""
        suggestionsArea.text = suggestionsText + filesText
        continueBtn.isVisible = true
        setLoading(false)
    }

    private fun buildChatPrompt(analysis: JenkinsAnalysis, log: String): String {
        val truncatedLog = if (log.length > 1000) "...\n" + log.takeLast(1000) else log
        return """
Продолжи анализ упавшей Jenkins-сборки.

**Причина:** ${analysis.rootCause}

**Связанные файлы:** ${analysis.relatedFiles.joinToString(", ").ifBlank { "не определены" }}

**Рекомендации:**
${analysis.suggestions.joinToString("\n") { "- $it" }}

**Лог сборки:**
```
$truncatedLog
```

Помоги исправить проблему.
        """.trimIndent()
    }
}
