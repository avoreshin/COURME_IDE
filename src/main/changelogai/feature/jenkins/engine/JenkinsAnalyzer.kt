package changelogai.feature.jenkins.engine

import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.feature.jenkins.model.JenkinsAnalysis
import changelogai.platform.LLMClientFactory
import com.intellij.openapi.project.Project

class JenkinsAnalyzer(private val project: Project) {

    private val contextCollector = JenkinsContextCollector(project)

    /** Анализирует лог сборки через LLM. Запускать с фонового потока. */
    fun analyze(log: String): JenkinsAnalysis {
        val context = contextCollector.collect()
        val truncatedLog = JenkinsContextCollector.truncateLog(log, 4000)
        val prompt = buildPrompt(truncatedLog, context)
        val state = PluginState.getInstance()
        val request = ChatRequest(
            model = state.aiModel,
            temperature = 0.2,
            maxTokens = 1500,
            messages = listOf(ChatMessage("user", prompt))
        )
        return LLMClientFactory.create(state, null).use { client ->
            val raw = client.postChatCompletions(request).choices.firstOrNull()?.message?.content ?: ""
            parseAnalysisResponse(raw)
        }
    }

    companion object {

        fun buildPrompt(log: String, context: JenkinsContext): String {
            val filesSection = if (context.changedFiles.isNotEmpty())
                "\n\nИзменённые файлы в последнем коммите:\n" +
                context.changedFiles.joinToString("\n") { "- $it" }
            else ""
            val jenkinsfileSection = if (!context.jenkinsfileContent.isNullOrBlank())
                "\n\nСодержимое Jenkinsfile:\n```groovy\n${context.jenkinsfileContent.take(1000)}\n```"
            else ""

            return """
Ты эксперт по CI/CD. Проанализируй лог упавшей Jenkins-сборки и ответь на русском языке.

Лог сборки (последние 4000 символов):
```
$log
```$filesSection$jenkinsfileSection

Ответь строго в формате markdown с тремя разделами:

## Root Cause
(1-2 предложения: краткая причина падения)

## Related Files
(список файлов проекта через дефис, которые вероятно вызвали ошибку; пустой если неизвестно)

## Suggestions
(конкретные шаги для исправления через дефис, минимум 2)
            """.trimIndent()
        }

        fun parseAnalysisResponse(markdown: String): JenkinsAnalysis {
            val rootCause = extractSection(markdown, "Root Cause")
                ?: markdown.lines().firstOrNull { it.isNotBlank() }
                ?: "Не удалось определить причину"

            val relatedFiles = extractListSection(markdown, "Related Files")
            val suggestions = extractListSection(markdown, "Suggestions")

            return JenkinsAnalysis(
                rootCause = rootCause.trim(),
                relatedFiles = relatedFiles,
                suggestions = suggestions
            )
        }

        private fun extractSection(markdown: String, header: String): String? {
            val regex = Regex("""##\s+$header\s*\n(.*?)(?=\n##|\z)""", RegexOption.DOT_MATCHES_ALL)
            return regex.find(markdown)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun extractListSection(markdown: String, header: String): List<String> {
            val sectionText = extractSection(markdown, header) ?: return emptyList()
            return sectionText.lines()
                .map { it.trimStart('-', '*', ' ').trim() }
                .filter { it.isNotBlank() }
        }
    }
}
