package changelogai.feature.sprint.engine

import changelogai.core.mcp.McpState
import changelogai.feature.gigacodeae.mcp.McpClient
import changelogai.feature.gigacodeae.mcp.McpService
import changelogai.feature.gigacodeae.mcp.McpToolInfo
import com.intellij.openapi.project.Project

/**
 * Точка входа для всех Jira-операций через MCP-сервер с preset="jira".
 */
class JiraMcpFetcher(private val project: Project) {

    private fun findJiraClient(): McpClient? {
        val jiraEntry = McpState.getInstance().servers
            .filter { it.enabled }
            .firstOrNull { it.preset == "jira" || it.preset == "confluence" }
            ?: return null
        return McpService.getInstance(project).clients
            .firstOrNull { it.name == jiraEntry.name }
    }

    fun listTools(): List<McpToolInfo> =
        findJiraClient()?.listTools() ?: emptyList()

    fun callTool(toolName: String, args: Map<String, Any>): String {
        val client = findJiraClient()
            ?: throw JiraMcpNotConfiguredException()
        return client.callTool(toolName, args)
    }

    fun isConfigured(): Boolean = findJiraClient() != null

    /**
     * Перебирает список кандидатов и вызывает первый доступный инструмент.
     * Разные Jira MCP-серверы называют инструменты по-разному.
     */
    fun resolveAndCall(candidates: List<String>, args: Map<String, Any>): String {
        val available = listTools().map { it.name }.toSet()
        // Имена из listTools() имеют вид "serverName__toolName", нужно сравнивать суффикс
        val tool = candidates.firstOrNull { candidate ->
            available.any { it.endsWith("__$candidate") || it == candidate }
        } ?: candidates.first()
        return callTool(tool, args)
    }
}

class JiraMcpNotConfiguredException :
    Exception("Jira MCP сервер не настроен. Добавьте сервер с preset='jira' в MCP Settings.")

/** Канонические имена инструментов Jira MCP — порядок = приоритет */
object JiraTool {
    val GET_SPRINT_ISSUES = listOf(
        "get_sprint_issues", "getSprintIssues",
        "get_issues_for_sprint", "jira_get_sprint_issues"
    )
    val GET_ACTIVE_SPRINT = listOf(
        "get_active_sprint", "getActiveSprint",
        "get_sprints_for_board", "jira_get_sprints"
    )
    val SEARCH_ISSUES_JQL = listOf(
        "search_issues", "jira_search", "jql_search",
        "get_issues_by_jql", "jira_jql"
    )
    val CREATE_ISSUE = listOf(
        "create_issue", "createIssue", "jira_create_issue"
    )
    val GET_PROJECT_VERSIONS = listOf(
        "get_project_versions", "getProjectVersions",
        "list_project_versions", "get_versions", "jira_get_versions"
    )
}
