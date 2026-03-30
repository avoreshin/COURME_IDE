package changelogai.feature.sprint.ui

import changelogai.feature.sprint.engine.JiraMcpNotConfiguredException
import changelogai.feature.sprint.engine.SprintAnalyzer
import changelogai.feature.sprint.model.JiraStory
import changelogai.feature.sprint.model.SprintAnalysis
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class SprintPanel(private val project: Project) {

    private val analyzer = SprintAnalyzer(project)
    private var bridge: SprintWebBridge? = null

    private val boardInput = JBTextField().apply {
        emptyText.text = "Проект или URL (напр. SCRUM)"
        toolTipText = "Введите ключ Jira-проекта (SCRUM) или URL борда"
    }
    private val loadBtn = JButton("Загрузить спринт").apply {
        icon = AllIcons.Actions.Refresh
    }
    private val statusLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.ITALIC, JBUI.scale(11))
        foreground = JBColor.GRAY
    }
    private val mcpSetupBtn = JButton("Настроить Jira MCP").apply {
        icon = AllIcons.General.Settings
        isVisible = false
    }

    val panel: JPanel = build()

    private fun build(): JPanel {
        val root = JPanel(BorderLayout(0, 8))
        root.background = JBColor(Color(8, 9, 11), Color(8, 9, 11))
        root.border = JBUI.Borders.empty(8)

        // Toolbar
        val toolbar = JPanel(BorderLayout(JBUI.scale(6), 0))
        toolbar.isOpaque = false
        toolbar.add(boardInput, BorderLayout.CENTER)
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        btnRow.isOpaque = false
        btnRow.add(loadBtn)
        btnRow.add(mcpSetupBtn)
        toolbar.add(btnRow, BorderLayout.EAST)

        val topPanel = JPanel(BorderLayout(0, JBUI.scale(4)))
        topPanel.isOpaque = false
        topPanel.add(toolbar, BorderLayout.CENTER)
        topPanel.add(statusLabel, BorderLayout.SOUTH)

        root.add(topPanel, BorderLayout.NORTH)

        // Main content area
        val contentArea: JComponent = if (JBCefApp.isSupported()) {
            val jcefBrowser = JBCefBrowser()
            val b = SprintWebBridge(jcefBrowser, analyzer)
            bridge = b
            b.onRefreshRequested = { loadSprint() }
            b.injectDashboard()
            jcefBrowser.component
        } else {
            buildFallbackPanel()
        }
        root.add(contentArea, BorderLayout.CENTER)

        // Wire actions after build
        loadBtn.addActionListener { loadSprint() }
        mcpSetupBtn.addActionListener { openMcpSettings() }

        if (!analyzer.isConfigured()) {
            mcpSetupBtn.isVisible = true
            setStatus("⚠ Добавьте Jira MCP-сервер с preset='jira' в MCP Settings", JBColor.ORANGE)
        }

        return root
    }

    private fun loadSprint() {
        val input = boardInput.text.trim()
        if (input.isEmpty()) {
            setStatus("Введите URL борда или board ID", JBColor.RED)
            return
        }

        loadBtn.isEnabled = false
        setStatus("⏳ Загружаю спринт...", JBColor.GRAY)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val (sprint, stories) = analyzer.loadSprint(input)
                SwingUtilities.invokeLater {
                    setStatus("🔄 Анализирую ${stories.size} историй...", JBColor.GRAY)
                }
                val analysis = analyzer.analyze(sprint, stories)
                SwingUtilities.invokeLater {
                    deliverAnalysis(analysis, stories)
                    setStatus("✓ ${sprint.name} · ${stories.size} историй", JBColor(Color(61, 214, 140), Color(61, 214, 140)))
                    loadBtn.isEnabled = true
                }
            } catch (e: JiraMcpNotConfiguredException) {
                SwingUtilities.invokeLater {
                    mcpSetupBtn.isVisible = true
                    setStatus("⚠ Jira MCP не настроен", JBColor.ORANGE)
                    loadBtn.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("✗ ${e.message?.take(80)}", JBColor.RED)
                    loadBtn.isEnabled = true
                }
            }
        }
    }

    private fun deliverAnalysis(analysis: SprintAnalysis, stories: List<JiraStory>) {
        val b = bridge ?: return
        b.setStories(stories)
        b.sendAnalysis(analysis)
    }

    private fun openMcpSettings() {
        // Navigate user to MCP settings — reuse existing McpPanel if available
        // For now show a hint dialog
        JOptionPane.showMessageDialog(
            panel,
            "Откройте GIGACOURME → MCP и добавьте Jira-сервер:\n" +
            "• Тип: HTTP или STDIO\n" +
            "• Preset: jira\n" +
            "• Токен Jira (API token или PAT)",
            "Настройка Jira MCP",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun buildFallbackPanel(): JPanel {
        val p = JPanel(GridBagLayout())
        p.isOpaque = false
        val lbl = JBLabel("<html><center style='color:#6B7280'>" +
                "JCEF не поддерживается в этой версии IDE.<br>" +
                "Sprint Analyzer требует IntelliJ 2024.1+</center></html>")
        p.add(lbl)
        return p
    }

    private fun setStatus(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }
}
