package changelogai.feature.jenkins.engine

import changelogai.core.mcp.McpState
import changelogai.feature.gigacodeae.mcp.McpClient
import changelogai.feature.gigacodeae.mcp.McpService
import changelogai.feature.gigacodeae.mcp.McpToolInfo
import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsBuild
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.Project

class JenkinsMcpFetcher(private val project: Project) {

    private val mapper = jacksonObjectMapper()

    private fun findJenkinsClient(): McpClient? {
        val entry = McpState.getInstance().servers
            .filter { it.enabled }
            .firstOrNull { it.preset == "jenkins" }
            ?: return null
        return McpService.getInstance(project).clients
            .firstOrNull { it.name == entry.name }
    }

    fun isConfigured(): Boolean = findJenkinsClient() != null

    private fun listTools(): List<McpToolInfo> =
        findJenkinsClient()?.listTools() ?: emptyList()

    private fun callTool(toolName: String, args: Map<String, Any>): String {
        val client = findJenkinsClient()
            ?: throw JenkinsMcpNotConfiguredException()
        return client.callTool(toolName, args)
    }

    private fun resolveAndCall(candidates: List<String>, args: Map<String, Any>): String {
        val available = listTools().map { it.name }.toSet()
        val tool = candidates.firstOrNull { candidate ->
            available.any { it.endsWith("__$candidate") || it == candidate }
        } ?: candidates.first()
        return callTool(tool, args)
    }

    fun getPipelines(): List<JenkinsPipeline> {
        val json = resolveAndCall(JenkinsTool.GET_PIPELINES, emptyMap())
        return parsePipelines(json)
    }

    fun getBuildLog(pipelineName: String, buildNumber: Int): String {
        return resolveAndCall(
            JenkinsTool.GET_BUILD_LOG,
            mapOf("pipeline" to pipelineName, "job" to pipelineName, "buildNumber" to buildNumber, "build_number" to buildNumber)
        )
    }

    fun triggerBuild(pipelineName: String) {
        resolveAndCall(
            JenkinsTool.TRIGGER_BUILD,
            mapOf("pipeline" to pipelineName, "job" to pipelineName)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePipelines(json: String): List<JenkinsPipeline> {
        return try {
            val data: Any = mapper.readValue(json, Any::class.java)
            val items: List<Map<String, Any>> = when {
                data is List<*> -> data as List<Map<String, Any>>
                data is Map<*, *> && data["jobs"] != null -> data["jobs"] as List<Map<String, Any>>
                data is Map<*, *> && data["pipelines"] != null -> data["pipelines"] as List<Map<String, Any>>
                else -> return emptyList()
            }
            items.mapNotNull { item ->
                val name = item["name"]?.toString() ?: return@mapNotNull null
                val url = item["url"]?.toString() ?: ""
                val statusRaw = item["color"]?.toString()
                    ?: item["status"]?.toString()
                    ?: item["result"]?.toString()
                    ?: "UNKNOWN"
                val status = when (statusRaw.lowercase()) {
                    "blue", "success" -> BuildStatus.SUCCESS
                    "red", "failure", "failed" -> BuildStatus.FAILURE
                    "yellow", "unstable" -> BuildStatus.UNSTABLE
                    "blue_anime", "red_anime", "notbuilt_anime", "in_progress" -> BuildStatus.IN_PROGRESS
                    "aborted" -> BuildStatus.ABORTED
                    else -> BuildStatus.UNKNOWN
                }
                val lastBuildMap = item["lastBuild"] as? Map<String, Any>
                val lastBuild = lastBuildMap?.let {
                    JenkinsBuild(
                        number = it["number"]?.toString()?.toIntOrNull() ?: 0,
                        status = status,
                        timestamp = it["timestamp"]?.toString()?.toLongOrNull() ?: 0L,
                        durationMs = it["duration"]?.toString()?.toLongOrNull() ?: 0L
                    )
                }
                JenkinsPipeline(name = name, url = url, status = status, lastBuild = lastBuild)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class JenkinsMcpNotConfiguredException :
    Exception("Jenkins MCP сервер не настроен. Добавьте сервер с preset='jenkins' в MCP Settings.")

object JenkinsTool {
    val GET_PIPELINES = listOf(
        "jenkins_get_pipelines", "get_pipelines", "list_jobs", "jenkins_list_jobs", "getJobs"
    )
    val GET_BUILD_LOG = listOf(
        "jenkins_get_build_log", "get_build_log", "getBuildLog", "jenkins_build_log"
    )
    val TRIGGER_BUILD = listOf(
        "jenkins_trigger_build", "trigger_build", "triggerBuild", "jenkins_build"
    )
}
