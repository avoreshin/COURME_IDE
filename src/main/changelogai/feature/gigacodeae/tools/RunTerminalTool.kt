package changelogai.feature.gigacodeae.tools

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import changelogai.core.llm.model.FunctionParameters
import changelogai.core.llm.model.PropertySchema
import java.io.File
import java.nio.charset.Charset

class RunTerminalTool : BuiltinTool {
    override val name = "run_terminal"
    override val description = "Выполняет команду в терминале в корне проекта и возвращает вывод"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "command" to PropertySchema("string", "Shell-команда для выполнения"),
            "timeout_seconds" to PropertySchema("integer", "Таймаут в секундах (по умолчанию 30)")
        ),
        required = listOf("command")
    )
    override val requiresConfirmation = true

    override fun execute(project: Project, arguments: Map<String, Any>): ToolResult {
        val command = arguments["command"]?.toString()
            ?: return ToolResult.Error("Параметр 'command' обязателен")
        val timeout = (arguments["timeout_seconds"]?.toString()?.toIntOrNull() ?: 30) * 1000L
        val basePath = project.basePath
            ?: return ToolResult.Error("Проект не имеет корневой директории")

        return try {
            val cmdLine = GeneralCommandLine()
                .withExePath(if (isWindows()) "cmd.exe" else "/bin/sh")
                .withParameters(if (isWindows()) listOf("/c", command) else listOf("-c", command))
                .withWorkDirectory(File(basePath))
                .withCharset(Charset.forName("UTF-8"))

            val output = StringBuilder()
            val handler = OSProcessHandler(cmdLine)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (output.length < 50_000) output.append(event.text)
                }
            })
            handler.startNotify()
            val finished = handler.waitFor(timeout)
            if (!finished) {
                handler.destroyProcess()
                return ToolResult.Error("Команда превысила таймаут ${timeout / 1000}с")
            }
            val exitCode = handler.exitCode ?: 0
            val result = output.toString().trimEnd()
            if (exitCode != 0) {
                ToolResult.Ok("Exit code: $exitCode\n$result")
            } else {
                ToolResult.Ok(result.ifEmpty { "(нет вывода)" })
            }
        } catch (e: Exception) {
            ToolResult.Error("Ошибка выполнения команды: ${e.message}")
        }
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")
}
