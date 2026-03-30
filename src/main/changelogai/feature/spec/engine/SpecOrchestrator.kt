package changelogai.feature.spec.engine

import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.feature.spec.agents.*
import changelogai.feature.spec.context.ProjectContext
import changelogai.feature.spec.context.ProjectContextCollector
import changelogai.feature.spec.model.ClarificationQuestion
import changelogai.feature.spec.model.ReasoningStep
import changelogai.feature.spec.model.SpecDocument
import changelogai.feature.spec.model.SpecState
import changelogai.feature.kb.model.KBSearchResult
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Мультиагентный оркестратор генерации спецификации.
 *
 * Фазы:
 *   1. ANALYZE   — AnalyzerAgent изучает задачу
 *   2. CLARIFY   — ClarifierAgent формирует вопросы → ожидание ответов пользователя
 *   3. GENERATE  — 4 агента параллельно генерируют FR / NFR / AC / EDGE
 *   4. VALIDATE  — SpecValidator проверяет результат
 *   5. FIX       — ValidatorAgent точечно правит только проблемные секции (до 2 раз)
 */
class SpecOrchestrator(private val project: Project) {

    private val contextCollector = ProjectContextCollector(project)
    private val parser = StreamParser()
    private val executor = Executors.newCachedThreadPool()
    private val cancelable = AtomicReference<AtomicCancelable?>(null)

    // ---- Agents --------------------------------------------------------
    private val analyzerAgent  = AnalyzerAgent()
    private val clarifierAgent = ClarifierAgent()
    private val frAgent        = FunctionalAgent()
    private val nfrAgent       = NonFunctionalAgent()
    private val acAgent        = AcceptanceCriteriaAgent()
    private val edgeAgent      = EdgeCasesAgent()
    private val validatorAgent = ValidatorAgent()
    private val prdAgent       = PrdAgent()
    private val mermaidAgent   = MermaidAgent()
    private val refinerAgent   = RefinerAgent()

    // ---- Observable state ----------------------------------------------
    @Volatile var state: SpecState = SpecState.IDLE; private set
    val steps = CopyOnWriteArrayList<ReasoningStep>()
    val questions = CopyOnWriteArrayList<ClarificationQuestion>()
    @Volatile var spec: SpecDocument? = null; private set
    @Volatile var errorMessage: String? = null; private set

    // ---- Callbacks (from background thread) ----------------------------
    var onStateChanged:     ((SpecState) -> Unit)?                    = null
    var onStepAdded:        ((ReasoningStep) -> Unit)?                = null
    var onQuestionsReady:   ((List<ClarificationQuestion>) -> Unit)?  = null
    var onSpecReady:        ((SpecDocument) -> Unit)?                 = null
    var onError:            ((String) -> Unit)?                       = null
    var onValidation:       ((SpecValidator.ValidationResult) -> Unit)? = null
    var onAgentStarted:     ((String, String) -> Unit)?               = null  // (agentName, icon)
    var onAgentFinished:    ((String) -> Unit)?                       = null
    var onPrdReady:                 ((String) -> Unit)?                       = null  // Markdown PRD document
    var onMermaidReady:             ((String) -> Unit)?                       = null
    var onIterationStarted:         ((Int) -> Unit)?                          = null
    var onFollowUpQuestionsReady:   ((List<ClarificationQuestion>) -> Unit)?  = null

    // ---- Session -------------------------------------------------------
    private lateinit var taskDescription: String
    private lateinit var projectContext: ProjectContext
    private var analysisResult: String = ""
    private var skillContext: String = ""
    private var confluenceCtx: changelogai.feature.spec.confluence.ConfluenceContext? = null
    private var kbResults: List<KBSearchResult> = emptyList()
    private var validationRetries = 0
    private var followUpAsked = false
    @Volatile var prdDocument: String? = null; private set
    @Volatile var iteration: Int = 0; private set
    @Volatile var mermaidDiagrams: String? = null; private set
    private val allAnswers = mutableListOf<Map<String, String>>()

    // ---- Public API ----------------------------------------------------

    fun startAnalysis(
        task: String,
        skillCtx: String = "",
        confluenceCtx: changelogai.feature.spec.confluence.ConfluenceContext? = null,
        kbResults: List<KBSearchResult> = emptyList()
    ) {
        taskDescription = task
        skillContext = skillCtx
        this.confluenceCtx = confluenceCtx
        this.kbResults = kbResults
        reset(keepTask = true)
        iteration = 0; allAnswers.clear(); followUpAsked = false
        transition(SpecState.ANALYZING)
        submitBg { phase1Analyze() }
    }

