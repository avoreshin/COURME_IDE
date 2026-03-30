package changelogai.core.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Конвертация между JSON-форматом (.mcp.json) и McpEntry/McpState.
 *
 * JSON-формат совместим с Claude Code / Claude Desktop / Cursor:
 * ```json
 * {
 *   "mcpServers": {
 *     "server-name": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server"],
 *       "env": { "KEY": "value" }
 *     }
 *   }
 * }
 * ```
 * Для HTTP-серверов поддерживаются расширения: `preset`, `accessToken`, `certificate`, `skipCertVerification`.
 */
object McpJsonSync {

    private val mapper = jacksonObjectMapper()

    val TEMPLATE = """
{
  "mcpServers": {
    "my-jira": {
      "url": "https://company.atlassian.net",
      "accessToken": "YOUR_API_TOKEN",
      "preset": "jira"
    },
    "my-confluence": {
      "url": "https://company.atlassian.net",
      "accessToken": "YOUR_API_TOKEN",
      "preset": "confluence"
    },
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
      "env": {}
    }
  }
}
""".trim()

    /** Парсит JSON-строку в список McpEntry. */
    @Suppress("UNCHECKED_CAST")
    fun fromJson(json: String): List<McpEntry> {
        if (json.isBlank()) return emptyList()
        return try {
            val root: Map<String, Any> = mapper.readValue(json, Map::class.java) as Map<String, Any>
            val servers = root["mcpServers"] as? Map<*, *> ?: return emptyList()
            servers.mapNotNull { (key, value) ->
                val name = key?.toString() ?: return@mapNotNull null
                val cfg  = value as? Map<*, *> ?: return@mapNotNull null
                McpEntry().apply {
                    this.name    = name
                    this.enabled = cfg["disabled"] as? Boolean != true
                    if (cfg["url"] != null) {
                        this.type          = "HTTP"
                        this.url           = cfg["url"].toString()
                        this.accessToken   = cfg["accessToken"]?.toString() ?: ""
                        this.preset        = cfg["preset"]?.toString() ?: autoDetectPreset(name, cfg["url"].toString())
                        this.certificate   = cfg["certificate"]?.toString() ?: ""
                        this.skipCertVerify = cfg["skipCertVerification"] as? Boolean ?: false
                    } else {
                        this.type    = "STDIO"
                        this.command = cfg["command"]?.toString() ?: ""
                        this.args    = (cfg["args"] as? List<*>)
                            ?.joinToString(" ") { it.toString() } ?: ""
                        val envMap = cfg["env"] as? Map<*, *>
                        this.envJson = if (envMap != null) mapper.writeValueAsString(envMap) else "{}"
                        this.preset  = cfg["preset"]?.toString() ?: autoDetectPresetFromEnv(cfg)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Сериализует список McpEntry в JSON-строку. */
    @Suppress("UNCHECKED_CAST")
    fun toJson(servers: List<McpEntry>): String {
        val mcpServers = LinkedHashMap<String, Any>()
        servers.forEach { e ->
            val cfg = LinkedHashMap<String, Any>()
            if (e.type == "HTTP") {
                if (e.url.isNotBlank())           cfg["url"]                 = e.url
                if (e.accessToken.isNotBlank())   cfg["accessToken"]         = e.accessToken
                if (e.preset.isNotBlank())        cfg["preset"]              = e.preset
                if (e.certificate.isNotBlank())   cfg["certificate"]         = e.certificate
                if (e.skipCertVerify)             cfg["skipCertVerification"] = true
            } else {
                if (e.command.isNotBlank()) cfg["command"] = e.command
                val argsList = e.args.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                if (argsList.isNotEmpty()) cfg["args"] = argsList
                val envMap = parseEnvJson(e.envJson)
                if (envMap.isNotEmpty()) cfg["env"] = envMap
                if (e.preset.isNotBlank()) cfg["preset"] = e.preset
            }
            if (!e.enabled) cfg["disabled"] = true
            mcpServers[e.name] = cfg
        }
        return mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(mapOf("mcpServers" to mcpServers))
    }

    /**
     * Загружает JSON из файла, обновляет McpState.servers.
     * Вызывается при сохранении из панели или при старте IDE.
     */
    fun syncFromFile(file: File) {
        if (!file.exists()) return
        val entries = fromJson(file.readText(Charsets.UTF_8))
        val state = McpState.getInstance()
        state.servers.clear()
        state.servers.addAll(entries)
    }

    /**
     * Загружает JSON для отображения в панели:
     * - если файл существует — читаем из файла
     * - иначе — сериализуем текущий McpState
     */
    fun loadForPanel(file: File): String {
        if (file.exists()) {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isNotBlank()) return content
        }
        val servers = McpState.getInstance().servers
        return if (servers.isNotEmpty()) toJson(servers) else TEMPLATE
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseEnvJson(envJson: String): Map<String, String> {
        if (envJson.isBlank() || envJson == "{}") return emptyMap()
        return try {
            (mapper.readValue(envJson, Map::class.java) as Map<*, *>)
                .entries.associate { it.key.toString() to it.value.toString() }
        } catch (_: Exception) { emptyMap() }
    }

    /** Авто-определяет preset по имени сервера или URL */
    private fun autoDetectPreset(name: String, url: String): String {
        val lower = name.lowercase()
        return when {
            "jira" in lower -> "jira"
            "confluence" in lower -> "confluence"
            "atlassian" in url.lowercase() -> "jira"
            else -> ""
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun autoDetectPresetFromEnv(cfg: Map<*, *>): String {
        val env = cfg["env"] as? Map<*, *> ?: return ""
        val keys = env.keys.map { it.toString() }
        return when {
            "JIRA_URL" in keys || keys.any { "JIRA" in it } -> "jira"
            "CONFLUENCE_URL" in keys -> "confluence"
            "ATLASSIAN_URL" in keys || "ATLASSIAN_API_TOKEN" in keys -> "jira"
            else -> ""
        }
    }
}
