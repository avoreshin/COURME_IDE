package changelogai.feature.gigacodeae.mcp

import changelogai.core.llm.model.FunctionDefinition
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema

/**
 * Конвертирует MCP tool info в FunctionDefinition для GigaChat function calling.
 */
object McpToolAdapter {

    fun toFunctionDefinition(tool: McpToolInfo): FunctionDefinition {
        val props = extractProperties(tool.inputSchema)
        val required = extractRequired(tool.inputSchema)
        return FunctionDefinition(
            name = sanitizeName(tool.name),
            description = tool.description.take(256),
            parameters = FunctionParameters(properties = props, required = required)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractProperties(schema: Map<String, Any>): Map<String, PropertySchema> {
        val props = schema["properties"] as? Map<String, Any> ?: return emptyMap()
        return props.mapNotNull { (key, value) ->
            if (value is Map<*, *>) {
                key to PropertySchema(
                    type = value["type"]?.toString() ?: "string",
                    description = value["description"]?.toString() ?: key
                )
            } else null
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRequired(schema: Map<String, Any>): List<String> =
        (schema["required"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    // GigaChat не принимает __ и спецсимволы в именах функций — заменяем
    private fun sanitizeName(name: String) =
        name.replace(Regex("[^a-zA-Z0-9_]"), "_").take(64)
}
