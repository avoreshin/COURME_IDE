package changelogai.feature.gigacodeae.tools

sealed class ToolResult {
    data class Ok(val content: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
    /** Пользователь отклонил подтверждение */
    object Denied : ToolResult()
}
