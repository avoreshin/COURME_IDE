package changelogai.feature.gigacodeae.orchestrator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.diagnostic.Logger
import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.llm.model.FunctionCall
import changelogai.core.settings.PluginState
import changelogai.feature.gigacodeae.tools.ToolResult
import changelogai.platform.LLMClientFactory

/**
 * Базовый класс суб-агента с мини agentic loop.
 *
 * В отличие от [changelogai.feature.spec.agents.SpecAgent], поддерживает
 * function calling — у каждого агента свой набор разрешённых инструментов
 * и ограниченное число итераций.
 */
abstract class ChatAgent(val name: String) {

    abstract val systemPrompt: String

    /** Имена инструментов, доступных этому агенту. Пустой набор = без инструментов. */
    abstract val allowedTools: Set<String>

    open fun temperature(): Double = 0.3
    open fun maxTokens(): Int = 3000
    open fun maxIterations(): Int = 5

    private val mapper = jacksonObjectMapper()
    private val log = Logger.getInstance(ChatAgent::class.java)

    /**
     * Запускает агента с минимальным контекстом.
     * Если у агента есть tools — запускает мини agentic loop (до [maxIterations]).
     * Если нет tools — один вызов LLM.
     *
     * @param context минимальный контекст (task + summary + recent + tools)
     * @param cancelable для отмены
     * @param onToolDispatch колбэк для вызова builtin/MCP инструментов
     * @param onToolCallStarted уведомление UI о начале вызова tool
     * @param onToolCallResult уведомление UI о результате tool
     */
    fun run(
        context: AgentContext,
        cancelable: AtomicCancelable,
        onToolDispatch: ((FunctionCall) -> ToolResult)? = null,
        onToolCallStarted: ((FunctionCall) -> Unit)? = null,
        onToolCallResult: ((FunctionCall, ToolResult) -> Unit)? = null
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        log.info("[$name] START | tools=${context.availableTools.map { it.name }} | maxIter=${maxIterations()} | task=${context.task.take(100)}")

        val state = PluginState.getInstance()
        val messages = buildMessages(context)
        val functions = context.availableTools.takeIf { it.isNotEmpty() }
        val accumulatedText = StringBuilder()
        val toolCallRecords = mutableListOf<ToolCallRecord>()

        try {
            LLMClientFactory.create(state, cancelable).use { client ->
                repeat(maxIterations()) { _ ->
                    cancelable.checkCanceled()

                    val request = ChatRequest(
                        model = state.aiModel,
                        temperature = temperature(),
                        maxTokens = maxTokens(),
                        messages = messages.toList(),
                        functions = functions,
                        functionCall = functions?.let { "auto" }
                    )

                    val response = client.postChatCompletions(request)
                    val choice = response.choices.firstOrNull()
                        ?: return AgentResult(name, "", "", toolCallRecords, AgentStatus.ERROR)

                    val assistantMsg = choice.message
                        ?: return AgentResult(name, "", "", toolCallRecords, AgentStatus.ERROR)
                    messages.add(assistantMsg)

                    assistantMsg.content?.let { accumulatedText.append(it) }

                    // Tool call
                    if (choice.finishReason == "function_call" && assistantMsg.functionCall != null) {
                        val call = assistantMsg.functionCall
                        log.info("[$name] TOOL_CALL | ${call.name} | args=${call.arguments.take(200)}")
                        onToolCallStarted?.invoke(call)

                        val startMs = System.currentTimeMillis()
                        val result = onToolDispatch?.invoke(call)
                            ?: ToolResult.Error("Нет диспетчера инструментов")
                        val durationMs = System.currentTimeMillis() - startMs

                        onToolCallResult?.invoke(call, result)

                        val toolText = when (result) {
                            is ToolResult.Ok -> result.content
                            is ToolResult.Error -> "ERROR: ${result.message}"
                            ToolResult.Denied -> "Пользователь отклонил выполнение инструмента"
                        }
                        val toolContent = mapper.writeValueAsString(mapOf("result" to toolText))
                        messages.add(ChatMessage(role = "function", name = call.name, content = toolContent))

                        toolCallRecords.add(ToolCallRecord(call.name, call.arguments, toolText, durationMs))
                        log.info("[$name] TOOL_RESULT | ${call.name} | ${durationMs}ms | ${toolText.take(150)}")
                        return@repeat
                    }

                    // Обрезано по токенам — просим продолжить
                    if (choice.finishReason == "length") {
                        log.info("[$name] TRUNCATED | finishReason=length, requesting continuation")
                        messages.add(ChatMessage(role = "user", content = "Продолжи с того места где остановился, без повторений."))
                        return@repeat
                    }

                    // Финальный ответ
                    val text = accumulatedText.toString().ifEmpty { assistantMsg.content ?: "" }
                    val elapsed = System.currentTimeMillis() - startTime
                    log.info("[$name] DONE | status=SUCCESS | ${elapsed}ms | toolCalls=${toolCallRecords.size} | output=${text.length} chars")
                    return AgentResult(
                        agentName = name,
                        summary = buildSummary(text),
                        fullOutput = text,
                        toolCalls = toolCallRecords,
                        status = AgentStatus.SUCCESS
                    )
                }
                // Исчерпаны итерации
                val text = accumulatedText.toString()
                val elapsed = System.currentTimeMillis() - startTime
                log.warn("[$name] DONE | status=PARTIAL (max iterations reached) | ${elapsed}ms | toolCalls=${toolCallRecords.size}")
                return AgentResult(name, buildSummary(text), text, toolCallRecords, AgentStatus.PARTIAL)
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            log.info("[$name] CANCELLED | ${System.currentTimeMillis() - startTime}ms")
            throw e
        } catch (e: Exception) {
            log.warn("[$name] ERROR | ${System.currentTimeMillis() - startTime}ms | ${e.message}", e)
            return AgentResult(
                agentName = name,
                summary = "Ошибка: ${e.message}",
                fullOutput = "Ошибка: ${e.message}",
                toolCalls = toolCallRecords,
                status = AgentStatus.ERROR
            )
        }
    }

    /**
     * Строит summary из полного ответа (для передачи оркестратору).
     * По умолчанию берёт первые 500 символов.
     */
    protected open fun buildSummary(fullOutput: String): String {
        return if (fullOutput.length <= 500) fullOutput
        else fullOutput.take(500) + "…"
    }

    private fun buildMessages(context: AgentContext): MutableList<ChatMessage> {
        val msgs = mutableListOf<ChatMessage>()
        msgs.add(ChatMessage(role = "system", content = systemPrompt))

        // Сжатая история
        if (!context.conversationSummary.isNullOrBlank()) {
            msgs.add(ChatMessage(role = "user", content = "Краткое содержание предыдущего разговора:\n${context.conversationSummary}"))
            msgs.add(ChatMessage(role = "assistant", content = "Понял, учту контекст предыдущего разговора."))
        }

        // Последние сообщения (verbatim)
        msgs.addAll(context.recentMessages)

        // Текущая задача
        msgs.add(ChatMessage(role = "user", content = context.task))

        return msgs
    }
}
