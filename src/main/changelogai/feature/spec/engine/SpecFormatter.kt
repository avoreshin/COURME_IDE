package changelogai.feature.spec.engine

import changelogai.feature.spec.model.SpecDocument
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class SpecFormatter {

    fun toMarkdown(spec: SpecDocument): String {
        val sb = StringBuilder()
        sb.appendLine("# ${spec.title}")
        sb.appendLine()

        if (spec.functional.isNotEmpty()) {
            sb.appendLine("## Функциональные требования")
            sb.appendLine()
            sb.appendLine("| ID | Описание | Приоритет |")
            sb.appendLine("|----|----------|-----------|")
            spec.functional.forEach { sb.appendLine("| ${it.id} | ${it.description} | ${it.priority} |") }
            sb.appendLine()
        }

        if (spec.nonFunctional.isNotEmpty()) {
            sb.appendLine("## Нефункциональные требования")
            sb.appendLine()
            sb.appendLine("| ID | Описание | Приоритет |")
            sb.appendLine("|----|----------|-----------|")
            spec.nonFunctional.forEach { sb.appendLine("| ${it.id} | ${it.description} | ${it.priority} |") }
            sb.appendLine()
        }

        if (spec.acceptanceCriteria.isNotEmpty()) {
            sb.appendLine("## Acceptance Criteria")
            sb.appendLine()
            spec.acceptanceCriteria.forEach {
                sb.appendLine("**${it.id}:** ${it.description}")
                sb.appendLine()
            }
        }

        if (spec.edgeCases.isNotEmpty()) {
            sb.appendLine("## Edge Cases")
            sb.appendLine()
            sb.appendLine("| ID | Описание | Приоритет |")
            sb.appendLine("|----|----------|-----------|")
            spec.edgeCases.forEach { sb.appendLine("| ${it.id} | ${it.description} | ${it.priority} |") }
        }

        return sb.toString().trimEnd()
    }

    fun toHtml(spec: SpecDocument): String {
        val md = toMarkdown(spec)
        // Простая конвертация MD → HTML
        return "<html><body><pre>${md.replace("<", "&lt;").replace(">", "&gt;")}</pre></body></html>"
    }

    fun toJson(spec: SpecDocument): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"title\": \"${spec.title.escape()}\",")
        sb.appendLine("  \"functional\": [")
        spec.functional.forEachIndexed { i, r ->
            val comma = if (i < spec.functional.lastIndex) "," else ""
            sb.appendLine("    {\"id\": \"${r.id}\", \"description\": \"${r.description.escape()}\", \"priority\": \"${r.priority}\"}$comma")
        }
        sb.appendLine("  ],")
        sb.appendLine("  \"nonFunctional\": [")
        spec.nonFunctional.forEachIndexed { i, r ->
            val comma = if (i < spec.nonFunctional.lastIndex) "," else ""
            sb.appendLine("    {\"id\": \"${r.id}\", \"description\": \"${r.description.escape()}\", \"priority\": \"${r.priority}\"}$comma")
        }
        sb.appendLine("  ],")
        sb.appendLine("  \"acceptanceCriteria\": [")
        spec.acceptanceCriteria.forEachIndexed { i, r ->
            val comma = if (i < spec.acceptanceCriteria.lastIndex) "," else ""
            sb.appendLine("    {\"id\": \"${r.id}\", \"description\": \"${r.description.escape()}\"}$comma")
        }
        sb.appendLine("  ],")
        sb.appendLine("  \"edgeCases\": [")
        spec.edgeCases.forEachIndexed { i, r ->
            val comma = if (i < spec.edgeCases.lastIndex) "," else ""
            sb.appendLine("    {\"id\": \"${r.id}\", \"description\": \"${r.description.escape()}\", \"priority\": \"${r.priority}\"}$comma")
        }
        sb.appendLine("  ]")
        sb.append("}")
        return sb.toString()
    }

    fun toClipboard(spec: SpecDocument) {
        CopyPasteManager.getInstance().setContents(StringSelection(toMarkdown(spec)))
    }

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
