package changelogai.feature.changelog

import com.intellij.openapi.project.Project
import changelogai.core.llm.cancellation.Cancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.core.settings.model.CommitLanguage
import changelogai.platform.LLMClientFactory

class ChangelogGenerator {

    data class Result(val sections: String, val rawText: String)

    fun generate(project: Project, commitMessages: List<String>, existingChangelog: String, cancelable: Cancelable?): Result {
        val state = PluginState.getInstance()
        val promptFile = when (state.commitLanguage) {
            CommitLanguage.Russian -> "prompt_ru.md"
            CommitLanguage.English -> "prompt_en.md"
        }
        val systemPrompt = PromptLoader.load(project, promptFile)
        val commitsText = commitMessages.joinToString("\n") { "- $it" }
        val userMessage = buildString {
            append("Commits:\n$commitsText")
            if (existingChangelog.isNotBlank()) {
                append("\n\nExisting CHANGELOG.md (для избежания дублей):\n$existingChangelog")
            }
        }
        val request = ChatRequest(
            model = state.aiModel,
            temperature = state.temperature.value,
            maxTokens = state.commitSize.value,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage)
            )
        )
        LLMClientFactory.create(state, cancelable).use { client ->
            val response = client.postChatCompletions(request)
            val raw = response.choices
                .firstOrNull()
                ?.message
                ?.content
                ?.trim()
                ?: throw IllegalStateException("LLM returned empty response")
            return Result(sections = extractChangelogSections(raw), rawText = raw)
        }
    }

    /**
     * Вырезает из ответа LLM только строки changelog-формата:
     * - ### Added / Fixed / Changed / Removed
     * - строки начинающиеся с "- "
     * Всё остальное (объяснения, комментарии, заголовки версий) — отбрасывается.
     */
    private fun extractChangelogSections(raw: String): String {
        val validSectionHeaders = setOf("added", "fixed", "changed", "removed")
        val result = mutableListOf<String>()
        var inSection = false

        for (line in raw.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") && trimmed.removePrefix("### ").lowercase() in validSectionHeaders -> {
                    inSection = true
                    if (result.isNotEmpty()) result.add("")
                    result.add(trimmed)
                }
                inSection && trimmed.startsWith("- ") -> result.add(trimmed)
                inSection && trimmed.isEmpty() -> { /* пропускаем пустые строки внутри секции */ }
                trimmed.startsWith("### ") -> inSection = false // неизвестная секция — выходим
                trimmed.startsWith("## ") -> inSection = false  // заголовок версии — пропускаем
            }
        }
        return result.dropWhile { it.isBlank() }.joinToString("\n")
    }
}
