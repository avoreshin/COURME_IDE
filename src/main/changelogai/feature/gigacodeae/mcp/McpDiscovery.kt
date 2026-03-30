package changelogai.feature.gigacodeae.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Авто-обнаружение MCP-серверов из стандартных конфиг-файлов IDE/инструментов:
 *  - {project}/.cursor/mcp.json      (Cursor — project-level)
 *  - {project}/mcp.json              (Cursor / общий project-level)
 *  - ~/.cursor/mcp.json              (Cursor — global)
 *  - ~/Library/Application Support/Claude/claude_desktop_config.json  (Claude Desktop macOS)
 *  - ~/.config/Claude/claude_desktop_config.json                      (Claude Desktop Linux)
 *  - %APPDATA%\Claude\claude_desktop_config.json                      (Claude Desktop Windows)
 *  - ~/.claude/claude_desktop_config.json                             (альтернатива)
 */
object McpDiscovery {

    data class DiscoveredServer(
        val name: String,
        val config: McpServerConfig,
        val source: String   // "cursor-project" | "cursor-global" | "claude-desktop" | "project-mcp"
    )

    private val mapper = jacksonObjectMapper()

    fun discover(project: Project): List<DiscoveredServer> {
        val result = mutableListOf<DiscoveredServer>()
        val home = System.getProperty("user.home") ?: ""
        val basePath = project.basePath ?: ""

        // Project-level configs
        if (basePath.isNotBlank()) {
            result += readMcpJson(File(basePath, ".mcp.json"), "project-mcp")    // AI-OAssist / Claude Code
            result += readMcpJson(File(basePath, ".cursor/mcp.json"), "cursor-project")
            result += readMcpJson(File(basePath, "mcp.json"), "project-mcp")
        }

        // Global AI-OAssist config
        result += readMcpJson(File(home, ".changelogai/mcp.json"), "global")

        // Global Cursor
        result += readMcpJson(File(home, ".cursor/mcp.json"), "cursor-global")

        // Claude Desktop
        val claudePaths = listOf(
            File(home, "Library/Application Support/Claude/claude_desktop_config.json"), // macOS
            File(home, ".config/Claude/claude_desktop_config.json"),                     // Linux
            File(home, ".claude/claude_desktop_config.json"),                            // alt
            System.getenv("APPDATA")?.let { File(it, "Claude/claude_desktop_config.json") }  // Windows
        )
        claudePaths.filterNotNull().filter { it.exists() }.forEach { f ->
            result += readClaudeDesktop(f)
        }

        return result.distinctBy { it.name }
    }

    // ── Parsers ────────────────────────────────────────────────────────────

    /** Формат Cursor / generic mcp.json: {"mcpServers": {"name": {"command":..., "args":[...], "env":{}}}} */
    private fun readMcpJson(file: File, source: String): List<DiscoveredServer> {
        if (!file.exists()) return emptyList()
        return try {
            val root: Map<String, Any> = mapper.readValue(file)
            @Suppress("UNCHECKED_CAST")
            val servers = root["mcpServers"] as? Map<String, Any> ?: return emptyList()
            servers.mapNotNull { (name, value) ->
                @Suppress("UNCHECKED_CAST")
                val cfg = value as? Map<String, Any> ?: return@mapNotNull null
                parseServerEntry(name, cfg, source)
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Claude Desktop формат: тот же mcpServers, но файл называется claude_desktop_config.json */
    private fun readClaudeDesktop(file: File): List<DiscoveredServer> =
        readMcpJson(file, "claude-desktop")

    @Suppress("UNCHECKED_CAST")
    private fun parseServerEntry(name: String, cfg: Map<String, Any>, source: String): DiscoveredServer? {
        // HTTP-сервер: есть поле "url"
        val url = cfg["url"]?.toString()
        if (url != null) {
            return DiscoveredServer(
                name = name,
                config = McpServerConfig(name = name, type = McpTransportType.HTTP, url = url),
                source = source
            )
        }

        // STDIO-сервер: обязательно command
        val command = cfg["command"]?.toString() ?: return null
        val args = when (val a = cfg["args"]) {
            is List<*> -> a.map { it.toString() }
            else -> emptyList()
        }
        val env = (cfg["env"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value.toString() }
            ?: emptyMap()

        return DiscoveredServer(
            name = name,
            config = McpServerConfig(
                name = name,
                type = McpTransportType.STDIO,
                command = command,
                args = args,
                env = env
            ),
            source = source
        )
    }
}
