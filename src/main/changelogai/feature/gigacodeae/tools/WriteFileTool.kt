package changelogai.feature.gigacodeae.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema
import java.io.File

class WriteFileTool : BuiltinTool {
    override val name = "write_file"
    override val description = "Записывает содержимое в файл проекта (создаёт если не существует)"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to PropertySchema("string", "Относительный путь к файлу от корня проекта"),
            "content" to PropertySchema("string", "Новое содержимое файла")
        ),
        required = listOf("path", "content")
    )
    override val requiresConfirmation = true

    override fun execute(project: Project, arguments: Map<String, Any>): ToolResult {
        val path = arguments["path"]?.toString()
            ?: return ToolResult.Error("Параметр 'path' обязателен")
        val content = (arguments["content"]?.toString()
            ?: return ToolResult.Error("Параметр 'content' обязателен"))
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "")
            .stripCodeFence()
        val basePath = project.basePath
            ?: return ToolResult.Error("Проект не имеет корневой директории")

        return try {
            var result: ToolResult = ToolResult.Error("Запись не выполнена")
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    val file = File(basePath, path)
                    file.parentFile?.mkdirs()
                    val vParent = VfsUtil.createDirectoryIfMissing(file.parent)
                        ?: run { result = ToolResult.Error("Не удалось создать директорию"); return@runWriteCommandAction }
                    val vFile = vParent.findOrCreateChildData(this, file.name)
                    VfsUtil.saveText(vFile, content)
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                    result = ToolResult.Ok("Файл записан: $path")
                }
            }
            result
        } catch (e: Exception) {
            ToolResult.Error("Ошибка записи: ${e.message}")
        }
    }
}

/**
 * Убирает обёртку ```lang ... ``` если LLM вернул контент в markdown code fence.
 */
private fun String.stripCodeFence(): String {
    val trimmed = this.trim()
    val firstNewline = trimmed.indexOf('\n')
    if (firstNewline < 0) return this
    val firstLine = trimmed.substring(0, firstNewline).trim()
    if (!firstLine.startsWith("```")) return this
    val lastFence = trimmed.lastIndexOf("```")
    if (lastFence <= firstNewline) return this
    return trimmed.substring(firstNewline + 1, lastFence).trimEnd()
}
