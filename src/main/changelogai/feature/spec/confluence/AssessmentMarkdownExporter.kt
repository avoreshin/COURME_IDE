package changelogai.feature.spec.confluence

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Конвертирует AssessmentReport в Markdown-документ.
 */
object AssessmentMarkdownExporter {

    fun toMarkdown(report: AssessmentReport): String = buildString {
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

        appendLine("# Оценка ТЗ: ${report.pageTitle}")
        appendLine()
        appendLine("**Дата:** $date  ")
        appendLine("**Источник:** [${report.pageUrl}](${report.pageUrl})  ")
        appendLine("**Оценка:** ${report.overallScore}/100 — ${report.scoreLabel}  ")
        appendLine("**Трудозатраты:** ${report.totalStoryPoints} story points  ")
        appendLine("**Замечаний:** ${report.qualityIssues.size}")
        appendLine()
        appendLine("---")
        appendLine()

        // Structure
        if (report.structureErrors.isNotEmpty() || report.structureWarnings.isNotEmpty()) {
            appendLine("## 🏗️ Структура")
            report.structureErrors.forEach { appendLine("- ❌ $it") }
            report.structureWarnings.forEach { appendLine("- ⚠️ $it") }
            appendLine()
        }

        // Quality issues
        if (report.qualityIssues.isNotEmpty()) {
            appendLine("## 🔎 Качество требований")
            appendLine()
            report.qualityIssues.forEach { issue ->
                val icon = when (issue.severity) {
                    IssueSeverity.ERROR   -> "❌"
                    IssueSeverity.WARNING -> "⚠️"
                    IssueSeverity.INFO    -> "ℹ️"
                }
                appendLine("**$icon [${issue.requirementId}]** ${issue.message}")
                if (issue.suggestion.isNotBlank()) {
                    appendLine("  > → ${issue.suggestion}")
                }
                appendLine()
            }
        }

        // Generated requirements
        if (report.hasGeneratedContent) {
            appendLine("## 🔧 Сгенерировано недостающих требований")
            appendLine()
            report.generatedSections.forEach { (section, reqs) ->
                appendLine("### $section")
                reqs.forEach { req ->
                    appendLine("- **[${req.id}]** ${req.description}")
                }
                appendLine()
            }
        }

        // Effort
        if (report.effortEstimates.isNotEmpty()) {
            appendLine("## ⏱️ Оценка трудозатрат")
            appendLine()
            appendLine("| Требование | SP | Сложность | Обоснование |")
            appendLine("|---|---|---|---|")
            report.effortEstimates.forEach { e ->
                val complexity = when (e.complexity) {
                    Complexity.LOW    -> "Низкая"
                    Complexity.MEDIUM -> "Средняя"
                    Complexity.HIGH   -> "Высокая"
                }
                appendLine("| ${e.description} | ${e.storyPoints} | $complexity | ${e.rationale} |")
            }
            appendLine()
            appendLine("**Итого: ${report.totalStoryPoints} story points**")
            appendLine()
        }

        // Suggestions
        if (report.suggestions.isNotEmpty()) {
            appendLine("## 💡 Общие рекомендации")
            report.suggestions.forEach { appendLine("- $it") }
            appendLine()
        }
    }
}
