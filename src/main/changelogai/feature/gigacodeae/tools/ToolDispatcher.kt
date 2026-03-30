package changelogai.feature.gigacodeae.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project
import changelogai.core.llm.model.FunctionCall
import changelogai.core.llm.model.FunctionDefinition

class ToolDispatcher(
    private val project: Project,
    private val onConfirm: ((toolName: String, args: Map<String, Any>) -> Boolean)? = null
) {

    private val tools: Map<String, BuiltinTool> = listOf(
        ReadFileTool(),
        WriteFileTool(),
        ListFilesTool(),
        RunTerminalTool(),
        SearchInFilesTool(),
        SearchKnowledgeBaseTool()
    ).associateBy { it.name }

    private val mapper = jacksonObjectMapper()

    fun getFunctionDefinitions(): List<FunctionDefinition> =
        tools.values.map { it.toFunctionDefinition() }

    /**
     * Выполняет вызов инструмента.
     * Если инструмент требует подтверждения — показывает диалог в EDT.
     * Должен вызываться из фонового потока (не EDT).
     */
    fun dispatch(call: FunctionCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult.Error("Неизвестный инструмент: ${call.name}")

        val args: Map<String, Any> = try {
            mapper.readValue(call.arguments)
        } catch (e: Exception) {
            return ToolResult.Error("Неверные аргументы JSON: ${e.message}")
        }

        if (tool.requiresConfirmation) {
            val approved = onConfirm?.invoke(tool.name, args) ?: false
            if (!approved) return ToolResult.Denied
        }

        return tool.execute(project, args)
    }
}
