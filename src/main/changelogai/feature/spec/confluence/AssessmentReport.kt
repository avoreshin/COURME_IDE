package changelogai.feature.spec.confluence

import changelogai.feature.spec.model.SpecDocument

/**
 * Результат оценки существующего ТЗ из Confluence.
 */
data class AssessmentReport(
    val pageTitle: String,
    val pageUrl: String,
    val pageId: String = "",
    val overallScore: Int,                          // 0-100
    val structureErrors: List<String>,              // ошибки структуры (SpecValidator)
    val structureWarnings: List<String>,            // предупреждения структуры
    val qualityIssues: List<QualityIssue>,          // проблемы качества (LLM-агент)
    val generatedSections: Map<String, List<SpecDocument.Requirement>>, // "NFR" → сгенерированные
    val effortEstimates: List<EffortEstimate>,       // оценка трудозатрат по FR
    val suggestions: List<String>,                  // общие рекомендации
    val extractedSpec: SpecDocument?                // извлечённая структура (если удалось)
) {
    val totalStoryPoints: Int get() = effortEstimates.sumOf { it.storyPoints }

    val scoreLabel: String get() = when {
        overallScore >= 80 -> "Отлично"
        overallScore >= 60 -> "Хорошо"
        overallScore >= 40 -> "Удовлетворительно"
        else               -> "Требует доработки"
    }

    val hasErrors: Boolean get() = structureErrors.isNotEmpty()
    val hasGeneratedContent: Boolean get() = generatedSections.isNotEmpty()
}

data class QualityIssue(
    val requirementId: String,
    val severity: IssueSeverity,
    val message: String,
    val suggestion: String
)

enum class IssueSeverity { ERROR, WARNING, INFO }

data class EffortEstimate(
    val requirementId: String,
    val description: String,     // краткое описание требования
    val storyPoints: Int,        // Fibonacci: 1, 2, 3, 5, 8, 13
    val complexity: Complexity,
    val rationale: String
)

enum class Complexity { LOW, MEDIUM, HIGH }

enum class AssessmentState {
    IDLE, EXTRACTING, VALIDATING, ASSESSING, FILLING_GAPS, ESTIMATING, COMPLETE, ERROR
}