    fun submitAnswers(answers: Map<String, String>) {
        answers.forEach { (id, answer) ->
            questions.find { it.id == id }?.answer = answer
        }
        allAnswers.add(answers)
        // First submission: check for follow-up questions before generating
        if (!followUpAsked && iteration == 0) {
            followUpAsked = true
            transition(SpecState.CLARIFYING)
            submitBg { phaseFollowUpClarify() }
        } else {
            transition(SpecState.GENERATING)
            submitBg { phase3Generate() }
        }
    }

    /** Legacy: kept for compatibility, delegates to refineWithContext */
    fun refine() = refineWithContext("")

    /** Add free-form clarification context and re-generate */
    fun refineWithContext(context: String) {
        iteration++
        onIterationStarted?.invoke(iteration)
        allAnswers.add(mapOf("__context__" to context))
        transition(SpecState.GENERATING)
        submitBg { phase3Generate() }
    }

    fun cancel()  { cancelable.get()?.cancel(); transition(SpecState.IDLE) }
    fun reset()   { reset(keepTask = false) }

    // ---- Phase Follow-Up Clarify ---------------------------------------

    private fun phaseFollowUpClarify() {
        addStep(ReasoningStep.StepType.THINKING, "❓ Проверяю: нужны ли уточняющие вопросы по ответам...")
        onAgentStarted?.invoke(clarifierAgent.name, clarifierAgent.icon)

        val answersText = allAnswers.lastOrNull()?.entries
            ?.filter { !it.key.startsWith("__") }
            ?.joinToString("\n") { (id, v) ->
                val q = questions.find { it.id == id }?.text ?: id
                "- $q: $v"
            } ?: ""

        val prompt = """
Задача: $taskDescription

Анализ: $analysisResult

Пользователь ответил на первичные вопросы:
$answersText

Задай ТОЛЬКО 1-3 КРИТИЧЕСКИХ уточняющих вопроса если есть важная информация, без которой нельзя составить точную спецификацию.
Если всё понятно — верни пустой ответ БЕЗ тегов <clarification>.
НЕ повторяй уже отвеченные вопросы.
        """.trimIndent()

        val xml = runAgentSafe(clarifierAgent, prompt) ?: ""
        onAgentFinished?.invoke(clarifierAgent.name)

        val followUps = parseClarifications(xml)
        if (followUps.isEmpty()) {
            addStep(ReasoningStep.StepType.THINKING, "Дополнительных вопросов нет — генерирую спецификацию")
            transition(SpecState.GENERATING)
            submitBg { phase3Generate() }
        } else {
            questions.addAll(followUps)
            followUps.forEach { q -> addStep(ReasoningStep.StepType.QUESTION, "Доп: ${q.id}: ${q.text}") }
            onFollowUpQuestionsReady?.invoke(followUps)
            // Stay in CLARIFYING — user will call submitAnswers again
        }
    }

    // ---- Phase Refine --------------------------------------------------

    private fun phaseRefine() {
        addStep(ReasoningStep.StepType.THINKING, "🔄 Итерация #$iteration: анализирую текущую спецификацию для улучшения...")
        onAgentStarted?.invoke(refinerAgent.name, refinerAgent.icon)

        val currentSpec = spec ?: run { handleError("Нет текущей спеки для улучшения"); return }
        val validation = SpecValidator.validate(currentSpec)

        val prompt = buildString {
            appendLine("Задача: $taskDescription")
            appendLine()
            appendLine("Итерация улучшения: $iteration")
            appendLine()
            appendLine("Текущая спецификация (краткая):")
            appendLine("FR: ${currentSpec.functional.size}, NFR: ${currentSpec.nonFunctional.size}, AC: ${currentSpec.acceptanceCriteria.size}, EDGE: ${currentSpec.edgeCases.size}")
            if (validation.errors.isNotEmpty()) {
                appendLine()
                appendLine("Проблемы с текущей спекой:")
                validation.errors.forEach { appendLine("- $it") }
            }
            if (allAnswers.isNotEmpty()) {
                appendLine()
                appendLine("Предыдущие ответы пользователя:")
                allAnswers.forEachIndexed { i, m -> m.forEach { (k, v) -> appendLine("Итерация ${i+1}, $k: $v") } }
            }
        }

        val clarifyXml = runAgentSafe(refinerAgent, prompt) ?: return
        onAgentFinished?.invoke(refinerAgent.name)

        val parsedQuestions = parseClarifications(clarifyXml)
        if (parsedQuestions.isEmpty()) {
            // Если вопросов нет — сразу регенерируем
            transition(SpecState.GENERATING)
            submitBg { phase3Generate() }
            return
        }

        questions.clear()
        questions.addAll(parsedQuestions)
        parsedQuestions.forEach { q ->
            addStep(ReasoningStep.StepType.QUESTION, "${q.id}: ${q.text}")
        }
        onQuestionsReady?.invoke(parsedQuestions)
        transition(SpecState.CLARIFYING)
    }

