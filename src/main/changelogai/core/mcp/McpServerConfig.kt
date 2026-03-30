package changelogai.core.mcp

data class McpServerConfig(
    val name: String,
    val type: McpTransportType,
    // STDIO
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    // HTTP/SSE
    val url: String? = null,
    // Auth (Jira / Confluence / любой HTTP-сервер с токеном)
    val accessToken: String? = null,
    val certificate: String? = null,      // путь к .pem или base64-строка CA-сертификата
    val skipCertVerify: Boolean = false
)

enum class McpTransportType { STDIO, HTTP }
