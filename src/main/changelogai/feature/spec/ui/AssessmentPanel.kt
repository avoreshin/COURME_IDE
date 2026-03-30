package changelogai.feature.spec.ui

import changelogai.feature.spec.confluence.AssessmentMarkdownExporter
import changelogai.feature.spec.confluence.AssessmentReport
import changelogai.feature.spec.confluence.Complexity
import changelogai.feature.spec.confluence.EffortEstimate
import changelogai.feature.spec.confluence.IssueSeverity
import changelogai.feature.spec.confluence.QualityIssue
import changelogai.feature.spec.model.SpecDocument
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.DefaultTableModel

/**
 * Панель отображения результатов оценки ТЗ из Confluence.
 * Показывает: score, структурные ошибки, проблемы качества,
 * сгенерированные секции и оценку трудозатрат.
 */
class AssessmentPanel : JPanel(BorderLayout()) {

    private val scoreCircle  = ScoreCircle()
    private val structurePanel = StructureResultsPanel()
    private val qualityPanel   = QualityIssuesPanel()
    private val generatedPanel = GeneratedSectionsPanel()
    private val effortPanel    = EffortTablePanel()
    private val emptyLabel     = JBLabel("Вставьте URL Confluence и выберите режим «Валидация»", JBLabel.CENTER).apply {
        foreground = JBColor.GRAY
        font = Font(Font.SANS_SERIF, Font.ITALIC, JBUI.scale(13))
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    // Callback для кнопки «Доработать» — переключает в Генерацию спеки
    var onRefineRequested: ((prefillTask: String, contextSummary: String) -> Unit)? = null
    // Callback для кнопки «→ Jira» — создаёт задачи через MCP
    var onCreateJiraRequested: ((report: AssessmentReport) -> Unit)? = null

    private var currentReport: AssessmentReport? = null
    private val actionsPanel = AssessmentActionsPanel(
        onSaveMd       = { saveReportAsMd() },
        onRefine       = { refineWithSpec() },
        onEdit         = { toggleEditMode() },
        onConfluence   = { uploadToConfluence() },
        onJira         = { onCreateJiraRequested?.invoke(currentReport ?: return@AssessmentActionsPanel) }
    )

    init {
        isOpaque = false
        add(emptyLabel, BorderLayout.CENTER)
    }

    fun showReport(report: AssessmentReport) {
        currentReport = report
        removeAll()
        contentPanel.removeAll()

        // Header: score + title
        val header = buildHeader(report)
        contentPanel.add(header)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Structure
        if (report.structureErrors.isNotEmpty() || report.structureWarnings.isNotEmpty()) {
            structurePanel.update(report.structureErrors, report.structureWarnings)
            contentPanel.add(structurePanel)
            contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Quality issues
        if (report.qualityIssues.isNotEmpty()) {
            qualityPanel.update(report.qualityIssues)
            contentPanel.add(qualityPanel)
            contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Generated sections
        if (report.hasGeneratedContent) {
            generatedPanel.update(report.generatedSections)
            contentPanel.add(generatedPanel)
            contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Effort estimates
        if (report.effortEstimates.isNotEmpty()) {
            effortPanel.update(report.effortEstimates, report.totalStoryPoints)
            contentPanel.add(effortPanel)
        }

        // Suggestions
        if (report.suggestions.isNotEmpty()) {
            contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            contentPanel.add(buildSuggestionsPanel(report.suggestions))
        }

        contentPanel.add(Box.createVerticalGlue())

        val scroll = JBScrollPane(contentPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBar.unitIncrement = 16
        }

        add(scroll, BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.SOUTH)

        revalidate(); repaint()
    }

    fun clear() {
        currentReport = null
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate(); repaint()
    }

    // ── Next Steps actions ──────────────────────────────────────────────────

    private fun saveReportAsMd() {
        val report = currentReport ?: return
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Сохранить отчёт оценки"
            selectedFile = java.io.File("assessment-${report.pageTitle.replace("[^\\w]".toRegex(), "_").take(30)}.md")
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Markdown (*.md)", "md")
        }
        if (chooser.showSaveDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile.let { if (it.extension.isEmpty()) java.io.File("${it.path}.md") else it }
            try {
                file.writeText(AssessmentMarkdownExporter.toMarkdown(report))
                JOptionPane.showMessageDialog(this, "Сохранено: ${file.name}", "Готово", JOptionPane.INFORMATION_MESSAGE)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this, "Ошибка сохранения: ${e.message}", "Ошибка", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun refineWithSpec() {
        val report = currentReport ?: return
        val summary = buildString {
            appendLine("Контекст из оценки ТЗ «${report.pageTitle}» (score: ${report.overallScore}):")
            if (report.qualityIssues.isNotEmpty()) {
                appendLine("Замечания к требованиям:")
                report.qualityIssues.take(5).forEach { appendLine("- [${it.requirementId}] ${it.message}") }
            }
            if (report.hasGeneratedContent) {
                appendLine("Недостающие требования для доработки:")
                report.generatedSections.forEach { (s, reqs) ->
                    reqs.forEach { appendLine("- [$s] ${it.description}") }
                }
            }
        }
        onRefineRequested?.invoke(report.pageTitle, summary)
    }

    private fun toggleEditMode() {
        val report = currentReport ?: return
        val md = AssessmentMarkdownExporter.toMarkdown(report)
        val editArea = JTextArea(md).apply {
            lineWrap = true; wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        }
        val scroll = JBScrollPane(editArea).apply {
            preferredSize = Dimension(JBUI.scale(600), JBUI.scale(400))
        }
        val options = arrayOf<Any>("Копировать", "Закрыть")
        val choice = JOptionPane.showOptionDialog(
            this, scroll, "Редактирование отчёта",
            JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
            null, options, options[1]
        )
        if (choice == 0) {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(editArea.text), null)
        }
    }

    private fun uploadToConfluence() {
        val report = currentReport ?: return
        val md = AssessmentMarkdownExporter.toMarkdown(report)
        val filename = "assessment-${report.pageTitle.replace("[^\\w]".toRegex(), "_").take(30)}.md"
        try {
            val creds = changelogai.feature.spec.confluence.ConfluenceFetcher.resolveForPage(report.pageUrl)
            if (creds == null) {
                JOptionPane.showMessageDialog(this, "Не удалось найти credentials Confluence в MCP настройках", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            val client = changelogai.feature.spec.confluence.ConfluenceRestClient(
                baseUrl = creds.first, token = creds.second, skipTls = true
            )
            val ok = client.uploadAttachment(report.pageId, filename, md.toByteArray())
            if (ok) JOptionPane.showMessageDialog(this, "Файл «$filename» прикреплён к странице", "Готово", JOptionPane.INFORMATION_MESSAGE)
            else JOptionPane.showMessageDialog(this, "Не удалось загрузить файл", "Ошибка", JOptionPane.ERROR_MESSAGE)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Ошибка: ${e.message}", "Ошибка", JOptionPane.ERROR_MESSAGE)
        }
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private fun buildHeader(report: AssessmentReport): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            border = EmptyBorder(JBUI.scale(8), JBUI.scale(12), JBUI.scale(8), JBUI.scale(12))
        }
        scoreCircle.setScore(report.overallScore)
        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            val title = JBLabel(report.pageTitle).apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(14))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val subtitle = JBLabel("${report.scoreLabel} · ${report.totalStoryPoints} SP · " +
                    "${report.qualityIssues.size} замечаний").apply {
                foreground = JBColor.GRAY
                font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val link = JBLabel("<html><a href='${report.pageUrl}'>${report.pageUrl}</a></html>").apply {
                foreground = JBColor.BLUE
                font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(11))
                toolTipText = report.pageUrl
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(title); add(Box.createVerticalStrut(JBUI.scale(2)))
            add(subtitle); add(Box.createVerticalStrut(JBUI.scale(2)))
            add(link)
        }
        panel.add(scoreCircle, BorderLayout.WEST)
        panel.add(info, BorderLayout.CENTER)
        return panel
    }

    private fun buildSuggestionsPanel(suggestions: List<String>): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(JBUI.scale(4), JBUI.scale(12), JBUI.scale(4), JBUI.scale(12))
        }
        panel.add(sectionLabel("💡 Общие рекомендации"))
        suggestions.forEach { s ->
            panel.add(wrapText("• $s"))
        }
        return panel
    }

    // ── Score Circle ───────────────────────────────────────────────────────

    inner class ScoreCircle : JComponent() {
        private var score = 0
        init {
            preferredSize = Dimension(JBUI.scale(72), JBUI.scale(72))
            minimumSize = preferredSize
        }
        fun setScore(s: Int) { score = s.coerceIn(0, 100); repaint() }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = minOf(width, height)
            val arc = (score / 100.0 * 360).toInt()
            val color = when {
                score >= 80 -> Color(76, 175, 80)
                score >= 60 -> Color(255, 193, 7)
                else        -> Color(244, 67, 54)
            }
            g2.color = JBColor.border()
            g2.fillOval(2, 2, size - 4, size - 4)
            g2.color = color
            g2.fillArc(2, 2, size - 4, size - 4, 90, -arc)
            val inner = (size * 0.65).toInt()
            val offset = (size - inner) / 2
            g2.color = background ?: JBColor.background()
            g2.fillOval(offset, offset, inner, inner)
            g2.color = foreground ?: JBColor.foreground()
            g2.font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(16))
            val fm = g2.fontMetrics
            val text = "$score"
            g2.drawString(text, (size - fm.stringWidth(text)) / 2, size / 2 + fm.ascent / 2 - 1)
        }
    }
}

// ── Structure Results ──────────────────────────────────────────────────────

private class StructureResultsPanel : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(12), JBUI.scale(4), JBUI.scale(12))
    }

    fun update(errors: List<String>, warnings: List<String>) {
        removeAll()
        add(sectionLabel("🏗️ Структура"))
        errors.forEach { e ->
            add(wrapText("✗ $e", JBColor(Color(200, 50, 50), Color(255, 100, 100))))
        }
        warnings.forEach { w ->
            add(wrapText("⚠ $w", JBColor(Color(180, 120, 0), Color(220, 180, 50))))
        }
        revalidate()
    }
}

