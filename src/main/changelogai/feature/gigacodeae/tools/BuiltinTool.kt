package changelogai.feature.gigacodeae.tools

import com.intellij.openapi.project.Project
import changelogai.core.llm.model.FunctionDefinition
import changelogai.core.llm.model.FunctionParameters

interface BuiltinTool {
    val name: String
    val description: String
    val parameters: FunctionParameters
    val requiresConfirmation: Boolean get() = false

    fun toFunctionDefinition() = FunctionDefinition(name, description, parameters)

    fun execute(project: Project, arguments: Map<String, Any>): ToolResult
}
