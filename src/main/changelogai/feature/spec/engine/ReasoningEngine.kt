package changelogai.feature.spec.engine

import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.feature.spec.context.ProjectContext
import changelogai.feature.spec.context.ProjectContextCollector
import changelogai.feature.spec.model.ClarificationQuestion
import changelogai.feature.spec.model.ReasoningStep
import changelogai.feature.spec.model.SpecDocument
import changelogai.feature.spec.model.SpecState
import changelogai.platform.LLMClientFactory
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Центральный оркестратор генерации спецификации.
 * Все LLM-вызовы выполняются в фоновом потоке.
 * Обновления состояния передаются через колбэки (вызываются из фонового потока).
 */
class ReasoningEngine(private val project: Project) {

    private val promptBuilder = PromptBuilder()
    private val contextCollector = ProjectContextCollector(project)
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelable = AtomicReference<AtomicCancelable?>(null)

    // ---- Observable state ----------------------------------------------
    @Volatile var state: SpecState = SpecState.IDLE
        private set
    val steps: CopyOnWriteArrayList<ReasoningStep> = CopyOnWriteArrayList()
    val questions: CopyOnWriteArrayList<ClarificationQuestion> = CopyOnWriteArrayList()
    @Volatile var spec: SpecDocument? = null
        private set
    @Volatile var errorMessage: String? = null
        private set

