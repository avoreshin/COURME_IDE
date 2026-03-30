package changelogai.feature.gigacodeae.tools

import com.intellij.openapi.project.Project
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema
import java.io.File

class ListFilesTool : BuiltinTool {
    override val name = "list_files"
    override val description = "Возвращает список файлов в директории проекта с опциональным glob-фильтром"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "dir" to PropertySchema("string", "Относительный путь к директории (по умолчанию — корень проекта)"),
            "pattern" to PropertySchema("string", "Glob-паттерн для фильтрации (напр. *.kt, **/*.xml)")
        )
    )

    override fun execute(project: Project, arguments: Map<String, Any>): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult.Error("Проект не имеет корневой директории")
        val dir = arguments["dir"]?.toString() ?: ""
        val pattern = arguments["pattern"]?.toString()

        val root = File(basePath, dir)
        if (!root.exists() || !root.isDirectory) return ToolResult.Error("Директория не найдена: $dir")

        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(File(basePath)).path }
            .filter { path -> pattern == null || matchesGlob(path, pattern) }
            .sorted()
            .take(500)
            .toList()

        return ToolResult.Ok(files.joinToString("\n").ifEmpty { "(директория пуста)" })
    }

    private fun matchesGlob(path: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("**", "\u0000")
            .replace("*", "[^/]*")
            .replace("\u0000", ".*")
        return Regex(regex).containsMatchIn(path)
    }
}