    // ---- Phase 1: Analyze ----------------------------------------------

    private fun phase1Analyze() {
        addStep(ReasoningStep.StepType.THINKING, "🔍 ${analyzerAgent.name}: анализирую задачу...")
        onAgentStarted?.invoke(analyzerAgent.name, analyzerAgent.icon)

        val ctx = contextCollector.collect().also { projectContext = it }
        val prompt = buildAnalyzePrompt(taskDescription, ctx)

        analysisResult = runAgent(analyzerAgent, prompt) ?: return
        onAgentFinished?.invoke(analyzerAgent.name)
        addStep(ReasoningStep.StepType.THINKING, "Анализ завершён:\n$analysisResult")

        phase2Clarify()
    }

    // ---- Phase 2: Clarify ----------------------------------------------

    private fun phase2Clarify() {
        addStep(ReasoningStep.StepType.THINKING, "❓ ${clarifierAgent.name}: формирую уточняющие вопросы...")
        onAgentStarted?.invoke(clarifierAgent.name, clarifierAgent.icon)

        val prompt = "Задача:\n$taskDescription\n\nАнализ:\n$analysisResult"
        val clarifyXml = runAgent(clarifierAgent, prompt) ?: return
        onAgentFinished?.invoke(clarifierAgent.name)

        // Парсим вопросы
        val parsedQuestions = parseClarifications(clarifyXml)
        if (parsedQuestions.isEmpty()) {
            // Вопросов нет — сразу генерируем
            addStep(ReasoningStep.StepType.THINKING, "Уточнения не нужны — задача достаточно описана")
            phase3Generate()
            return
        }

        questions.addAll(parsedQuestions)
        parsedQuestions.forEach { q ->
            addStep(ReasoningStep.StepType.QUESTION, "${q.id}: ${q.text}")
        }
        onQuestionsReady?.invoke(parsedQuestions)
        transition(SpecState.CLARIFYING)
    }

    // ---- Phase 3: Generate (parallel) ----------------------------------

    private fun phase3Generate() {
        addStep(ReasoningStep.StepType.THINKING, "⚡ Запускаю 4 агента параллельно: FR / NFR / AC / EDGE")

        val answersBlock = buildAnswersBlock()
        val kbBlock = if (kbResults.isNotEmpty()) {
            val sb = StringBuilder("\n\nКонтекст из Базы знаний:\n")
            var totalChars = 0
            for (r in kbResults) {
                if (totalChars + r.chunkText.length > 3000) break
                sb.appendLine("- ${r.pageTitle}: ${r.chunkText.take(500)}")
                totalChars += r.chunkText.length
            }
            sb.toString()
        } else ""
        val baseContext = "Задача:\n$taskDescription\n\nАнализ:\n$analysisResult$answersBlock$kbBlock"
        val ac = newCancelable()

        // Параллельный запуск 4 агентов
        val frFuture  = submitToPool(ac) { runAgentSafe(frAgent,  baseContext) }
        val nfrFuture = submitToPool(ac) { runAgentSafe(nfrAgent, baseContext) }
        val edgeFuture = submitToPool(ac) { runAgentSafe(edgeAgent, baseContext) }

        // AC ждёт результат FR для лучшего покрытия
        val frXml = frFuture.get() ?: ""
        onAgentFinished?.invoke(frAgent.name)
        addStep(ReasoningStep.StepType.THINKING, "📋 ${frAgent.name} завершён")

        val acPrompt = "$baseContext\n\nФункциональные требования:\n$frXml"
        val acFuture = submitToPool(ac) { runAgentSafe(acAgent, acPrompt) }

        val nfrXml  = nfrFuture.get() ?: ""
        val edgeXml = edgeFuture.get() ?: ""
        val acXml   = acFuture.get() ?: ""

        onAgentFinished?.invoke(nfrAgent.name)
        onAgentFinished?.invoke(edgeAgent.name)
        onAgentFinished?.invoke(acAgent.name)

        addStep(ReasoningStep.StepType.THINKING, "⚙️ ${nfrAgent.name} завершён")
        addStep(ReasoningStep.StepType.THINKING, "✅ ${acAgent.name} завершён")
        addStep(ReasoningStep.StepType.THINKING, "⚡ ${edgeAgent.name} завершён")

        // Собираем спеку
        val title = extractTitle(taskDescription)
        val assembled = buildFullXml(title, frXml, nfrXml, acXml, edgeXml)
        val parsed = parseSpec(assembled, title)

        if (parsed == null) {
            handleError("Не удалось собрать спецификацию из ответов агентов")
            return
        }

        phase4Validate(parsed, frXml, nfrXml, acXml, edgeXml, baseContext)
    }