// ── Quality Issues ─────────────────────────────────────────────────────────

private class QualityIssuesPanel : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(12), JBUI.scale(4), JBUI.scale(12))
    }

    fun update(issues: List<QualityIssue>) {
        removeAll()
        add(sectionLabel("🔎 Качество требований (${issues.size} замечаний)"))
        issues.forEach { issue ->
            val color = when (issue.severity) {
                IssueSeverity.ERROR   -> JBColor(Color(200, 50, 50), Color(255, 100, 100))
                IssueSeverity.WARNING -> JBColor(Color(180, 120, 0), Color(220, 180, 50))
                IssueSeverity.INFO    -> JBColor.GRAY
            }
            val prefix = when (issue.severity) {
                IssueSeverity.ERROR   -> "✗"
                IssueSeverity.WARNING -> "⚠"
                IssueSeverity.INFO    -> "ℹ"
            }
            val row = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = EmptyBorder(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            row.add(wrapText("$prefix [${issue.requirementId}] ${issue.message}", color))
            if (issue.suggestion.isNotBlank()) {
                row.add(wrapText("  → ${issue.suggestion}", italic = true))
            }
            add(row)
        }
        revalidate()
    }
}

// ── Generated Sections ─────────────────────────────────────────────────────

private class GeneratedSectionsPanel : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(12), JBUI.scale(4), JBUI.scale(12))
    }

    fun update(sections: Map<String, List<SpecDocument.Requirement>>) {
        removeAll()
        val total = sections.values.sumOf { it.size }
        add(sectionLabel("🔧 Сгенерировано недостающих требований ($total)"))
        sections.forEach { (sectionName, reqs) ->
            add(JTextArea("  $sectionName:").apply {
                isEditable = false; isOpaque = false; lineWrap = false
                font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(12))
                border = EmptyBorder(JBUI.scale(4), 0, JBUI.scale(2), 0)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            reqs.forEach { req ->
                add(wrapText("  • [${req.id}] ${req.description}",
                    JBColor(Color(30, 130, 76), Color(80, 200, 120))))
            }
        }
        revalidate()
    }
}

