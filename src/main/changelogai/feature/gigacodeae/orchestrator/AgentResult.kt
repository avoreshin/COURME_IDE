package changelogai.feature.gigacodeae.orchestrator

/**
 * Результат работы суб-агента.
 * [summary] — сжатая версия для оркестратора (передаётся другим агентам).
 * [fullOutput] — полный ответ (показывается пользователю).
 */
data class AgentResult(
    val agentName: String,
    val summary: String,
    val fullOutput: String,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val status: AgentStatus = AgentStatus.SUCCESS
)

data class ToolCallRecord(
    val name: String,
    val args: String,
    val result: String,
    val durationMs: Long
)

enum class AgentStatus { SUCCESS, PARTIAL, ERROR }
