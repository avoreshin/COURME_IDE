package changelogai.feature.spec.confluence

import changelogai.feature.spec.agents.ConfluenceSpecExtractorAgent
import changelogai.feature.spec.agents.EffortEstimatorAgent
import changelogai.feature.spec.agents.GapFillerAgent
import changelogai.feature.spec.agents.QualityAssessorAgent
import changelogai.feature.spec.engine.SpecValidator
import changelogai.feature.spec.model.ReasoningStep
import changelogai.feature.spec.model.SpecDocument
import changelogai.core.llm.cancellation.AtomicCancelable
import com.intellij.openapi.project.Project
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Оркестратор оценки существующего ТЗ из Confluence.
 *
 * Фазы:
 *   1. EXTRACT   — ConfluenceSpecExtractorAgent извлекает структуру из plain text
 *   2. VALIDATE  — SpecValidator проверяет структуру
 *   3. ASSESS    — QualityAssessorAgent оценивает качество каждого требования
 *   4. FILL GAPS — GapFillerAgent генерирует недостающие секции (если нужно)
 *   5. ESTIMATE  — EffortEstimatorAgent оценивает трудозатраты по FR
 *   6. REPORT    — сборка итогового AssessmentReport
 */
class ConfluenceAssessmentOrchestrator(
    @Suppress("UNUSED_PARAMETER") private val project: Project
) {

    private val executor = Executors.newCachedThreadPool()
    private val cancelable = AtomicReference<AtomicCancelable?>(null)

    private val extractorAgent  = ConfluenceSpecExtractorAgent()
    private val assessorAgent   = QualityAssessorAgent()
    private val gapFillerAgent  = GapFillerAgent()
    private val estimatorAgent  = EffortEstimatorAgent()

    // ---- Callbacks ─────────────────────────────────────────────────────────
    var onStateChanged: ((AssessmentState) -> Unit)? = null
    var onStepAdded:    ((ReasoningStep) -> Unit)?   = null
    var onReportReady:  ((AssessmentReport) -> Unit)? = null
    var onError:        ((String) -> Unit)?           = null
    var onAgentStarted: ((String, String) -> Unit)?  = null
    var onAgentFinished:((String) -> Unit)?          = null

    // ---- State ─────────────────────────────────────────────────────────────
    @Volatile var state: AssessmentState = AssessmentState.IDLE
        private set

    private var currentFuture: Future<*>? = null

    // ---- Public API ────────────────────────────────────────────────────────

    private var skillCtx: String = ""

    fun startAssessment(ctx: ConfluenceContext, skillCtx: String = "") {
        this.skillCtx = skillCtx
        val c = AtomicCancelable()
        cancelable.set(c)
        transition(AssessmentState.EXTRACTING)
        currentFuture = executor.submit {
            try {
                runPipeline(ctx, c)
            } catch (e: Exception) {
                if (!c.isCanceled()) {
                    transition(AssessmentState.ERROR)
                    onError?.invoke(e.message ?: "Неизвестная ошибка")
                }
            }
        }
    }

    fun cancel() {
        cancelable.get()?.cancel()
        transition(AssessmentState.IDLE)
        currentFuture?.cancel(true)
    }

    // ---- Pipeline ──────────────────────────────────────────────────────────

    private fun runPipeline(ctx: ConfluenceContext, c: AtomicCancelable) {
        // Phase 1: Extract
        addStep(ReasoningStep.StepType.THINKING, "📄 Извлекаю структуру требований из Confluence: ${ctx.pageTitle}")
        onAgentStarted?.invoke(extractorAgent.name, extractorAgent.icon)
        val extractRaw = extractorAgent.run(buildExtractorPrompt(ctx), c)
        onAgentFinished?.invoke(extractorAgent.name)
        if (c.isCanceled()) return

        val extracted = parseExtractedSpec(extractRaw)
        addStep(ReasoningStep.StepType.OUTPUT,
            "Извлечено: ${extracted.functional.size} FR, ${extracted.nonFunctional.size} NFR, " +
            "${extracted.acceptanceCriteria.size} AC, ${extracted.edgeCases.size} EDGE")

        // Phase 2: Validate structure
        transition(AssessmentState.VALIDATING)
        addStep(ReasoningStep.StepType.THINKING, "🔍 Валидирую структуру требований...")
        val validationResult = SpecValidator.validate(extracted)
        val crossWarnings = SpecValidator.crossCheckConfluence(extracted, ctx)
        addStep(
            ReasoningStep.StepType.OUTPUT,
            if (validationResult.isValid) "✓ Структура корректна"
            else "✗ Найдено ошибок: ${validationResult.errors.size}, предупреждений: ${validationResult.warnings.size}"
        )
        if (c.isCanceled()) return

        // Phase 3: Assess quality
        transition(AssessmentState.ASSESSING)
        addStep(ReasoningStep.StepType.THINKING, "🔎 Оцениваю качество каждого требования...")
        onAgentStarted?.invoke(assessorAgent.name, assessorAgent.icon)
        val assessRaw = assessorAgent.run(buildAssessorPrompt(extracted), c)
        onAgentFinished?.invoke(assessorAgent.name)
        if (c.isCanceled()) return

        val (qualityIssues, qualityScore, suggestions) = parseAssessment(assessRaw)
        addStep(ReasoningStep.StepType.OUTPUT, "Оценка качества: $qualityScore/100")

        // Phase 4: Fill gaps (только если есть структурные ошибки)
        val generatedSections = mutableMapOf<String, List<SpecDocument.Requirement>>()
        val missingSections = detectMissingSections(extracted, validationResult)
        if (missingSections.isNotEmpty() && !c.isCanceled()) {
            transition(AssessmentState.FILLING_GAPS)
            addStep(ReasoningStep.StepType.THINKING, "🔧 Генерирую недостающие секции: ${missingSections.joinToString(", ")}")
            onAgentStarted?.invoke(gapFillerAgent.name, gapFillerAgent.icon)
            val gapRaw = gapFillerAgent.run(buildGapFillerPrompt(extracted, ctx, missingSections), c)
            onAgentFinished?.invoke(gapFillerAgent.name)
            if (!c.isCanceled()) {
                val generated = parseGeneratedRequirements(gapRaw)
                generatedSections.putAll(generated)
                val totalGenerated = generated.values.sumOf { it.size }
                addStep(ReasoningStep.StepType.OUTPUT, "Сгенерировано $totalGenerated новых требований")
            }
        }

        if (c.isCanceled()) return

        // Phase 5: Estimate effort
        transition(AssessmentState.ESTIMATING)
        val allFr = extracted.functional + (generatedSections["FR"] ?: emptyList())
        addStep(ReasoningStep.StepType.THINKING, "⏱️ Оцениваю трудозатраты по ${allFr.size} функциональным требованиям...")
        onAgentStarted?.invoke(estimatorAgent.name, estimatorAgent.icon)
        val estimateRaw = estimatorAgent.run(buildEstimatorPrompt(allFr, ctx), c)
        onAgentFinished?.invoke(estimatorAgent.name)
        if (c.isCanceled()) return

        val effortEstimates = parseEffortEstimates(estimateRaw, allFr)
        val totalSp = effortEstimates.sumOf { it.storyPoints }
        addStep(ReasoningStep.StepType.OUTPUT, "Итого трудозатрат: $totalSp story points")

        // Phase 6: Build report
        val overallScore = calcOverallScore(qualityScore, validationResult, effortEstimates)
        val report = AssessmentReport(
            pageTitle = ctx.pageTitle,
            pageUrl = ctx.canonicalWebUrl,
            pageId = ctx.pageId,
            overallScore = overallScore,
            structureErrors = validationResult.errors,
            structureWarnings = validationResult.warnings + crossWarnings,
            qualityIssues = qualityIssues,
            generatedSections = generatedSections,
            effortEstimates = effortEstimates,
            suggestions = suggestions,
            extractedSpec = extracted
        )

        transition(AssessmentState.COMPLETE)
        addStep(ReasoningStep.StepType.OUTPUT, "✅ Оценка завершена. Score: $overallScore/100 (${report.scoreLabel}), " +
                "SP: $totalSp, проблем: ${qualityIssues.size}")
        onReportReady?.invoke(report)
    }

    // ---- Prompt Builders ───────────────────────────────────────────────────

    private fun buildExtractorPrompt(ctx: ConfluenceContext): String = buildString {
        appendLine("Страница Confluence: \"${ctx.pageTitle}\"")
        appendLine("URL: ${ctx.canonicalWebUrl}")
        appendLine()
        appendLine("=== СОДЕРЖИМОЕ СТРАНИЦЫ ===")
        appendLine(ctx.plainText)
    }

    private fun buildAssessorPrompt(spec: SpecDocument): String = buildString {
        if (skillCtx.isNotBlank()) {
            appendLine("## Дополнительный контекст эксперта")
            appendLine(skillCtx)
            appendLine()
        }
        appendLine("Оцени качество следующих требований:")
        appendLine()
        if (spec.functional.isNotEmpty()) {
            appendLine("## Функциональные требования (FR)")
            spec.functional.forEach { appendLine("${it.id} [${it.priority}]: ${it.description}") }
        }
        if (spec.nonFunctional.isNotEmpty()) {
            appendLine()
            appendLine("## Нефункциональные требования (NFR)")
            spec.nonFunctional.forEach { appendLine("${it.id} [${it.priority}]: ${it.description}") }
        }
        if (spec.acceptanceCriteria.isNotEmpty()) {
            appendLine()
            appendLine("## Критерии приёмки (AC)")
            spec.acceptanceCriteria.forEach { appendLine("${it.id}: ${it.description}") }
        }
        if (spec.edgeCases.isNotEmpty()) {
            appendLine()
            appendLine("## Граничные случаи (EDGE)")
            spec.edgeCases.forEach { appendLine("${it.id} [${it.priority}]: ${it.description}") }
        }
    }

    private fun buildGapFillerPrompt(
        spec: SpecDocument,
        ctx: ConfluenceContext,
        missingSections: List<String>
    ): String = buildString {
        appendLine("Контекст из Confluence: ${ctx.pageTitle}")
        appendLine(ctx.plainText.take(3000))
        appendLine()
        appendLine("Существующие требования:")
        if (spec.functional.isNotEmpty())
            spec.functional.forEach { appendLine("${it.id}: ${it.description}") }
        if (spec.nonFunctional.isNotEmpty())
            spec.nonFunctional.forEach { appendLine("${it.id}: ${it.description}") }
        appendLine()
        appendLine("ЗАДАЧА: Сгенерируй недостающие секции: ${missingSections.joinToString(", ")}")
        appendLine("Продолжай нумерацию:")
        appendLine("  FR: начиная с FR-${String.format("%03d", spec.functional.size + 1)}")
        appendLine("  NFR: начиная с NFR-${String.format("%03d", spec.nonFunctional.size + 1)}")
        appendLine("  AC: начиная с AC-${String.format("%03d", spec.acceptanceCriteria.size + 1)}")
        appendLine("  EDGE: начиная с EDGE-${String.format("%03d", spec.edgeCases.size + 1)}")
    }

    private fun buildEstimatorPrompt(frList: List<SpecDocument.Requirement>, ctx: ConfluenceContext): String = buildString {
        appendLine("Контекст проекта: ${ctx.pageTitle}")
        appendLine()
        appendLine("Функциональные требования для оценки:")
        frList.forEach { appendLine("${it.id} [${it.priority}]: ${it.description}") }
    }

    // ---- Parsers ───────────────────────────────────────────────────────────

    private fun parseExtractedSpec(raw: String): SpecDocument {
        val title = Regex("""<spec_title>([\s\S]*?)</spec_title>""").find(raw)
            ?.groupValues?.get(1)?.trim() ?: "Спецификация"
        val reqBlock = Regex("""<requirements>([\s\S]*?)</requirements>""").find(raw)
            ?.groupValues?.get(1) ?: ""

        fun parseItems(tag: String): List<SpecDocument.Requirement> =
            Regex("""<$tag\s+id="([^"]+)"(?:\s+priority="([^"]+)")?\s*>([\s\S]*?)</$tag>""")
                .findAll(reqBlock)
                .map { m ->
                    SpecDocument.Requirement(
                        id = m.groupValues[1],
                        description = m.groupValues[3].trim(),
                        priority = parsePriority(m.groupValues[2])
                    )
                }.toList()

        return SpecDocument(
            title = title,
            functional = parseItems("fr"),
            nonFunctional = parseItems("nfr"),
            acceptanceCriteria = parseItems("ac"),
            edgeCases = parseItems("edge")
        )
    }

    data class AssessmentParseResult(
        val issues: List<QualityIssue>,
        val score: Int,
        val suggestions: List<String>
    )

    private fun parseAssessment(raw: String): AssessmentParseResult {
        val score = Regex("""<overall_score>(\d+)</overall_score>""").find(raw)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 70

        val issues = Regex("""<issue\s+req_id="([^"]+)"\s+severity="([^"]+)">([\s\S]*?)</issue>""")
            .findAll(raw)
            .map { m ->
                val reqId = m.groupValues[1]
                val severity = parseIssueSeverity(m.groupValues[2])
                val inner = m.groupValues[3]
                val message = Regex("""<message>([\s\S]*?)</message>""").find(inner)
                    ?.groupValues?.get(1)?.trim() ?: ""
                val suggestion = Regex("""<suggestion>([\s\S]*?)</suggestion>""").find(inner)
                    ?.groupValues?.get(1)?.trim() ?: ""
                QualityIssue(requirementId = reqId, severity = severity, message = message, suggestion = suggestion)
            }.toList()

        val suggestions = Regex("""<general_suggestion>([\s\S]*?)</general_suggestion>""")
            .findAll(raw)
            .map { it.groupValues[1].trim() }
            .toList()

        return AssessmentParseResult(issues, score.coerceIn(0, 100), suggestions)
    }

    private fun parseGeneratedRequirements(raw: String): Map<String, List<SpecDocument.Requirement>> {
        val block = Regex("""<generated_requirements>([\s\S]*?)</generated_requirements>""").find(raw)
            ?.groupValues?.get(1) ?: return emptyMap()

        fun parseItems(tag: String): List<SpecDocument.Requirement> =
            Regex("""<$tag\s+id="([^"]+)"(?:\s+priority="([^"]+)")?\s*>([\s\S]*?)</$tag>""")
                .findAll(block)
                .map { m ->
                    SpecDocument.Requirement(
                        id = m.groupValues[1],
                        description = m.groupValues[3].trim(),
                        priority = parsePriority(m.groupValues[2])
                    )
                }.toList()

        val result = mutableMapOf<String, List<SpecDocument.Requirement>>()
        parseItems("fr").takeIf { it.isNotEmpty() }?.let { result["FR"] = it }
        parseItems("nfr").takeIf { it.isNotEmpty() }?.let { result["NFR"] = it }
        parseItems("ac").takeIf { it.isNotEmpty() }?.let { result["AC"] = it }
        parseItems("edge").takeIf { it.isNotEmpty() }?.let { result["EDGE"] = it }
        return result
    }

    private fun parseEffortEstimates(
        raw: String,
        frList: List<SpecDocument.Requirement>
    ): List<EffortEstimate> {
        val frMap = frList.associateBy { it.id }
        return Regex("""<estimate\s+req_id="([^"]+)"\s+story_points="(\d+)"\s+complexity="([^"]+)">([\s\S]*?)</estimate>""")
            .findAll(raw)
            .map { m ->
                val reqId = m.groupValues[1]
                val sp = m.groupValues[2].toIntOrNull() ?: 3
                val complexity = parseComplexity(m.groupValues[3])
                val rationale = Regex("""<rationale>([\s\S]*?)</rationale>""").find(m.groupValues[4])
                    ?.groupValues?.get(1)?.trim() ?: ""
                EffortEstimate(
                    requirementId = reqId,
                    description = frMap[reqId]?.description?.take(80) ?: reqId,
                    storyPoints = sp.coerceIn(1, 13),
                    complexity = complexity,
                    rationale = rationale
                )
            }.toList()
    }

    // ---- Helpers ───────────────────────────────────────────────────────────

    private fun detectMissingSections(spec: SpecDocument, result: SpecValidator.ValidationResult): List<String> {
        val missing = mutableListOf<String>()
        if (spec.nonFunctional.size < 3) missing += "NFR"
        if (spec.acceptanceCriteria.size < 3) missing += "AC"
        if (spec.edgeCases.isEmpty()) missing += "EDGE"
        return missing
    }

    private fun calcOverallScore(
        qualityScore: Int,
        validation: SpecValidator.ValidationResult,
        efforts: List<EffortEstimate>
    ): Int {
        var score = qualityScore
        score -= validation.errors.size * 10
        score -= validation.warnings.size * 3
        return score.coerceIn(0, 100)
    }

    private fun transition(newState: AssessmentState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    private fun addStep(type: ReasoningStep.StepType, text: String) {
        onStepAdded?.invoke(ReasoningStep(type = type, text = text))
    }

    private fun parsePriority(s: String): SpecDocument.Priority = when (s.uppercase()) {
        "CRITICAL" -> SpecDocument.Priority.CRITICAL
        "HIGH"     -> SpecDocument.Priority.HIGH
        "LOW"      -> SpecDocument.Priority.LOW
        else       -> SpecDocument.Priority.MEDIUM
    }

    private fun parseIssueSeverity(s: String): IssueSeverity = when (s.uppercase()) {
        "ERROR"   -> IssueSeverity.ERROR
        "WARNING" -> IssueSeverity.WARNING
        else      -> IssueSeverity.INFO
    }

    private fun parseComplexity(s: String): Complexity = when (s.uppercase()) {
        "HIGH"   -> Complexity.HIGH
        "LOW"    -> Complexity.LOW
        else     -> Complexity.MEDIUM
    }
}
