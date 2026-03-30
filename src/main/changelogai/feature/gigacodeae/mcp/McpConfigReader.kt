package changelogai.feature.gigacodeae.mcp

import com.intellij.openapi.project.Project

/**
 * Читает конфигурацию MCP-серверов:
 * 1. Авто-обнаружение из IDE-конфигов (Cursor, Claude Desktop, mcp.json в проекте)
 * 2. Вручную добавленные серверы из McpState (дополняют, не дублируют)
 */
object McpConfigReader {

    fun readServers(project: Project): List<McpServerConfig> {
        val discovered = McpDiscovery.discover(project)
        val discoveredNames = discovered.map { it.name }.toSet()

        val manual = McpState.getInstance().servers
            .filter { it.enabled && it.name.isNotBlank() && it.name !in discoveredNames }
            .map { it.toServerConfig() }

        return discovered.map { it.config } + manual
    }

    /** Без project — только ручные (для обратной совместимости) */
    fun readServers(): List<McpServerConfig> =
        McpState.getInstance().servers
            .filter { it.enabled }
            .map { it.toServerConfig() }
}