    // ---- Phase 4: Validate + Fix ---------------------------------------

    private fun phase4Validate(
        currentSpec: SpecDocument,
        frXml: String, nfrXml: String, acXml: String, edgeXml: String,
        baseContext: String
    ) {
        val baseResult = SpecValidator.validate(currentSpec)
        val crossWarnings = confluenceCtx?.let { SpecValidator.crossCheckConfluence(currentSpec, it) } ?: emptyList()
        val result = if (crossWarnings.isEmpty()) baseResult else baseResult.copy(
            warnings = baseResult.warnings + crossWarnings
        )
        onValidation?.invoke(result)

        if (!result.hasErrors || validationRetries >= 2) {
            spec = currentSpec
            onSpecReady?.invoke(currentSpec)
            // Phase 5: генерируем PRD-документ
            phase5GeneratePrd(currentSpec, frXml, nfrXml, acXml, edgeXml)
            return
        }

        validationRetries++
        addStep(ReasoningStep.StepType.THINKING,
            "🛡️ Валидация: найдено ${result.errors.size} ошибок, ${result.warnings.size} предупреждений. Запускаю точечное исправление (попытка $validationRetries/2)...")

        val ac = newCancelable()
        val fixFutures = mutableMapOf<String, Future<String?>>()

        // Запускаем ValidatorAgent только для проблемных секций
        if (result.errors.any { "FR" in it || "функциональн" in it.lowercase() }) {
            val frErrors = result.errors.filter { "FR" in it || "функциональн" in it.lowercase() }
            fixFutures["fr"] = submitToPool(ac) {
                runAgentSafe(validatorAgent, buildFixPrompt("FR", frXml, frErrors))
            }
        }
        if (result.errors.any { "NFR" in it || "нефункциональн" in it.lowercase() || "производительн" in it || "безопасн" in it || "доступн" in it }) {
            val nfrErrors = result.errors.filter { "NFR" in it || "нефункциональн" in it.lowercase() || "производительн" in it || "безопасн" in it || "доступн" in it }
            fixFutures["nfr"] = submitToPool(ac) {
                runAgentSafe(validatorAgent, buildFixPrompt("NFR", nfrXml, nfrErrors))
            }
        }
        if (result.errors.any { "AC" in it || "критери" in it.lowercase() || "Given" in it }) {
            val acErrors = result.errors.filter { "AC" in it || "критери" in it.lowercase() || "Given" in it }
            fixFutures["ac"] = submitToPool(ac) {
                runAgentSafe(validatorAgent, buildFixPrompt("AC", acXml, acErrors))
            }
        }
        if (result.errors.any { "EDGE" in it || "граничн" in it.lowercase() }) {
            val edgeErrors = result.errors.filter { "EDGE" in it || "граничн" in it.lowercase() }
            fixFutures["edge"] = submitToPool(ac) {
                runAgentSafe(validatorAgent, buildFixPrompt("EDGE", edgeXml, edgeErrors))
            }
        }

        val fixedFr   = fixFutures["fr"]?.get()   ?: frXml
        val fixedNfr  = fixFutures["nfr"]?.get()  ?: nfrXml
        val fixedAc   = fixFutures["ac"]?.get()   ?: acXml
        val fixedEdge = fixFutures["edge"]?.get() ?: edgeXml

        addStep(ReasoningStep.StepType.THINKING, "Исправление завершено. Перепроверяю...")

        val title = currentSpec.title
        val fixedAssembled = buildFullXml(title, fixedFr, fixedNfr, fixedAc, fixedEdge)
        val fixedSpec = parseSpec(fixedAssembled, title) ?: currentSpec

        phase4Validate(fixedSpec, fixedFr, fixedNfr, fixedAc, fixedEdge, baseContext)
    }

