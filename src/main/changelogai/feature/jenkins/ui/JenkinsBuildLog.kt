package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsBuildLog(
    private val onAnalyzeClicked: (log: String) -> Unit,
    private val onTriggerBuildClicked: (pipelineName: String) -> Unit
) {

    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
        background = JBColor(Color(13, 17, 23), Color(13, 17, 23))
        foreground = JBColor(Color(201, 209, 217), Color(201, 209, 217))
        border = JBUI.Borders.empty(8)
    }
    private val analyzeBtn = JButton("Анализировать", AllIcons.Actions.Find).apply {
        isVisible = false
    }
    private val triggerBtn = JButton("Запустить сборку", AllIcons.Actions.Execute).apply {
        isVisible = false
    }
    private var currentPipeline: JenkinsPipeline? = null

    val component: JPanel = buildLayout()

    private fun buildLayout(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.isOpaque = false

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        toolbar.isOpaque = false
        toolbar.add(triggerBtn)
        toolbar.add(analyzeBtn)

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(logArea), BorderLayout.CENTER)

        analyzeBtn.addActionListener {
            val log = logArea.text
            if (log.isNotBlank()) onAnalyzeClicked(log)
        }
        triggerBtn.addActionListener {
            currentPipeline?.name?.let { onTriggerBuildClicked(it) }
        }

        return panel
    }

    fun setPipeline(pipeline: JenkinsPipeline) {
        currentPipeline = pipeline
        val build = pipeline.lastBuild
        if (build != null) {
            val header = "Сборка #${build.number} — ${build.status}\nДлительность: ${build.durationMs / 1000}с\n\n"
            logArea.text = header + build.log.ifBlank { "Лог пуст" }
            logArea.caretPosition = 0
            analyzeBtn.isVisible = build.status == BuildStatus.FAILURE
            triggerBtn.isVisible = true
        } else {
            logArea.text = "Нет данных о сборках"
            analyzeBtn.isVisible = false
            triggerBtn.isVisible = true
        }
    }

    fun setLog(log: String) {
        logArea.text = log
        logArea.caretPosition = 0
    }

    fun setAnalyzing(analyzing: Boolean) {
        analyzeBtn.isEnabled = !analyzing
        analyzeBtn.text = if (analyzing) "Анализирую..." else "Анализировать"
    }
}
