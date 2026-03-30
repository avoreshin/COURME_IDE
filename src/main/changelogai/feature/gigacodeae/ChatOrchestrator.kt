package changelogai.feature.gigacodeae

import com.intellij.openapi.project.Project
import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.llm.model.FunctionCall
import changelogai.core.llm.model.FunctionDefinition
import changelogai.core.settings.PluginState
import changelogai.feature.gigacodeae.tools.ToolDispatcher
import changelogai.feature.gigacodeae.tools.ToolResult
import changelogai.feature.kb.KnowledgeBaseService
import changelogai.feature.kb.model.KBSearchResult
import changelogai.platform.LLMClientFactory

/**
 * Agentic loop: отправляет сообщение, обрабатывает tool calls (до MAX_ITERATIONS итераций),
 * возвращает финальный ответ ассистента.
 *
 * Выполняется строго в фоновом потоке (Task.Backgroundable).
 */
class ChatOrchestrator(
    private val project: Project,
    private val dispatcher: ToolDispatcher
) {
    private val dynamicToolCreator = DynamicToolCreator(project)
    companion object {
        private const val MAX_ITERATIONS = 10
    }

    fun sendMessage(
        history: List<ChatMessage>,
        userText: String,
        extraFunctions: List<FunctionDefinition>,
        cancelable: AtomicCancelable,
        onToolCallStarted: (FunctionCall) -> Unit,
        onToolCallResult: (FunctionCall, ToolResult) -> Unit,
        onAssistantMessage: (String) -> Unit,
        onDone: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit,
        // опциональный dispatch для MCP-инструментов
        mcpDispatch: ((name: String, args: Map<String, Any>) -> String)? = null,
        systemPrompt: String? = null
    ) {
        val state = PluginState.getInstance()

        // Обогащаем system prompt контекстом из Базы знаний (если проиндексирована)
        val enrichedSystemPrompt = enrichWithKB(systemPrompt, userText)

        val systemOffset = if (enrichedSystemPrompt != null) 1 else 0
        val messages = mutableListOf<ChatMessage>()
        if (enrichedSystemPrompt != null) messages.add(ChatMessage(role = "system", content = enrichedSystemPrompt))
        messages.addAll(history)
        messages.add(ChatMessage(role = "user", content = userText))
        val accumulatedText = StringBuilder()   // накапливаем части при finish_reason=length

        val allFunctions = (dispatcher.getFunctionDefinitions() + extraFunctions)
            .takeIf { it.isNotEmpty() }

        try {
            LLMClientFactory.create(state, cancelable).use { client ->
                repeat(MAX_ITERATIONS) { iteration ->
                    cancelable.checkCanceled()

                    val request = ChatRequest(
                        model = state.aiModel,
                        temperature = state.temperature.value,
                        maxTokens = state.commitSize.value,
                        messages = messages.toList(),
                        functions = allFunctions,
                        functionCall = allFunctions?.let { "auto" }
                    )

                    val response = client.postChatCompletions(request)
                    val choice = response.choices.firstOrNull()
                        ?: run { onError("LLM вернул пустой ответ"); return }

                    val assistantMsg = choice.message
                        ?: run { onError("Ответ не содержит сообщения"); return }
                    messages.add(assistantMsg)

                    val finishReason = choice.finishReason

                    // Накапливаем текст ответа ассистента
                    assistantMsg.content?.let { accumulatedText.append(it) }

                    // LLM хочет вызвать инструмент
                    if (finishReason == "function_call" && assistantMsg.functionCall != null) {
                        val call = assistantMsg.functionCall
                        onToolCallStarted(call)

                        val isBuiltin = dispatcher.getFunctionDefinitions().any { it.name == call.name }

                        @Suppress("UNCHECKED_CAST")
                        val parsedArgs: Map<String, Any> = try {
                            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                                .readValue(call.arguments, Map::class.java) as Map<String, Any>
                        } catch (_: Exception) { emptyMap() }

                        val result = when {
                            // Встроенный инструмент
                            isBuiltin -> dispatcher.dispatch(call)
                            // MCP-инструмент
                            mcpDispatch != null -> ToolResult.Ok(mcpDispatch(call.name, parsedArgs))
                            // Неизвестный инструмент → LLM генерирует и выполняет команду
                            else -> dynamicToolCreator.execute(call.name, parsedArgs, cancelable)
                        }
                        onToolCallResult(call, result)

                        val toolText = when (result) {
                            is ToolResult.Ok -> result.content
                            is ToolResult.Error -> "ERROR: ${result.message}"
                            ToolResult.Denied -> "Пользователь отклонил выполнение инструмента"
                        }
                        // GigaChat требует, чтобы content role=function был валидным JSON
                        val toolContent = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                            .writeValueAsString(mapOf("result" to toolText))
                        // добавляем ответ инструмента в историю
                        messages.add(
                            ChatMessage(role = "function", name = call.name, content = toolContent)
                        )
                        return@repeat // следующая итерация → отправляем обновлённую историю
                    }

                    // Ответ обрезан по токенам — автоматически просим продолжить
                    if (finishReason == "length") {
                        messages.add(ChatMessage(role = "user", content = "Продолжи с того места где остановился, без повторений."))
                        return@repeat
                    }

                    // Финальный ответ (возможно накопленный из нескольких частей)
                    val text = accumulatedText.toString().ifEmpty { assistantMsg.content ?: "" }
                    onAssistantMessage(text)
                    onDone(messages.drop(systemOffset))
                    return
                }
                onError("Превышено максимальное количество вызовов инструментов ($MAX_ITERATIONS)")
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            // отмена — молча
        } catch (e: Exception) {
            onError(e.message ?: "Неизвестная ошибка")
        }
    }

    private fun enrichWithKB(systemPrompt: String?, userText: String): String? {
        val kbBlock = try {
            val kbService = KnowledgeBaseService.getInstance(project)
            if (!kbService.isIndexed()) null
            else {
                val results = kbService.search(userText, topK = 3)
                if (results.isEmpty()) null
                else buildString {
                    appendLine("\n\n## Релевантный контекст из Базы знаний")
                    for (r in results) {
                        appendLine("### ${r.pageTitle} (${(r.score * 100).toInt()}%)")
                        appendLine(r.chunkText)
                        appendLine()
                    }
                }
            }
        } catch (_: Exception) { null }

        return when {
            systemPrompt != null && kbBlock != null -> systemPrompt + kbBlock
            systemPrompt != null -> systemPrompt
            kbBlock != null -> kbBlock
            else -> null
        }
    }
}
