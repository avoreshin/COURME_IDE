package changelogai.feature.gigacodeae.tools

import com.intellij.openapi.project.Project
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema
import java.io.File

class SearchInFilesTool : BuiltinTool {
    override val name = "search_in_files"
    override val description = "Ищет текст (или регулярное выражение) по содержимому файлов проекта"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to PropertySchema("string", "Строка поиска или регулярное выражение"),
            "file_pattern" to PropertySchema("string", "Glob-паттерн файлов для поиска (напр. *.kt)"),
            "is_regex" to PropertySchema("boolean", "true — query является регулярным выражением")
        ),
        required = listOf("query")
    )

    override fun execute(project: Project, arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"]?.toString()
            ?: return ToolResult.Error("Параметр 'query' обязателен")
        val filePattern = arguments["file_pattern"]?.toString()
        val isRegex = arguments["is_regex"]?.toString()?.toBoolean() ?: false
        val basePath = project.basePath
            ?: return ToolResult.Error("Проект не имеет корневой директории")

        val regex = if (isRegex) Regex(query) else Regex(Regex.escape(query))
        val results = mutableListOf<String>()

        val baseDir = File(basePath)
        outer@ for (file in baseDir.walkTopDown()) {
            if (!file.isFile) continue
            if (filePattern != null && !matchesGlob(file.relativeTo(baseDir).path, filePattern)) continue
            try {
                file.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) {
                            results.add("${file.relativeTo(baseDir).path}:${idx + 1}: $line")
                        }
                    }
                }
            } catch (_: Exception) { /* бинарный файл — пропускаем */ }
            if (results.size >= 200) break@outer
        }

        return ToolResult.Ok(
            if (results.isEmpty()) "Совпадений не найдено"
            else results.joinToString("\n")
        )
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