    // ---- Callbacks (called from background thread) ---------------------
    var onStateChanged: ((SpecState) -> Unit)? = null
    var onStepAdded: ((ReasoningStep) -> Unit)? = null
    var onQuestionsReady: ((List<ClarificationQuestion>) -> Unit)? = null
    var onSpecReady: ((SpecDocument) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onToken: ((String) -> Unit)? = null
    var onValidation: ((SpecValidator.ValidationResult) -> Unit)? = null

    // ---- Conversation history ------------------------------------------
    private val history = mutableListOf<ChatMessage>()
    private lateinit var taskDescription: String
    private var validationRetries = 0
    private val maxValidationRetries = 2

    // ---- Actions -------------------------------------------------------

    fun startAnalysis(task: String) {
        taskDescription = task
        history.clear()
        steps.clear()
        questions.clear()
        spec = null
        errorMessage = null
        validationRetries = 0
        transition(SpecState.ANALYZING)

        submit {
            val ctx = contextCollector.collect()
            val userPrompt = promptBuilder.buildUserPrompt(task, ctx)
            callLLM(systemPrompt = promptBuilder.buildSystemPrompt(), userMessage = userPrompt, ctx = ctx)
        }
    }

    fun submitAnswers(answers: Map<String, String>) {
        answers.forEach { (id, answer) ->
            questions.find { it.id == id }?.answer = answer
        }
        transition(SpecState.GENERATING)

        submit {
            val ctx = contextCollector.collect()
            val answersMap = questions.associate { it.id to (it.answer ?: "") }
            val userPrompt = promptBuilder.buildUserPrompt(taskDescription, ctx, answersMap)
            callLLM(systemPrompt = null, userMessage = userPrompt, ctx = ctx)
        }
    }

    fun cancel() {
        cancelable.get()?.cancel()
        transition(SpecState.IDLE)
    }

    fun reset() {
        cancel()
        steps.clear()
        questions.clear()
        spec = null
        errorMessage = null
        history.clear()
        transition(SpecState.IDLE)
    }

    // ---- Internal -------------------------------------------------------

    private fun submit(block: () -> Unit): Future<*> = executor.submit {
        try { block() } catch (e: Exception) {
            handleError("Ошибка: ${e.message}")
        }
    }

    private fun callLLM(systemPrompt: String?, userMessage: String, ctx: ProjectContext) {
        val ac = AtomicCancelable()
        cancelable.set(ac)

        val state = PluginState.getInstance()
        val messages = mutableListOf<ChatMessage>()
        if (systemPrompt != null) messages.add(ChatMessage(role = "system", content = systemPrompt))
        messages.addAll(history)
        messages.add(ChatMessage(role = "user", content = userMessage))

        val request = ChatRequest(
            model = state.aiModel,
            temperature = 0.3,
            maxTokens = state.commitSize.value.coerceAtLeast(4000),
            messages = messages
        )

        try {
            LLMClientFactory.create(state, ac).use { client ->
                val response = client.postChatCompletions(request)
                val assistantText = response.choices.firstOrNull()?.message?.content
                    ?: run { handleError("LLM вернул пустой ответ"); return }

                // Сохраняем в историю
                history.add(ChatMessage(role = "user", content = userMessage))
                history.add(ChatMessage(role = "assistant", content = assistantText))

                processLLMResponse(assistantText)
            }
        } catch (e: Exception) {
            handleError("Ошибка LLM: ${e.message}")
        }
    }

    private fun processLLMResponse(text: String) {
        val parser = StreamParser()

        // Симулируем стриминг по словам для live preview
        val words = text.split(" ")
        val fullResponse = StringBuilder()
        for (word in words) {
            val chunk = "$word "
            fullResponse.append(chunk)
            onToken?.invoke(chunk)
            val events = parser.feed(chunk)
            events.forEach { handleEvent(it) }
        }
        parser.finalize().forEach { handleEvent(it) }

        // Определяем следующее состояние
        when {
            spec != null -> {
                val validationResult = SpecValidator.validate(spec!!)
                onValidation?.invoke(validationResult)
                if (validationResult.hasErrors && validationRetries < maxValidationRetries) {
                    validationRetries++
                    val fixStep = ReasoningStep(
                        ReasoningStep.StepType.THINKING,
                        "Валидация не пройдена (попытка $validationRetries/$maxValidationRetries):\n${validationResult.summary()}"
                    )
                    steps.add(fixStep)
                    onStepAdded?.invoke(fixStep)
                    spec = null
                    transition(SpecState.GENERATING)
                    // Повторный раунд с фидбэком валидатора
                    val fixPrompt = SpecValidator.buildFixPrompt(validationResult)
                    val ctx = contextCollector.collect()
                    callLLM(systemPrompt = null, userMessage = fixPrompt, ctx = ctx)
                } else {
                    transition(SpecState.COMPLETE)
                }
            }
            questions.isNotEmpty() -> {
                onQuestionsReady?.invoke(questions.toList())
                transition(SpecState.CLARIFYING)
            }
            else -> {
                // Нет структурированного вывода — показываем как текст
                steps.add(ReasoningStep(ReasoningStep.StepType.OUTPUT, text))
                onStepAdded?.invoke(steps.last())
                transition(SpecState.COMPLETE)
            }
        }
    }

    private fun handleEvent(event: StreamParser.ParseEvent) {
        when (event) {
            is StreamParser.ParseEvent.ThinkingStep -> {
                val step = ReasoningStep(ReasoningStep.StepType.THINKING, event.text)
                steps.add(step)
                onStepAdded?.invoke(step)
            }
            is StreamParser.ParseEvent.QuestionFound -> {
                if (questions.none { it.id == event.question.id }) {
                    questions.add(event.question)
                    val step = ReasoningStep(ReasoningStep.StepType.QUESTION, event.question.text)
                    steps.add(step)
                    onStepAdded?.invoke(step)
                }
            }
            is StreamParser.ParseEvent.SpecReady -> {
                spec = event.spec
                onSpecReady?.invoke(event.spec)
            }
            is StreamParser.ParseEvent.RawText -> {
                if (event.text.isNotBlank()) {
                    val step = ReasoningStep(ReasoningStep.StepType.OUTPUT, event.text)
                    steps.add(step)
                    onStepAdded?.invoke(step)
                }
            }
        }
    }

    private fun handleError(msg: String) {
        errorMessage = msg
        onError?.invoke(msg)
        transition(SpecState.ERROR)
    }

    private fun transition(newState: SpecState) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
