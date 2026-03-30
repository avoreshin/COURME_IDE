package changelogai.feature.gigacodeae.orchestrator

/**
 * Режим работы оркестратора.
 * - [AUTO] — rules-based классификация (текущее поведение)
 * - [SINGLE_AGENT] — всегда один агент, без планирования
 * - [MULTI_STEP] — всегда Planner → multi-step execution
 */
enum class OrchestratorMode(val label: String) {
    AUTO("Авто"),
    SINGLE_AGENT("Агент"),
    MULTI_STEP("Планирование");

    override fun toString() = label
}
