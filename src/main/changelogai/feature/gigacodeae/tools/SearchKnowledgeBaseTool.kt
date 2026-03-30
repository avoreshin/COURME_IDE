package changelogai.feature.gigacodeae.tools

import com.intellij.openapi.project.Project
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema
import changelogai.feature.kb.KnowledgeBaseService

class SearchKnowledgeBaseTool : BuiltinTool {
    override val name = "search_knowledge_base"
    override val description = "Семантический поиск по базе знаний проекта (Confluence). Возвращает релевантные фрагменты из проиндексированных страниц."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to PropertySchema("string", "Поисковый запрос на естественном языке"),
            "top_k" to PropertySchema("integer", "Количество результатов (по умолчанию 5)")
        ),
        required = listOf("query")
    )

    override fun execute(project: Project, arguments: Map<String, Any>): ToolResult {
        val query = arguments["query"]?.toString()
            ?: return ToolResult.Error("Параметр 'query' обязателен")
        val topK = (arguments["top_k"] as? Number)?.toInt() ?: 5

        val kbService = KnowledgeBaseService.getInstance(project)
        if (!kbService.isIndexed()) {
            return ToolResult.Error("База знаний не проиндексирована. Настройте индексацию во вкладке Knowledge Base.")
        }

        return try {
            val results = kbService.search(query, topK)
            if (results.isEmpty()) {
                ToolResult.Ok("Релевантных результатов не найдено")
            } else {
                val formatted = results.mapIndexed { i, r ->
                    buildString {
                        appendLine("--- Результат ${i + 1} (${(r.score * 100).toInt()}%) ---")
                        appendLine("Страница: ${r.pageTitle}")
                        if (r.heading.isNotBlank()) appendLine("Раздел: ${r.heading}")
                        appendLine(r.chunkText)
                    }
                }.joinToString("\n")
                ToolResult.Ok(formatted)
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка поиска в KB: ${e.message}")
        }
    }
}