    // ---- Phase 5: PRD Document -----------------------------------------

    private fun phase5GeneratePrd(
        spec: SpecDocument,
        frXml: String, nfrXml: String, acXml: String, edgeXml: String
    ) {
        addStep(ReasoningStep.StepType.THINKING, "📄 ${prdAgent.name}: собираю PRD-документ для разработчиков...")
        onAgentStarted?.invoke(prdAgent.name, prdAgent.icon)

        val answersBlock = buildAnswersBlock()
        val sourceNote = confluenceCtx?.let {
            "\n\nИсточник требований: ${it.canonicalWebUrl}\nВключи эту ссылку в конце документа в раздел '## Источники'."
        } ?: ""
        val prompt = """
Задача: $taskDescription

Анализ: $analysisResult
$answersBlock

Спецификация:
$frXml
$nfrXml
$acXml
$edgeXml$sourceNote
        """.trimIndent()

        val prd = runAgentSafe(prdAgent, prompt)
        onAgentFinished?.invoke(prdAgent.name)

        if (prd != null) {
            prdDocument = prd
            onPrdReady?.invoke(prd)
        }
        transition(SpecState.COMPLETE)
        phase6GenerateMermaid(spec, frXml, nfrXml)
    }

    // ---- Phase 6: Mermaid Diagrams -------------------------------------

