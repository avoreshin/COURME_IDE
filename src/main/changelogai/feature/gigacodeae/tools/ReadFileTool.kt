package changelogai.feature.gigacodeae.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema
import java.io.File

class ReadFileTool : BuiltinTool {
    override val name = "read_file"
    override val description = "Читает содержимое файла из проекта по относительному пути"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to PropertySchema("string", "Относительный путь к файлу от корня проекта")
        ),
        required = listOf("path")
    )

    override fun execute(project: Project, arguments: Map<String, Any>): ToolResult {
        val path = arguments["path"]?.toString()
            ?: return ToolResult.Error("Параметр 'path' обязателен")
        val basePath = project.basePath
            ?: return ToolResult.Error("Проект не имеет корневой директории")
        val file = File(basePath, path)
        if (!file.exists()) return ToolResult.Error("Файл не найден: $path")
        if (!file.isFile) return ToolResult.Error("Путь указывает на директорию: $path")
        return try {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                ?: return ToolResult.Error("VFS не смог найти файл: $path")
            ToolResult.Ok(VfsUtilCore.loadText(vFile))
        } catch (e: Exception) {
            ToolResult.Error("Ошибка чтения файла: ${e.message}")
        }
    }
}
