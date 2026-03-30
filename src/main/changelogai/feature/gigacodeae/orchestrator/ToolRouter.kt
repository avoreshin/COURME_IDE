package changelogai.feature.gigacodeae.orchestrator

import changelogai.core.llm.model.FunctionCall
import changelogai.core.llm.model.FunctionDefinition
import changelogai.feature.gigacodeae.tools.ToolDispatcher
import changelogai.feature.gigacodeae.tools.ToolResult

/**
 * Селективная загрузка инструментов.
 * Каждый агент получает только те tools, которые ему нужны.
 */
class ToolRouter(
    private val dispatcher: ToolDispatcher,
    private val mcpFunctions: () -> List<FunctionDefinition>,
    private val mcpDispatch: ((name: String, args: Map<String, Any>) -> String)? = null
) {

    private val builtinDefs: List<FunctionDefinition> by lazy { dispatcher.getFunctionDefinitions() }
    private val builtinNames: Set<String> by lazy { builtinDefs.map { it.name }.toSet() }

    /**
     * Возвращает только инструменты, разрешённые для данного агента.
     */
    fun getToolsForAgent(agent: ChatAgent): List<FunctionDefinition> {
        val allowed = agent.allowedTools
        if (allowed.isEmpty()) return emptyList()

        // "mcp:*" — всё: builtin + MCP
        if ("mcp:*" in allowed) {
            return builtinDefs + mcpFunctions()
        }

        val builtin = builtinDefs.filter { it.name in allowed }
        val mcp = mcpFunctions().filter { it.name in allowed }
        return builtin + mcp
    }

    /**
     * Диспатчит вызов инструмента (builtin или MCP).
     */
    fun dispatch(call: FunctionCall): ToolResult {
        // Builtin
        if (call.name in builtinNames) {
            return dispatcher.dispatch(call)
        }
        // MCP
        val mcpFn = mcpDispatch ?: return ToolResult.Error("MCP dispatch не настроен")
        val parsedArgs: Map<String, Any> = try {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readValue(call.arguments, Map::class.java) as Map<String, Any>
        } catch (_: Exception) {
            emptyMap()
        }
        return ToolResult.Ok(mcpFn(call.name, parsedArgs))
    }
}