    private fun phase6GenerateMermaid(spec: SpecDocument, frXml: String, nfrXml: String) {
        addStep(ReasoningStep.StepType.THINKING, "📊 ${mermaidAgent.name}: генерирую архитектурные диаграммы...")
        onAgentStarted?.invoke(mermaidAgent.name, mermaidAgent.icon)

        val prompt = "Задача: $taskDescription\n\n$frXml\n\n$nfrXml"
        val diagrams = runAgentSafe(mermaidAgent, prompt)
        onAgentFinished?.invoke(mermaidAgent.name)

        if (diagrams != null) {
            mermaidDiagrams = diagrams
            onMermaidReady?.invoke(diagrams)
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun runAgent(agent: SpecAgent, prompt: String): String? {
        onAgentStarted?.invoke(agent.name, agent.icon)
        return try {
            val ac = cancelable.get() ?: newCancelable()
            agent.run(prompt, ac)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            null
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            com.intellij.openapi.diagnostic.Logger.getInstance(SpecOrchestrator::class.java)
                .warn("[SpecOrchestrator] Agent ${agent.name} failed", e)
            handleError("${agent.name}: $detail")
            null
        }
    }

    private fun runAgentSafe(agent: SpecAgent, prompt: String): String? {
        onAgentStarted?.invoke(agent.name, agent.icon)
        return try {
            val ac = cancelable.get() ?: newCancelable()
            agent.run(prompt, ac)
        } catch (e: Exception) {
            addStep(ReasoningStep.StepType.THINKING, "⚠ ${agent.name}: ${e.message}")
            null
        }
    }

    private fun submitBg(block: () -> Unit): Future<*> =
        executor.submit { try { block() } catch (e: Exception) { handleError("Ошибка: ${e.message}") } }

    private fun <T> submitToPool(ac: AtomicCancelable, block: () -> T): Future<T> =
        executor.submit<T> { if (!ac.isCanceled()) block() else null as T }

    private fun newCancelable(): AtomicCancelable {
        val ac = AtomicCancelable()
        cancelable.set(ac)
        return ac
    }

    private fun addStep(type: ReasoningStep.StepType, text: String) {
        val step = ReasoningStep(type, text)
        steps.add(step)
        onStepAdded?.invoke(step)
    }

    private fun transition(newState: SpecState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    private fun handleError(msg: String) {
        errorMessage = msg
        addStep(ReasoningStep.StepType.THINKING, "❌ Ошибка: $msg")
        transition(SpecState.ERROR)
        onError?.invoke(msg)
    }

    private fun reset(keepTask: Boolean) {
        cancelable.get()?.cancel()
        steps.clear(); questions.clear()
        spec = null; errorMessage = null; prdDocument = null
        validationRetries = 0; followUpAsked = false
        allAnswers.clear(); iteration = 0; mermaidDiagrams = null
        transition(SpecState.IDLE)
    }

    // ---- Prompt builders -----------------------------------------------

    private fun buildAnalyzePrompt(task: String, ctx: ProjectContext): String {
        val sb = StringBuilder()
        sb.appendLine("## Задача"); sb.appendLine(task); sb.appendLine()
        if (skillContext.isNotBlank()) {
            sb.appendLine("## Дополнительный контекст / специализация")
            sb.appendLine(skillContext); sb.appendLine()
        }
        confluenceCtx?.let {
            sb.appendLine(it.toPromptBlock())
        }
        if (kbResults.isNotEmpty()) {
            sb.appendLine("## Релевантный контекст из Базы знаний")
            var totalChars = 0
            for (r in kbResults) {
                if (totalChars + r.chunkText.length > 4000) break
                sb.appendLine("### ${r.pageTitle} (${(r.score * 100).toInt()}%)")
                sb.appendLine(r.chunkText)
                sb.appendLine()
                totalChars += r.chunkText.length
            }
        }
        sb.appendLine("## Контекст проекта")
        sb.appendLine("Проект: ${ctx.projectName}")
        ctx.language?.let { sb.appendLine("Язык: $it") }
        ctx.framework?.let { sb.appendLine("Фреймворк: $it") }
        ctx.buildTool?.let { sb.appendLine("Сборка: $it") }
        if (ctx.dependencies.isNotEmpty()) sb.appendLine("Зависимости: ${ctx.dependencies.take(8).joinToString(", ")}")
        return sb.toString()
    }

    private fun buildAnswersBlock(): String {
        if (allAnswers.isEmpty()) return ""
        val sb = StringBuilder("\n\nОтветы и уточнения пользователя:\n")
        allAnswers.forEachIndexed { idx, m ->
            m.forEach { (id, answer) ->
                if (id == "__context__") {
                    sb.appendLine("Уточнение #${idx+1}: $answer")
                } else {
                    val qText = questions.find { it.id == id }?.text ?: id
                    sb.appendLine("- $qText: $answer")
                }
            }
        }
        return sb.toString()
    }

    private fun buildFixPrompt(section: String, currentXml: String, errors: List<String>): String {
        return "Текущая секция $section:\n$currentXml\n\nПроблемы для исправления:\n${errors.joinToString("\n") { "- $it" }}"
    }

    private fun buildFullXml(title: String, fr: String, nfr: String, ac: String, edge: String): String {
        return "<requirements>\n<title>$title</title>\n$fr\n$nfr\n$ac\n$edge\n</requirements>"
    }

    private fun extractTitle(task: String): String {
        val firstLine = task.lines().firstOrNull { it.isNotBlank() }?.take(60) ?: "Спецификация"
        return if (firstLine.length > 50) firstLine.take(50) + "..." else firstLine
    }

    private fun parseClarifications(xml: String): List<ClarificationQuestion> {
        return Regex("""<clarification>\s*<question\s+id="([^"]+)">([\s\S]*?)</question>(?:\s*<options>([\s\S]*?)</options>)?[\s\S]*?</clarification>""")
            .findAll(xml)
            .map { m ->
                val options = m.groupValues[3].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                ClarificationQuestion(id = m.groupValues[1], text = m.groupValues[2].trim(), options = options)
            }.toList()
    }

    private fun parseSpec(xml: String, fallbackTitle: String): SpecDocument? {
        return try {
            val parser = StreamParser()
            val events = parser.feed(xml) + parser.finalize()
            events.filterIsInstance<StreamParser.ParseEvent.SpecReady>().firstOrNull()?.spec
                ?: run {
                    // Попробуем собрать вручную из секций если парсер не нашёл тег
                    val wrapped = if ("<requirements>" in xml) xml else "<requirements><title>$fallbackTitle</title>$xml</requirements>"
                    val events2 = StreamParser().feed(wrapped) + StreamParser().finalize()
                    events2.filterIsInstance<StreamParser.ParseEvent.SpecReady>().firstOrNull()?.spec
                }
        } catch (e: Exception) { null }
    }
}
