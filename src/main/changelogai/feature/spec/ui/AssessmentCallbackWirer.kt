package changelogai.feature.spec.ui

import changelogai.core.confluence.ConfluenceContext
import changelogai.feature.spec.confluence.AssessmentState
import changelogai.feature.spec.confluence.ConfluenceAssessmentOrchestrator
import changelogai.feature.spec.engine.SpecOrchestrator
import changelogai.feature.spec.model.SpecState
import changelogai.core.skill.SkillDefinition
import changelogai.feature.kb.KnowledgeBaseService
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * Wires all [ConfluenceAssessmentOrchestrator] callbacks to UI panels.
 * Extracted from SpecPanel to keep it thin.
 */
internal class AssessmentCallbackWirer(
    private val project: Project,
    private val assessmentEngine: ConfluenceAssessmentOrchestrator,
    private val engine: SpecOrchestrator,
    private val reasoningPanel: ReasoningLogPanel,
    private val agentBar: AgentStatusBar,
    private val assessmentPanel: AssessmentPanel,
    private val stateCtrl: SpecStateController,
    private val skillContext: () -> String,
    private val confluenceCtx: () -> ConfluenceContext?,
    private val onRefineRequested: (prefillTask: String, combinedCtx: String, confluenceCtx: ConfluenceContext?) -> Unit
) {
    fun wire() {
        assessmentEngine.onStepAdded = { step ->
            SwingUtilities.invokeLater {
                reasoningPanel.rebuild(engine.steps.toList() + listOf(step))
            }
        }
        assessmentEngine.onAgentStarted = { n, ic ->
            SwingUtilities.invokeLater { agentBar.agentStarted(n, ic) }
        }
        assessmentEngine.onAgentFinished = { n ->
            SwingUtilities.invokeLater { agentBar.agentFinished(n) }
        }
        assessmentEngine.onReportReady = { report ->
            SwingUtilities.invokeLater {
                assessmentPanel.showReport(report)
                assessmentPanel.onRefineRequested = { prefillTask, contextSummary ->
                    val baseSkillCtx = skillContext()
                    val combinedCtx = if (contextSummary.isNotBlank()) "$baseSkillCtx\n\n$contextSummary" else baseSkillCtx
                    onRefineRequested(prefillTask, combinedCtx, confluenceCtx())
                }
                stateCtrl.setStatus(SpecState.COMPLETE, "Оценка завершена")
                stateCtrl.applyState(SpecState.COMPLETE)
            }
        }
        assessmentEngine.onError = { m ->
            SwingUtilities.invokeLater { stateCtrl.setStatus(SpecState.ERROR, m) }
        }
        assessmentEngine.onStateChanged = { s ->
            SwingUtilities.invokeLater {
                when (s) {
                    AssessmentState.EXTRACTING  -> stateCtrl.setStatus(SpecState.ANALYZING, "Извлекаю требования...")
                    AssessmentState.VALIDATING  -> stateCtrl.setStatus(SpecState.ANALYZING, "Валидирую структуру...")
                    AssessmentState.ASSESSING   -> stateCtrl.setStatus(SpecState.ANALYZING, "Оцениваю качество...")
                    AssessmentState.FILLING_GAPS -> stateCtrl.setStatus(SpecState.GENERATING, "Генерирую недостающее...")
                    AssessmentState.ESTIMATING  -> stateCtrl.setStatus(SpecState.GENERATING, "Оцениваю трудозатраты...")
                    AssessmentState.COMPLETE    -> stateCtrl.setStatus(SpecState.COMPLETE, "Готово")
                    AssessmentState.ERROR       -> stateCtrl.setStatus(SpecState.ERROR, "Ошибка оценки")
                    AssessmentState.IDLE        -> Unit
                }
            }
        }
    }
}
