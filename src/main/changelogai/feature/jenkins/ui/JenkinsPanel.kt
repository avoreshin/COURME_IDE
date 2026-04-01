package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.engine.JenkinsAnalyzer
import changelogai.feature.jenkins.engine.JenkinsDemoData
import changelogai.feature.jenkins.engine.JenkinsMcpFetcher
import changelogai.feature.jenkins.engine.JenkinsMcpNotConfiguredException
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsPanel(private val project: Project) {

    private val fetcher = JenkinsMcpFetcher(project)
    private val analyzer = JenkinsAnalyzer(project)

    private val isDemoMode = JCheckBox("Demo").apply {
        isOpaque = false
        toolTipText = "Использовать демо-данные вместо MCP"
        isSelected = !fetcher.isConfigured()
    }
    private val refreshBtn = JButton("Обновить", AllIcons.Actions.Refresh)
    private val statusLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.ITALIC, JBUI.scale(11))
        foreground = JBColor.GRAY
    }
    private val mcpSetupBtn = JButton("Настроить Jenkins MCP", AllIcons.General.Settings).apply {
        isVisible = !fetcher.isConfigured()
    }

    private val pipelineList = JenkinsPipelineList { pipeline -> onPipelineSelected(pipeline) }
    private val buildLog = JenkinsBuildLog(
        onAnalyzeClicked = { log -> startAnalysis(log) },
        onTriggerBuildClicked = { name -> triggerBuild(name) }
    )
    private val analysisPanel = JenkinsAnalysisPanel()

    val panel: JPanel = buildLayout()

    private fun buildLayout(): JPanel {
        val root = JPanel(BorderLayout(0, JBUI.scale(6)))
        root.border = JBUI.Borders.empty(8)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(refreshBtn)
            add(isDemoMode)
            add(mcpSetupBtn)
        }
        val topPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(toolbar, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }

        val rightPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(buildLog.component, BorderLayout.CENTER)
            add(analysisPanel.component, BorderLayout.SOUTH)
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pipelineList.component, rightPanel).apply {
            dividerLocation = JBUI.scale(200)
            isContinuousLayout = true
            border = null
        }

        root.add(topPanel, BorderLayout.NORTH)
        root.add(splitPane, BorderLayout.CENTER)

        refreshBtn.addActionListener { loadPipelines() }
        mcpSetupBtn.addActionListener { showMcpSetupHint() }
        isDemoMode.addActionListener { loadPipelines() }

        loadPipelines()
        return root
    }

    private fun loadPipelines() {
        refreshBtn.isEnabled = false
        setStatus("⏳ Загружаю пайплайны...", JBColor.GRAY)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pipelines = if (isDemoMode.isSelected) {
                    JenkinsDemoData.pipelines
                } else {
                    fetcher.getPipelines()
                }
                SwingUtilities.invokeLater {
                    pipelineList.setPipelines(pipelines)
                    setStatus("✓ ${pipelines.size} пайплайнов", JBColor(Color(61, 214, 140), Color(61, 214, 140)))
                    refreshBtn.isEnabled = true
                    if (pipelines.isNotEmpty()) onPipelineSelected(pipelines.first())
                }
            } catch (e: JenkinsMcpNotConfiguredException) {
                SwingUtilities.invokeLater {
                    isDemoMode.isSelected = true
                    mcpSetupBtn.isVisible = true
                    setStatus("⚠ Jenkins MCP не настроен — включён Demo режим", JBColor.ORANGE)
                    refreshBtn.isEnabled = true
                    loadPipelines()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("✗ ${e.message?.take(80)}", JBColor.RED)
                    refreshBtn.isEnabled = true
                }
            }
        }
    }

    private fun onPipelineSelected(pipeline: JenkinsPipeline) {
        buildLog.setPipeline(pipeline)
        if (!isDemoMode.isSelected && pipeline.lastBuild?.log.isNullOrBlank() && pipeline.lastBuild != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val log = fetcher.getBuildLog(pipeline.name, pipeline.lastBuild.number)
                    SwingUtilities.invokeLater { buildLog.setLog(log) }
                } catch (_: Exception) { }
            }
        }
    }

    private fun startAnalysis(log: String) {
        buildLog.setAnalyzing(true)
        analysisPanel.setLoading(true)

        if (isDemoMode.isSelected) {
            SwingUtilities.invokeLater {
                analysisPanel.setAnalysis(JenkinsDemoData.demoAnalysis, log)
                buildLog.setAnalyzing(false)
            }
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val analysis = analyzer.analyze(log)
                SwingUtilities.invokeLater {
                    analysisPanel.setAnalysis(analysis, log)
                    buildLog.setAnalyzing(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("✗ Ошибка анализа: ${e.message?.take(60)}", JBColor.RED)
                    buildLog.setAnalyzing(false)
                    analysisPanel.setLoading(false)
                }
            }
        }
    }

    private fun triggerBuild(pipelineName: String) {
        if (isDemoMode.isSelected) {
            setStatus("Demo: запуск сборки $pipelineName (имитация)", JBColor.GRAY)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                fetcher.triggerBuild(pipelineName)
                SwingUtilities.invokeLater {
                    setStatus("✓ Сборка $pipelineName запущена", JBColor(Color(61, 214, 140), Color(61, 214, 140)))
                    loadPipelines()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { setStatus("✗ ${e.message?.take(80)}", JBColor.RED) }
            }
        }
    }

    private fun showMcpSetupHint() {
        JOptionPane.showMessageDialog(
            panel,
            "Откройте GIGACOURME → MCP и добавьте Jenkins-сервер:\n• Тип: HTTP или STDIO\n• Preset: jenkins\n• Токен Jenkins (API token)",
            "Настройка Jenkins MCP",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun setStatus(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }
}
