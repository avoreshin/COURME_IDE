package changelogai.core.mcp

/**
 * Описание одного MCP-сервера. Хранится в McpState.
 * Должен быть plain class с var-полями и no-arg конструктором (требование JDOM-сериализации).
 */
class McpEntry {
    var name: String = ""
    var enabled: Boolean = true
    var type: String = "STDIO"   // "STDIO" | "HTTP"
    // STDIO
    var command: String = ""
    var args: String = ""        // пробел-разделённые аргументы
    var envJson: String = "{}"   // JSON-объект {"KEY":"VALUE"}
    // HTTP
    var url: String = ""
    // Atlassian preset (Jira / Confluence)
    var preset: String = ""          // "jira" | "confluence" | "" (generic)
    var accessToken: String = ""
    var certificate: String = ""     // путь к .pem / base64
    var skipCertVerify: Boolean = false

    fun toServerConfig(): McpServerConfig {
        val env = parseEnv().toMutableMap()
        return McpServerConfig(
            name = name,
            type = if (type == "HTTP") McpTransportType.HTTP else McpTransportType.STDIO,
            command = command.takeIf { it.isNotBlank() },
            args = if (args.isBlank()) emptyList() else args.trim().split("\\s+".toRegex()),
            env = env,
            url = url.takeIf { it.isNotBlank() },
            accessToken = accessToken.takeIf { it.isNotBlank() },
            certificate = certificate.takeIf { it.isNotBlank() },
            skipCertVerify = skipCertVerify
        )
    }

    private fun parseEnv(): Map<String, String> = try {
        @Suppress("UNCHECKED_CAST")
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readValue(envJson, Map::class.java)
            .entries.associate { it.key.toString() to it.value.toString() }
    } catch (_: Exception) { emptyMap() }

    fun copyFields(): McpEntry = McpEntry().also {
        it.name = name; it.enabled = enabled; it.type = type
        it.command = command; it.args = args; it.envJson = envJson; it.url = url
        it.preset = preset; it.accessToken = accessToken
        it.certificate = certificate; it.skipCertVerify = skipCertVerify
    }
}
