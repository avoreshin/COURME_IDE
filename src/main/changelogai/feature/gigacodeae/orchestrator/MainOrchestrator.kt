package changelogai.feature.gigacodeae.orchestrator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.llm.model.FunctionCall
import changelogai.core.llm.model.FunctionDefinition
import changelogai.core.settings.PluginState
import changelogai.feature.gigacodeae.orchestrator.agents.*
import changelogai.feature.gigacodeae.skill.SkillDefinition
import changelogai.feature.gigacodeae.tools.ToolDispatcher
import changelogai.feature.gigacodeae.tools.ToolResult
import changelogai.platform.LLMClientFactory

/**
 * Главный оркестратор: заменяет ChatOrchestrator.
 *
 * Тот же публичный API [sendMessage], но внутри:
 * 1. Сжимает историю (summary + последние сообщения)
 * 2. Классифицирует intent (rules-based, без LLM)
 * 3. Роутит tools только нужному агенту
 * 4. Запускает sub-agent(ы)
 * 5. Для простых вопросов — прямой вызов LLM без tools
 */
class MainOrchestrator(
    private val project: Project,
    private val dispatcher: ToolDispatcher
) {
    private val log = Logger.getInstance(MainOrchestrator::class.java)
    private val classifier = IntentClassifier()
    private val compressor = ContextCompressor()

    private val agents = mapOf(
        "Code" to CodeAgent(),
        "Review" to ReviewAgent(),
        "Test" to TestAgent(),
        "Search" to SearchAgent(),
        "Planner" to PlannerAgent(),
        "Summarizer" to SummarizerAgent(),
        "Tool" to ToolAgent()
    )

    /** Кэш summary по session id (session id передаётся неявно через history) */
    private val summaryCache = mutableMapOf<Int, String>()

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
        mcpDispatch: ((name: String, args: Map<String, Any>) -> String)? = null,
        systemPrompt: String? = null,
        currentSkill: SkillDefinition? = null,
        sessionSummary: String? = null,
        onSummaryUpdated: ((String) -> Unit)? = null,
        mode: OrchestratorMode = OrchestratorMode.AUTO,
        onAgentLog: ((AgentLogEvent) -> Unit)? = null
    ) {
        try {
            val skill = currentSkill ?: SkillDefinition.defaults().first()
            val toolRouter = ToolRouter(dispatcher, { extraFunctions }, mcpDispatch)
            log.info("[Orchestrator] >>> NEW REQUEST | skill=${skill.id} | historySize=${history.size} | text=${userText.take(100)}")

            // 1. Сжимаем историю
            val compressed = compressor.compressHistory(history, sessionSummary)
            log.info("[Orchestrator] compressed | summary=${compressed.summary?.length ?: 0} chars | recent=${compressed.recentMessages.size} msgs | needsSummarization=${compressed.needsSummarization}")

            // 2. Если нужна суммаризация — запускаем SummarizerAgent
            var summary = compressed.summary
            if (compressed.needsSummarization && compressed.oldMessages.isNotEmpty()) {
                cancelable.checkCanceled()
                onAgentLog?.invoke(AgentLogEvent(AgentLogEvent.Type.SUMMARIZE, "Сжатие истории (${compressed.oldMessages.size} сообщений)"))
                val summarizerAgent = agents["Summarizer"]!!
                val oldText = compressor.formatMessagesForSummarization(compressed.oldMessages)
                val sumCtx = AgentContext(
                    task = "Сделай краткое резюме следующей истории разговора:\n\n$oldText",
                    conversationSummary = null,
                    recentMessages = emptyList(),
                    availableTools = emptyList(),
                    projectBrief = buildProjectBrief()
                )
                val sumResult = summarizerAgent.run(sumCtx, cancelable)
                summary = sumResult.fullOutput
                onSummaryUpdated?.invoke(summary)
            }

            // 3. Классифицируем intent (или используем принудительный режим)
            classifier.availableMcpToolNames = extraFunctions.map { it.name }.toSet()
            val plan = classifier.classify(userText, skill)
            val effectiveMode = mode
            log.info("[Orchestrator] intent=${plan.primaryIntent} | agents=${plan.agents} | mode=$effectiveMode")

            val useMultiStep = when (effectiveMode) {
                OrchestratorMode.MULTI_STEP -> true
                OrchestratorMode.SINGLE_AGENT -> false
                OrchestratorMode.AUTO -> plan.needsPlanning
            }

            val useDirectAnswer = effectiveMode == OrchestratorMode.AUTO
                && plan.primaryIntent == IntentClassifier.Intent.QUESTION

            onAgentLog?.invoke(AgentLogEvent(
                AgentLogEvent.Type.INTENT,
                "Intent: ${plan.primaryIntent}",
                "Режим: ${effectiveMode.label} | Агенты: ${plan.agents.ifEmpty { listOf("direct") }}"
            ))

            // 4. Простой вопрос — прямой LLM, без tools
            if (useDirectAnswer) {
                log.info("[Orchestrator] → DIRECT_ANSWER (no tools, no agents)")
                directAnswer(
                    summary, compressed.recentMessages, userText,
                    systemPrompt ?: skill.systemPrompt,
                    cancelable, onAssistantMessage, onDone, onError
                )
                return
            }

            // 5. Multi-step execution
            if (useMultiStep) {
                log.info("[Orchestrator] → MULTI_STEP (Planner + sub-agents)")
                executeMultiStep(
                    userText, summary, compressed.recentMessages,
                    toolRouter, cancelable,
                    onToolCallStarted, onToolCallResult, onAssistantMessage, onDone, onError,
                    onAgentLog
                )
                return
            }

            // 6. Запускаем нужного агента
            val agentName = plan.agents.firstOrNull() ?: "Code"
            log.info("[Orchestrator] → SINGLE_AGENT | $agentName")
            onAgentLog?.invoke(AgentLogEvent(AgentLogEvent.Type.AGENT_START, "Агент: $agentName", "tools: ${agents[agentName]?.allowedTools}"))
            runSubAgent(
                agentName, userText, summary, compressed.recentMessages,
                toolRouter, cancelable,
                onToolCallStarted, onToolCallResult, onAssistantMessage, onDone, onError,
                onAgentLog
            )

        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            // отмена — молча
        } catch (e: Exception) {
            onError(e.message ?: "Неизвестная ошибка")
        }
    }

    /**
     * Multi-step execution: PlannerAgent разбивает задачу на шаги,
     * затем каждый шаг выполняется подходящим агентом.
     * Результат предыдущего шага передаётся как контекст следующему.
     */
    private fun executeMultiStep(
        userText: String,
        summary: String?,
        recentMessages: List<ChatMessage>,
        toolRouter: ToolRouter,
        cancelable: AtomicCancelable,
        onToolCallStarted: (FunctionCall) -> Unit,
        onToolCallResult: (FunctionCall, ToolResult) -> Unit,
        onAssistantMessage: (String) -> Unit,
        onDone: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit,
        onAgentLog: ((AgentLogEvent) -> Unit)? = null
    ) {
        // 1. PlannerAgent создаёт план
        cancelable.checkCanceled()
        val plannerAgent = agents["Planner"]!!
        val planCtx = AgentContext(
            task = userText,
            conversationSummary = summary,
            recentMessages = recentMessages,
            availableTools = emptyList(),
            projectBrief = buildProjectBrief()
        )
        val planResult = plannerAgent.run(planCtx, cancelable)

        if (planResult.status == AgentStatus.ERROR) {
            onError("Planner: ${planResult.fullOutput}")
            return
        }

        // 2. Парсим шаги
        val steps = PlanParser.parse(planResult.fullOutput)
        val totalSteps = steps.size
        log.info("[Orchestrator] PLAN parsed | $totalSteps steps: ${steps.map { "[${it.tag}] ${it.agentName}" }}")
        onAgentLog?.invoke(AgentLogEvent(
            AgentLogEvent.Type.PLAN_CREATED,
            "План: $totalSteps шагов",
            steps.mapIndexed { i, s -> "${i + 1}. [${s.tag}] ${s.description}" }.joinToString("\n")
        ))
        val stepResults = mutableListOf<StepResult>()
        val allToolCalls = mutableListOf<ToolCallRecord>()

        // Показываем план пользователю
        onAssistantMessage("**План выполнения ($totalSteps шагов):**\n${planResult.fullOutput}")

        // 3. Выполняем каждый шаг нужным агентом
        for ((index, step) in steps.withIndex()) {
            cancelable.checkCanceled()

            val stepNum = index + 1
            val agent = agents[step.agentName] ?: agents["Code"]!!
            val tools = toolRouter.getToolsForAgent(agent)
            log.info("[Orchestrator] STEP $stepNum/$totalSteps | [${step.tag}] → ${agent.name} | tools=${tools.map { it.name }} | ${step.description.take(80)}")
            onAgentLog?.invoke(AgentLogEvent(
                AgentLogEvent.Type.PLAN_STEP,
                "Шаг $stepNum/$totalSteps → ${agent.name}",
                "[${step.tag}] ${step.description}"
            ))

            // Собираем контекст из предыдущих шагов
            val previousContext = if (stepResults.isNotEmpty()) {
                stepResults.joinToString("\n\n") { prev ->
                    "Шаг ${prev.stepNum} [${prev.tag}] ${prev.description}:\n${prev.summary}"
                }
            } else null

            val stepTask = buildString {
                append("Общая задача пользователя: $userText\n\n")
                append("Текущий шаг $stepNum/$totalSteps: [${step.tag}] ${step.description}\n")
                if (previousContext != null) {
                    append("\nРезультаты предыдущих шагов:\n$previousContext")
                }
            }

            val context = AgentContext(
                task = stepTask,
                conversationSummary = summary,
                recentMessages = recentMessages,
                availableTools = tools,
                projectBrief = buildProjectBrief()
            )

            val result = agent.run(
                context = context,
                cancelable = cancelable,
                onToolDispatch = { call -> toolRouter.dispatch(call) },
                onToolCallStarted = onToolCallStarted,
                onToolCallResult = onToolCallResult
            )

            allToolCalls.addAll(result.toolCalls)

            stepResults.add(StepResult(stepNum, step.tag, step.description, result.summary, result.fullOutput, result.status))
            log.info("[Orchestrator] STEP $stepNum/$totalSteps DONE | status=${result.status} | toolCalls=${result.toolCalls.size} | output=${result.fullOutput.length} chars")
            onAgentLog?.invoke(AgentLogEvent(
                AgentLogEvent.Type.AGENT_DONE,
                "Шаг $stepNum/$totalSteps: ${result.status}",
                "${agent.name} | ${result.toolCalls.size} tool calls | ${result.fullOutput.length} символов"
            ))

            // Показываем результат шага
            onAssistantMessage("**Шаг $stepNum/$totalSteps [${step.tag}]** ${step.description}\n\n${result.fullOutput}")

            // Если шаг упал — останавливаемся
            if (result.status == AgentStatus.ERROR) {
                onError("Ошибка на шаге $stepNum/${totalSteps}: ${result.fullOutput}")
                return
            }
        }

        // 4. Собираем финальную историю
        log.info("[Orchestrator] MULTI_STEP COMPLETE | ${stepResults.size}/$totalSteps steps succeeded | totalToolCalls=${allToolCalls.size}")
        val fullOutput = stepResults.joinToString("\n\n---\n\n") { sr ->
            "**Шаг ${sr.stepNum} [${sr.tag}]** ${sr.description}\n\n${sr.fullOutput}"
        }
        val updatedMessages = recentMessages.toMutableList()
        updatedMessages.add(ChatMessage(role = "user", content = userText))
        updatedMessages.add(ChatMessage(role = "assistant", content = fullOutput))
        onDone(updatedMessages)
    }

    private data class StepResult(
        val stepNum: Int,
        val tag: String,
        val description: String,
        val summary: String,
        val fullOutput: String,
        val status: AgentStatus
    )

    private fun runSubAgent(
        agentName: String,
        task: String,
        summary: String?,
        recentMessages: List<ChatMessage>,
        toolRouter: ToolRouter,
        cancelable: AtomicCancelable,
        onToolCallStarted: (FunctionCall) -> Unit,
        onToolCallResult: (FunctionCall, ToolResult) -> Unit,
        onAssistantMessage: (String) -> Unit,
        onDone: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit,
        onAgentLog: ((AgentLogEvent) -> Unit)? = null
    ) {
        val agent = agents[agentName] ?: agents["Code"]!!
        val tools = toolRouter.getToolsForAgent(agent)
        log.info("[Orchestrator] runSubAgent | ${agent.name} | tools=${tools.map { it.name }}")

        val context = AgentContext(
            task = task,
            conversationSummary = summary,
            recentMessages = recentMessages,
            availableTools = tools,
            projectBrief = buildProjectBrief()
        )

        val result = agent.run(
            context = context,
            cancelable = cancelable,
            onToolDispatch = { call -> toolRouter.dispatch(call) },
            onToolCallStarted = onToolCallStarted,
            onToolCallResult = onToolCallResult
        )

        log.info("[Orchestrator] runSubAgent DONE | ${agent.name} | status=${result.status} | toolCalls=${result.toolCalls.size}")
        onAgentLog?.invoke(AgentLogEvent(
            AgentLogEvent.Type.AGENT_DONE,
            "${agent.name}: ${result.status}",
            "${result.toolCalls.size} tool calls | ${result.fullOutput.length} символов"
        ))
        when (result.status) {
            AgentStatus.SUCCESS, AgentStatus.PARTIAL -> {
                onAssistantMessage(result.fullOutput)
                // Строим компактную историю: user + assistant (без промежуточных tool calls)
                val updatedMessages = recentMessages.toMutableList()
                updatedMessages.add(ChatMessage(role = "user", content = task))
                updatedMessages.add(ChatMessage(role = "assistant", content = result.fullOutput))
                onDone(updatedMessages)
            }
            AgentStatus.ERROR -> {
                onError(result.fullOutput)
            }
        }
    }

    /**
     * Прямой ответ на простой вопрос: один вызов LLM без tools, минимальный контекст.
     */
    private fun directAnswer(
        summary: String?,
        recentMessages: List<ChatMessage>,
        userText: String,
        systemPrompt: String,
        cancelable: AtomicCancelable,
        onAssistantMessage: (String) -> Unit,
        onDone: (List<ChatMessage>) -> Unit,
        onError: (String) -> Unit
    ) {
        val state = PluginState.getInstance()
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage(role = "system", content = systemPrompt))

        if (!summary.isNullOrBlank()) {
            messages.add(ChatMessage(role = "user", content = "Контекст предыдущего разговора:\n$summary"))
            messages.add(ChatMessage(role = "assistant", content = "Учту контекст."))
        }

        messages.addAll(recentMessages)
        messages.add(ChatMessage(role = "user", content = userText))

        val request = ChatRequest(
            model = state.aiModel,
            temperature = state.temperature.value,
            maxTokens = state.commitSize.value,
            messages = messages
            // functions = null — без tools!
        )

        try {
            LLMClientFactory.create(state, cancelable).use { client ->
                val response = client.postChatCompletions(request)
                val text = response.choices.firstOrNull()?.message?.content
                    ?: run { onError("LLM вернул пустой ответ"); return }
                onAssistantMessage(text)
                val updated = recentMessages.toMutableList()
                updated.add(ChatMessage(role = "user", content = userText))
                updated.add(ChatMessage(role = "assistant", content = text))
                onDone(updated)
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            // отмена
        } catch (e: Exception) {
            onError(e.message ?: "Неизвестная ошибка")
        }
    }

    private fun buildProjectBrief(): ProjectBrief {
        val basePath = project.basePath ?: "."
        return ProjectBrief(
            name = project.name,
            basePath = basePath
        )
    }
}
