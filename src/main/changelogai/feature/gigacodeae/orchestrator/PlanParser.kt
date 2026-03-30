package changelogai.feature.gigacodeae.orchestrator

/**
 * Парсит вывод PlannerAgent в список шагов с тегами агентов.
 *
 * Формат PlannerAgent:
 *   1. [CODE] Изменить класс X
 *   2. [SEARCH] Найти использования Y
 *   3. [TEST] Написать тесты для Z
 */
object PlanParser {

    private val STEP_REGEX = Regex("""^\s*\d+[.)]\s*\[(\w+)]\s*(.+)""", RegexOption.MULTILINE)

    private val TAG_TO_AGENT = mapOf(
        "CODE" to "Code",
        "SEARCH" to "Search",
        "TEST" to "Test",
        "REVIEW" to "Review",
        "TOOL" to "Tool"
    )

    fun parse(planOutput: String): List<PlanStep> {
        val steps = STEP_REGEX.findAll(planOutput).map { match ->
            val tag = match.groupValues[1].uppercase()
            PlanStep(
                tag = tag,
                agentName = TAG_TO_AGENT[tag] ?: "Code",
                description = match.groupValues[2].trim()
            )
        }.toList()

        // Если не удалось распарсить — fallback: один шаг Code с полным текстом
        if (steps.isEmpty()) {
            return listOf(PlanStep(tag = "CODE", agentName = "Code", description = planOutput.trim()))
        }
        return steps
    }
}

data class PlanStep(
    val tag: String,
    val agentName: String,
    val description: String
)