// ── Effort Table ───────────────────────────────────────────────────────────

private class EffortTablePanel : JPanel(BorderLayout()) {
    private val tableModel = DefaultTableModel(
        arrayOf("Требование", "SP", "Сложность", "Обоснование"), 0
    )
    private val table = com.intellij.ui.table.JBTable(tableModel).apply {
        setShowGrid(false)
        rowHeight = JBUI.scale(24)
        tableHeader.reorderingAllowed = false
        autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = JBUI.scale(180)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(36)
        columnModel.getColumn(1).maxWidth        = JBUI.scale(50)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(70)
        columnModel.getColumn(2).maxWidth        = JBUI.scale(90)
        columnModel.getColumn(3).preferredWidth = JBUI.scale(300)
    }
    private val totalLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(12))
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(12), JBUI.scale(4), 0)
    }

    init {
        isOpaque = false
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(12), JBUI.scale(4), JBUI.scale(12))
        add(sectionLabel("⏱️ Оценка трудозатрат"), BorderLayout.NORTH)
        add(JBScrollPane(table).apply { preferredSize = Dimension(0, JBUI.scale(200)) }, BorderLayout.CENTER)
        add(totalLabel, BorderLayout.SOUTH)
    }

    fun update(estimates: List<EffortEstimate>, totalSp: Int) {
        tableModel.rowCount = 0
        estimates.forEach { e ->
            tableModel.addRow(arrayOf(
                e.description,
                e.storyPoints,
                complexityLabel(e.complexity),
                e.rationale
            ))
        }
        totalLabel.text = "Итого: $totalSp story points"
    }

    private fun complexityLabel(c: Complexity) = when (c) {
        Complexity.LOW    -> "Низкая"
        Complexity.MEDIUM -> "Средняя"
        Complexity.HIGH   -> "Высокая"
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

internal fun sectionLabel(text: String): JBLabel = JBLabel(text).apply {
    font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(13))
    border = EmptyBorder(JBUI.scale(4), 0, JBUI.scale(4), 0)
    alignmentX = Component.LEFT_ALIGNMENT
}

internal fun wrapText(text: String, color: Color? = null, italic: Boolean = false): JComponent =
    JTextArea(text).apply {
        lineWrap = true; wrapStyleWord = true; isEditable = false; isOpaque = false
        font = Font(Font.SANS_SERIF, if (italic) Font.ITALIC else Font.PLAIN, JBUI.scale(12))
        border = EmptyBorder(JBUI.scale(1), JBUI.scale(8), JBUI.scale(1), JBUI.scale(4))
        color?.let { foreground = it }
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }
