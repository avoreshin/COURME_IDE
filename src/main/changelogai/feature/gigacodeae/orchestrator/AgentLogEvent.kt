package changelogai.feature.gigacodeae.orchestrator

/**
 * Событие лога оркестратора, отображаемое в UI чата.
 */
data class AgentLogEvent(
    val type: Type,
    val message: String,
    val details: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Type {
        /** Классификация intent */
        INTENT,
        /** Выбор агента */
        AGENT_START,
        /** Агент завершил работу */
        AGENT_DONE,
        /** Шаг multi-step плана */
        PLAN_STEP,
        /** План создан */
        PLAN_CREATED,
        /** Суммаризация истории */
        SUMMARIZE,
        /** Ошибка */
        ERROR
    }
}
