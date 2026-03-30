package changelogai.feature.gigacodeae.orchestrator

import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.FunctionDefinition

/**
 * Минимальный контекст, передаваемый суб-агенту.
 * Содержит только то, что нужно конкретному агенту — без полной истории.
 */
data class AgentContext(
    val task: String,
    val conversationSummary: String?,
    val recentMessages: List<ChatMessage>,
    val availableTools: List<FunctionDefinition>,
    val projectBrief: ProjectBrief
)

data class ProjectBrief(
    val name: String,
    val language: String? = null,
    val framework: String? = null,
    val basePath: String
)
