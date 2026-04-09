package changelogai.feature.spec.jira

import changelogai.core.jira.JiraMcpFetcher
import changelogai.core.jira.JiraMcpNotConfiguredException
import changelogai.core.jira.JiraTool
import changelogai.core.settings.PluginState
import changelogai.feature.spec.confluence.AssessmentReport
import com.intellij.openapi.project.Project

/**
 * Создаёт Jira-задачи из результатов оценки ТЗ.
 *
 * Структура: 1 Story (вся спека, суммарные SP) + Subtask на каждый EffortEstimate.
 * Project key берётся из PluginState.jiraProjectKey.
 */
class JiraTaskCreator(private val project: Project) {

    fun createFromReport(
        report: AssessmentReport,
        onProgress: (String) -> Unit,
        onSuccess: (storyKey: String, subtaskCount: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val projectKey = PluginState.getInstance().jiraProjectKey.trim()
        if (projectKey.isEmpty()) {
            onError("Jira project key не настроен. Укажите его в Settings → Jira project key.")
            return
        }

        val fetcher = JiraMcpFetcher(project)
        if (!fetcher.isConfigured()) {
            onError("Jira MCP сервер не настроен. Добавьте сервер с preset='jira' в MCP Settings.")
            return
        }

        try {
            onProgress("Создаю Story «${report.pageTitle}»...")
            val storyArgs = buildMap<String, Any> {
                put("project", projectKey)
                put("summary", report.pageTitle)
                put("issuetype", "Story")
                put("issue_type", "Story")
                val description = buildString {
                    append("Spec: ${report.pageUrl}")
                    appendLine()
                    append("Оценка: ${report.overallScore}/100 — ${report.scoreLabel}")
                    if (report.suggestions.isNotEmpty()) {
                        appendLine(); appendLine()
                        append("Рекомендации:\n")
                        report.suggestions.forEach { append("• $it\n") }
                    }
                }
                put("description", description)
                if (report.totalStoryPoints > 0) {
                    put("story_points", report.totalStoryPoints)
                    put("storyPoints", report.totalStoryPoints)
                }
            }

            val storyResponse = fetcher.resolveAndCall(JiraTool.CREATE_ISSUE, storyArgs)
            val storyKey = extractIssueKey(storyResponse)
                ?: throw IllegalStateException("Не удалось получить ключ созданной Story из ответа MCP: $storyResponse")

            val estimates = report.effortEstimates
            estimates.forEachIndexed { index, estimate ->
                onProgress("Создаю подзадачу ${index + 1}/${estimates.size}: «${estimate.description.take(50)}»...")
                val subtaskArgs = buildMap<String, Any> {
                    put("parent", storyKey)
                    put("summary", estimate.description)
                    put("issuetype", "Subtask")
                    put("issue_type", "Subtask")
                    if (estimate.rationale.isNotBlank()) {
                        put("description", estimate.rationale)
                    }
                    put("story_points", estimate.storyPoints)
                    put("storyPoints", estimate.storyPoints)
                }
                fetcher.resolveAndCall(JiraTool.CREATE_ISSUE, subtaskArgs)
            }

            onSuccess(storyKey, estimates.size)
        } catch (e: JiraMcpNotConfiguredException) {
            onError(e.message ?: "Jira MCP не настроен")
        } catch (e: Exception) {
            onError("Ошибка при создании задач: ${e.message}")
        }
    }

    /** Извлекает ключ задачи (например PROJ-42) из ответа MCP. */
    private fun extractIssueKey(response: String): String? {
        // Типичный ответ: "Created issue PROJ-42" или JSON {"key":"PROJ-42"}
        val keyRegex = Regex("""[A-Z][A-Z0-9_]+-\d+""")
        return keyRegex.find(response)?.value
    }
}
