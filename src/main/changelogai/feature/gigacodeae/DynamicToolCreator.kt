package changelogai.feature.gigacodeae

import com.intellij.openapi.project.Project
import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.feature.gigacodeae.tools.RunTerminalTool
import changelogai.feature.gigacodeae.tools.ToolResult
import changelogai.platform.LLMClientFactory
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Динамически реализует неизвестный инструмент через LLM + терминал.
 *
 * Когда LLM вызывает функцию которой нет в зарегистрированных инструментах:
 * 1. Делаем отдельный LLM-запрос: "напиши shell-команду для реализации <name>(<args>)"
 * 2. Показываем пользователю сгенерированную команду и просим подтверждения
 * 3. Выполняем через RunTerminalTool и возвращаем результат
 */
class DynamicToolCreator(private val project: Project) {

    private val terminal = RunTerminalTool()

    fun execute(
        toolName: String,
        arguments: Map<String, Any>,
        cancelable: AtomicCancelable
    ): ToolResult {
        val state = PluginState.getInstance()

        // Формируем запрос к LLM для генерации команды
        val argsDesc = if (arguments.isEmpty()) "без аргументов"
        else arguments.entries.joinToString(", ") { (k, v) -> "$k=$v" }

        val prompt = """
            Реализуй функцию "$toolName" с аргументами: $argsDesc.
            Напиши ОДНУ shell-команду (bash/sh) которая выполняет это действие.
            Допустимо использовать: grep, find, awk, sed, python3, node, curl, git и другие стандартные утилиты.
            Ответ должен содержать ТОЛЬКО команду — без пояснений, без markdown, без комментариев.
        """.trimIndent()

        // Генерируем команду отдельным LLM-запросом
        val command = try {
            val request = ChatRequest(
                model = state.aiModel,
                temperature = 0.1,
                maxTokens = 256,
                messages = listOf(ChatMessage(role = "user", content = prompt))
            )
            LLMClientFactory.create(state, cancelable).use { client ->
                client.postChatCompletions(request)
                    .choices.firstOrNull()?.message?.content?.trim() ?: ""
            }
        } catch (e: Exception) {
            return ToolResult.Error("Не удалось сгенерировать команду для «$toolName»: ${e.message}")
        }

        if (command.isBlank()) {
            return ToolResult.Error("LLM не вернул команду для «$toolName»")
        }

        // Обязательное подтверждение перед выполнением сгенерированного кода
        var approved = false
        SwingUtilities.invokeAndWait {
            val choice = JOptionPane.showConfirmDialog(
                null,
                "GigaCodeAE создал команду для «$toolName» ($argsDesc):\n\n" +
                "  $command\n\n" +
                "Выполнить эту команду в терминале?",
                "Выполнить сгенерированную команду?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            approved = (choice == JOptionPane.YES_OPTION)
        }
        if (!approved) return ToolResult.Denied

        return terminal.execute(project, mapOf(
            "command" to command,
            "timeout_seconds" to 60
        ))
    }
}
