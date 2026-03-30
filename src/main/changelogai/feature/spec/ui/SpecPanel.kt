package changelogai.feature.spec.ui

import changelogai.core.llm.debug.LLMTraceDialog
import changelogai.feature.spec.confluence.ConfluenceAssessmentOrchestrator
import changelogai.feature.spec.engine.SpecFormatter
import changelogai.feature.spec.engine.SpecOrchestrator
import changelogai.feature.spec.engine.SpecValidator
import changelogai.feature.spec.model.ClarificationQuestion
import changelogai.feature.spec.model.ReasoningStep
import changelogai.feature.spec.model.SpecDocument
import changelogai.feature.spec.model.SpecSession
import changelogai.feature.spec.model.SpecState
import changelogai.feature.spec.SpecSessionRepository
import changelogai.feature.kb.KnowledgeBaseService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// ═══════════════════════════════════════════════════════════════════════════
// SpecPanel — thin coordinator, delegates to focused controllers
// ═══════════════════════════════════════════════════════════════════════════

class SpecPanel(private val project: Project) {

    private val engine           = SpecOrchestrator(project)
    private val assessmentEngine = ConfluenceAssessmentOrchestrator(project)
    private val formatter        = SpecFormatter()
    private val repository       = SpecSessionRepository.getInstance(project)

    // ── Controllers ────────────────────────────────────────────────────────
    private val confluenceCtrl = ConfluenceLoadingController(project) { panel }
    private val skillCtrl      = SkillSelectorController(project)
    private val exportCtrl     = SpecExportController(project, engine, formatter)
    private val sessionCtrl    = SpecSessionController(repository, engine) { taskInput.text.trim() }

    // ── Mode toggle ────────────────────────────────────────────────────────
    private val modeGenerate   = JRadioButton("Генерация спеки").apply { isSelected = true; isOpaque = false }
    private val modeValidation = JRadioButton("Валидация ТЗ").apply { isOpaque = false }
    @Suppress("unused")
    private val modeGroup = ButtonGroup().apply { add(modeGenerate); add(modeValidation) }

    // ── Input ──────────────────────────────────────────────────────────────
    private val taskInput   = JBTextArea(4, 40).apply {
        lineWrap = true; wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(13))
        setBorder(JBUI.Borders.empty(8))
    }
    private val analyzeBtn  = JButton("Анализировать", AllIcons.Actions.Lightning)
    private val cancelBtn   = JButton("Отмена", AllIcons.Actions.Cancel).apply { isEnabled = false }
    private val statusDot   = JLabel().apply { preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16)) }
    private val statusLabel = JBLabel("Готов к анализу").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12)); foreground = JBColor.GRAY
    }
    private val agentBar    = AgentStatusBar()

    // ── State controller (depends on buttons declared above) ───────────────
    private val stateCtrl = SpecStateController(
        statusDot, statusLabel, analyzeBtn, cancelBtn,
        exportCtrl.copyBtn, exportCtrl.saveBtn, exportCtrl.sendCodeBtn
    )

    // ── Content ────────────────────────────────────────────────────────────
    private val reasoningPanel   = ReasoningLogPanel()
    private val specViewPanel    = SpecViewPanel()
    private val prdPanel         = PrdPanel()
    private val diagramsPanel    = DiagramsPanel()
    private val validationBanner = ValidationBanner()
    private val refinementArea   = RefinementArea()
    private val assessmentPanel  = AssessmentPanel()

    private val tabs = JTabbedPane().apply {
        addTab("Рассуждения",  AllIcons.General.Information,    JBScrollPane(reasoningPanel))
        addTab("Спецификация", AllIcons.FileTypes.Text,         JBScrollPane(specViewPanel))
        addTab("PRD / TRD",    AllIcons.FileTypes.Unknown,      JBScrollPane(prdPanel))
        addTab("Диаграммы",    AllIcons.FileTypes.Diagram,      JBScrollPane(diagramsPanel))
        addTab("Оценка ТЗ",    AllIcons.Actions.PreviewDetails, JBScrollPane(assessmentPanel))
    }

    private val cardLayout      = CardLayout()
    private val centerCard      = JPanel(cardLayout)
    private val questionsScreen = WizardQuestionsScreen()

    // ── Bottom bar extras ──────────────────────────────────────────────────
    private val resetBtn     = JButton("Сбросить", AllIcons.Actions.Rollback)
    private val inspectorBtn = JButton(AllIcons.Actions.Show).apply {
        toolTipText = "LLM Call Inspector — история запросов к LLM"
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
    }
    private val tokLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(11)); foreground = JBColor.GRAY
    }

    val panel: JPanel = build()
    init {
        confluenceCtrl.onSwitchToValidation = { ctx ->
            modeValidation.isSelected = true
            taskInput.isVisible = false
            tabs.selectedIndex = tabs.indexOfTab("Оценка ТЗ")
            wireAssessmentCallbacks()
            assessmentEngine.startAssessment(ctx, skillCtrl.selectedSkillContext)
        }
        sessionCtrl.onSessionRestored = { restoreSession(it) }
        exportCtrl.wireActions()
        wireCallbacks()
        wireActions()
        sessionCtrl.refresh()
    }

    // ── Build ──────────────────────────────────────────────────────────────

    private fun build(): JPanel {
        val root = JPanel(BorderLayout())

        val modeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(modeGenerate); add(modeValidation)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(confluenceCtrl.reconnectBtn)
        }
        val inputBox = JBScrollPane(taskInput).apply {
            preferredSize = Dimension(0, JBUI.scale(96))
            setBorder(BorderFactory.createLineBorder(JBColor.border(), 1, true))
        }
        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(sessionCtrl.newBtn); add(sessionCtrl.deleteBtn); add(sessionCtrl.sessionCombo)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(analyzeBtn); add(cancelBtn)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(statusDot); add(statusLabel)
            add(Box.createHorizontalGlue())
            add(skillCtrl.skillCombo); add(skillCtrl.editSkillBtn)
        }
        val inputCard = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            setBorder(JBUI.Borders.empty(10, 12, 6, 12))
            val northSection = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(modeRow,                     BorderLayout.NORTH)
                add(confluenceCtrl.confluenceRow, BorderLayout.CENTER)
            }
            add(northSection, BorderLayout.NORTH)
            add(inputBox,     BorderLayout.CENTER)
            add(btnRow,       BorderLayout.SOUTH)
        }
        val topSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputCard, BorderLayout.CENTER)
            add(agentBar,  BorderLayout.SOUTH)
        }

        val workPanel = JPanel(BorderLayout()).apply {
            add(validationBanner, BorderLayout.NORTH)
            add(tabs,             BorderLayout.CENTER)
            add(refinementArea,   BorderLayout.SOUTH)
        }
        centerCard.add(workPanel,       "work")
        centerCard.add(questionsScreen, "questions")

        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()))
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
                isOpaque = false
                add(exportCtrl.copyBtn); add(exportCtrl.saveBtn); add(resetBtn); add(exportCtrl.sendCodeBtn)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
                isOpaque = false; add(inspectorBtn); add(tokLabel)
            }
            add(left, BorderLayout.WEST); add(right, BorderLayout.EAST)
        }

        root.add(topSection, BorderLayout.NORTH)
        root.add(centerCard, BorderLayout.CENTER)
        root.add(bottomBar,  BorderLayout.SOUTH)
        return root
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun wireActions() {
        analyzeBtn.addActionListener { startAnalysis() }
        cancelBtn .addActionListener { engine.cancel(); assessmentEngine.cancel() }
        resetBtn  .addActionListener { reset() }
        modeGenerate.addActionListener {
            taskInput.isVisible = true
            confluenceCtrl.confluenceRow.isVisible = false
            confluenceCtrl.confluenceRow.revalidate(); confluenceCtrl.confluenceRow.repaint()
        }
        modeValidation.addActionListener {
            taskInput.isVisible = false
            confluenceCtrl.confluenceRow.isVisible = true
            confluenceCtrl.confluenceRow.revalidate(); confluenceCtrl.confluenceRow.repaint()
            skillCtrl.selectValidationSkill()
        }
        taskInput.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if ((e.isMetaDown || e.isControlDown) && e.keyCode == KeyEvent.VK_ENTER) startAnalysis()
            }
        })
        questionsScreen.onSubmit = { answers -> engine.submitAnswers(answers) }
        refinementArea.onRefine  = { text -> refinementArea.setGenerating(true); engine.refineWithContext(text) }
        inspectorBtn.addActionListener { LLMTraceDialog(project).show() }
        sessionCtrl.wireActions()
    }

    // ── Analysis flow ──────────────────────────────────────────────────────

    private fun startAnalysis() {
        val urlText = confluenceCtrl.urlField.text.trim()
        if (modeValidation.isSelected && urlText.isBlank()) {
            confluenceCtrl.setStatus("⚠ Введите URL Confluence для режима валидации", JBColor.RED)
            return
        }
        if (modeGenerate.isSelected) {
            val text = taskInput.text.trim().ifEmpty { return }
            clearContentPanels()
            val skillContext = skillCtrl.selectedSkillContext

            if (urlText.isBlank()) {
                analyzeBtn.isEnabled = false
                ApplicationManager.getApplication().executeOnPooledThread {
                    val kbResults = searchKb(text)
                    engine.startAnalysis(text, skillContext, null, kbResults)
                    SwingUtilities.invokeLater { analyzeBtn.isEnabled = true }
                }
                return
            }
            analyzeBtn.isEnabled = false
            confluenceCtrl.setStatus("⏳ Загружаю Confluence...", JBColor.GRAY)
            confluenceCtrl.fetchAsync(urlText) { result ->
                analyzeBtn.isEnabled = true
                confluenceCtrl.handleFetchResult(result, isGenerateMode = true) { ctx ->
                    clearContentPanels()
                    val kbResults = searchKb(text)
                    engine.startAnalysis(text, skillContext, ctx, kbResults)
                }
            }
        } else {
            analyzeBtn.isEnabled = false
            confluenceCtrl.setStatus("⏳ Загружаю Confluence...", JBColor.GRAY)
            confluenceCtrl.fetchAsync(urlText) { result ->
                analyzeBtn.isEnabled = true
                confluenceCtrl.handleFetchResult(result, isGenerateMode = false) { ctx ->
                    if (ctx == null) return@handleFetchResult
                    reasoningPanel.clear(); agentBar.clear(); assessmentPanel.clear()
                    cardLayout.show(centerCard, "work")
                    tabs.selectedIndex = tabs.indexOfTab("Оценка ТЗ")
                    wireAssessmentCallbacks()
                    assessmentEngine.startAssessment(ctx, skillCtrl.selectedSkillContext)
                }
            }
        }
    }

    private fun clearContentPanels() {
        reasoningPanel.clear(); specViewPanel.clear(); prdPanel.clear()
        validationBanner.dismiss(); agentBar.clear()
        refinementArea.setGenerating(false); refinementArea.isVisible = false
        cardLayout.show(centerCard, "work")
    }

    private fun searchKb(text: String) = try {
        val kbService = KnowledgeBaseService.getInstance(project)
        if (kbService.isIndexed()) kbService.search(text, topK = 5) else emptyList()
    } catch (_: Exception) { emptyList() }

    // ── Assessment callbacks ────────────────────────────────────────────────

    private fun wireAssessmentCallbacks() {
        AssessmentCallbackWirer(
            project         = project,
            assessmentEngine = assessmentEngine,
            engine          = engine,
            reasoningPanel  = reasoningPanel,
            agentBar        = agentBar,
            assessmentPanel = assessmentPanel,
            stateCtrl       = stateCtrl,
            skillContext    = { skillCtrl.selectedSkillContext },
            confluenceCtx   = { confluenceCtrl.currentCtx },
            onRefineRequested = { prefillTask, combinedCtx, confluenceCtx ->
                modeGenerate.isSelected = true
                taskInput.isVisible = true
                confluenceCtrl.confluenceRow.isVisible = false
                taskInput.text = prefillTask
                engine.reset()
                val kbResults = searchKb(prefillTask)
                engine.startAnalysis(prefillTask, combinedCtx, confluenceCtx, kbResults)
                cardLayout.show(centerCard, "work")
                tabs.selectedIndex = 0
            }
        ).wire()
    }

    // ── Reset ──────────────────────────────────────────────────────────────

    private fun reset() {
        engine.reset()
        taskInput.text = ""
        confluenceCtrl.reset()
        modeGenerate.isSelected = true; taskInput.isVisible = true
        reasoningPanel.clear(); specViewPanel.clear(); prdPanel.clear()
        assessmentPanel.clear()
        validationBanner.dismiss(); agentBar.clear()
        refinementArea.setGenerating(false); refinementArea.isVisible = false
        cardLayout.show(centerCard, "work")
        stateCtrl.setExport(false)
        stateCtrl.setStatus(SpecState.IDLE, "Готов к анализу")
    }

    // ── Engine callbacks ───────────────────────────────────────────────────

    private fun wireCallbacks() {
        engine.onStateChanged   = { s -> SwingUtilities.invokeLater {
            stateCtrl.applyState(s)
            if (s == SpecState.COMPLETE) refinementArea.setGenerating(false)
        }}
        engine.onStepAdded      = { _ -> SwingUtilities.invokeLater {
            reasoningPanel.rebuild(engine.steps.toList())
            tokLabel.text = "~${engine.steps.sumOf { it.text.length / 4 }} токенов"
        }}
        engine.onQuestionsReady = { qs -> SwingUtilities.invokeLater {
            questionsScreen.load(qs); cardLayout.show(centerCard, "questions")
        }}
        engine.onFollowUpQuestionsReady = { qs -> SwingUtilities.invokeLater {
            questionsScreen.appendQuestions(qs)
        }}
        engine.onSpecReady      = { sp -> SwingUtilities.invokeLater {
            specViewPanel.render(sp)
            cardLayout.show(centerCard, "work"); tabs.selectedIndex = 1
            stateCtrl.setExport(true); refinementArea.isVisible = true
            sessionCtrl.saveCurrentSession(sp, null, null)
        }}
        engine.onPrdReady       = { md -> SwingUtilities.invokeLater {
            prdPanel.render(md); tabs.selectedIndex = 2
            sessionCtrl.currentSession?.let { s -> s.prdDocument = md; repository.update(s) }
        }}
        engine.onValidation     = { r  -> SwingUtilities.invokeLater { validationBanner.showResult(r) } }
        engine.onError          = { m  -> SwingUtilities.invokeLater { stateCtrl.setStatus(SpecState.ERROR, m) } }
        engine.onAgentStarted   = { n, ic -> SwingUtilities.invokeLater { agentBar.agentStarted(n, ic) } }
        engine.onAgentFinished  = { n     -> SwingUtilities.invokeLater { agentBar.agentFinished(n) } }
        engine.onMermaidReady   = { md -> SwingUtilities.invokeLater {
            diagramsPanel.render(md)
            sessionCtrl.currentSession?.let { s -> s.mermaidDiagrams = md; repository.update(s) }
        }}
        engine.onIterationStarted = { n -> SwingUtilities.invokeLater {
            reasoningPanel.clear(); refinementArea.setGenerating(true)
            stateCtrl.setStatus(SpecState.ANALYZING, "Итерация #$n — улучшаю...")
        }}
    }

    // ── Session restore ────────────────────────────────────────────────────

    private fun restoreSession(session: SpecSession) {
        engine.reset()
        sessionCtrl.restoreSession(session)
        taskInput.text = session.taskDescription
        reasoningPanel.clear(); specViewPanel.clear(); prdPanel.clear(); diagramsPanel.clear()
        validationBanner.dismiss(); agentBar.clear()
        refinementArea.setGenerating(false); refinementArea.isVisible = false

        session.spec?.let { sp ->
            specViewPanel.render(sp); stateCtrl.setExport(true); refinementArea.isVisible = true; tabs.selectedIndex = 1
        }
        session.prdDocument?.let { prdPanel.render(it) }
        session.mermaidDiagrams?.let { diagramsPanel.render(it) }

        cardLayout.show(centerCard, "work")
        val stateMsg = if (session.spec != null) "Загружено из истории" else "Готов к анализу"
        val stateVal = if (session.spec != null) SpecState.COMPLETE else SpecState.IDLE
        stateCtrl.setStatus(stateVal, stateMsg)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// WizardQuestionsScreen — пошаговый мастер уточнений
// ═══════════════════════════════════════════════════════════════════════════

private class WizardQuestionsScreen : JPanel(BorderLayout()) {

    var onSubmit: ((Map<String, String>) -> Unit)? = null

    private val allQuestions = mutableListOf<ClarificationQuestion>()
    private val answers      = mutableMapOf<String, String>()
    private var currentIdx   = 0

    // ── Progress ─────────────────────────────────────────────────────────
    private val progressLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(11)); foreground = JBColor.GRAY
    }
    private val progressBar = JProgressBar(0, 100).apply {
        preferredSize = Dimension(0, JBUI.scale(3)); isBorderPainted = false
    }

    // ── Question & options ────────────────────────────────────────────────
    private val questionLabel = JLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(13))
        verticalAlignment = SwingConstants.TOP
    }
    private val optionsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
    }

    // ── Navigation ───────────────────────────────────────────────────────
    private val backBtn = JButton("Назад", AllIcons.Actions.Back).apply {
        isEnabled = false
        horizontalTextPosition = SwingConstants.RIGHT
    }
    private val nextBtn = JButton("Далее", AllIcons.Actions.Forward).apply {
        horizontalTextPosition = SwingConstants.LEFT
    }

    // ── Inner cards: wizard / processing ─────────────────────────────────
    private val innerCards   = CardLayout()
    private val innerPanel   = JPanel(innerCards)
    private val processingMsg = JLabel("Генерирую спецификацию...").apply {
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(13)); foreground = JBColor.GRAY
        horizontalAlignment = SwingConstants.CENTER
    }
    private val processingProgress = JProgressBar().apply { isIndeterminate = true; preferredSize = Dimension(200, JBUI.scale(4)) }

    init {
        val wizardCard = JPanel(BorderLayout()).apply {
            add(buildHeader(),  BorderLayout.NORTH)
            add(JBScrollPane(buildContent()).apply { setBorder(null) }, BorderLayout.CENTER)
            add(buildFooter(), BorderLayout.SOUTH)
        }
        val processingCard = buildProcessingCard()
        innerPanel.add(wizardCard,    "wizard")
        innerPanel.add(processingCard,"processing")
        add(innerPanel, BorderLayout.CENTER)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun load(qs: List<ClarificationQuestion>) {
        allQuestions.clear(); answers.clear(); currentIdx = 0
        allQuestions.addAll(qs)
        innerCards.show(innerPanel, "wizard")
        showCurrent()
    }

    fun appendQuestions(qs: List<ClarificationQuestion>) {
        val startIdx = allQuestions.size
        allQuestions.addAll(qs)
        currentIdx = startIdx
        innerCards.show(innerPanel, "wizard")
        showCurrent()
    }

    fun showProcessing(msg: String = "Генерирую спецификацию...") {
        processingMsg.text = msg
        innerCards.show(innerPanel, "processing")
    }

    // ── Question display ──────────────────────────────────────────────────

    private fun showCurrent() {
        if (allQuestions.isEmpty()) return
        val q     = allQuestions[currentIdx]
        val total = allQuestions.size

        progressLabel.text  = "Вопрос ${currentIdx + 1} из $total"
        progressBar.value   = ((currentIdx + 1) * 100) / total
        questionLabel.text  = "<html><body style='width:320px; font-size:${JBUI.scale(13)}px'>${q.text}</body></html>"

        buildOptionsForQuestion(q)

        backBtn.isEnabled = currentIdx > 0
        nextBtn.text      = if (currentIdx == total - 1) "Сгенерировать" else "Далее"
        nextBtn.icon      = if (currentIdx == total - 1) AllIcons.Actions.Execute else AllIcons.Actions.Forward
    }

    private fun buildOptionsForQuestion(q: ClarificationQuestion) {
        optionsContainer.removeAll()
        val selected = answers[q.id]

        if (q.options.isNotEmpty()) {
            q.options.forEach { opt ->
                val card = optionCard(opt, opt == selected) {
                    answers[q.id] = opt
                    buildOptionsForQuestion(q)
                    optionsContainer.revalidate(); optionsContainer.repaint()
                }
                card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
                card.alignmentX  = Component.LEFT_ALIGNMENT
                optionsContainer.add(card)
                optionsContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        } else {
            val existingText = answers[q.id] ?: ""
            val ta = JBTextArea(3, 30).apply {
                lineWrap = true; wrapStyleWord = true
                font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))
                setBorder(JBUI.Borders.empty(8)); text = existingText
            }
            val wrap = JBScrollPane(ta).apply {
                setBorder(JBUI.Borders.customLine(JBColor.border(), 1))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
                alignmentX  = Component.LEFT_ALIGNMENT
            }
            ta.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent)  { answers[q.id] = ta.text }
                override fun removeUpdate(e: DocumentEvent)  { answers[q.id] = ta.text }
                override fun changedUpdate(e: DocumentEvent) {}
            })
            optionsContainer.add(wrap)
        }
        optionsContainer.revalidate(); optionsContainer.repaint()
    }

    // ── Option card ───────────────────────────────────────────────────────

    private fun optionCard(text: String, selected: Boolean, onClick: () -> Unit): JPanel {
        val accent = JBColor(Color(37, 99, 235), Color(99, 155, 255))
        val selectedBg  = JBColor(Color(235, 242, 255), Color(22, 40, 75))
        val normalBorder  = BorderFactory.createLineBorder(JBColor.border(), 1, true)
        val selectedBorder = BorderFactory.createLineBorder(accent, 2, true)
        val innerPad = JBUI.Borders.empty(10, 14)

        val panel = object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
            override fun paintComponent(g: Graphics) {
                if (selected) {
                    val g2 = g as Graphics2D
                    g2.color = selectedBg
                    g2.fillRect(0, 0, width, height)
                }
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            setBorder(BorderFactory.createCompoundBorder(
                if (selected) selectedBorder else normalBorder, innerPad
            ))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val indicator = JLabel(if (selected) "●" else "○").apply {
            font      = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(16))
            foreground = if (selected) accent else JBColor.GRAY
            preferredSize = Dimension(JBUI.scale(22), preferredSize.height)
        }
        val lbl = JLabel("<html><body style='font-size:${JBUI.scale(12)}px'>$text</body></html>").apply {
            foreground = UIManager.getColor("Label.foreground")
        }
        panel.add(indicator, BorderLayout.WEST)
        panel.add(lbl,       BorderLayout.CENTER)
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { onClick() }
            override fun mouseEntered(e: MouseEvent) {
                if (!selected) panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(accent, 1, true), innerPad
                ))
            }
            override fun mouseExited(e: MouseEvent) {
                if (!selected) panel.setBorder(BorderFactory.createCompoundBorder(normalBorder, innerPad))
            }
        })
        return panel
    }

    // ── Builders ──────────────────────────────────────────────────────────

    private fun buildHeader(): JPanel = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
        setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(10, 16, 8, 16)
        ))
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Уточняющие вопросы", AllIcons.General.QuestionDialog, SwingConstants.LEFT).apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(13))
            }, BorderLayout.WEST)
            add(progressLabel, BorderLayout.EAST)
        }
        add(titleRow,   BorderLayout.NORTH)
        add(progressBar, BorderLayout.SOUTH)
    }

    private fun buildContent(): JPanel = JPanel(BorderLayout(0, JBUI.scale(16))).apply {
        isOpaque = false
        setBorder(JBUI.Borders.empty(20, 20, 12, 20))
        add(questionLabel,    BorderLayout.NORTH)
        add(optionsContainer, BorderLayout.CENTER)
    }

    private fun buildFooter(): JPanel = JPanel(BorderLayout()).apply {
        setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(10, 16)
        ))
        val skipBtn = JButton("Пропустить всё").apply {
            foreground = JBColor.GRAY
            addActionListener { showProcessing(); onSubmit?.invoke(emptyMap()) }
        }
        backBtn.addActionListener { if (currentIdx > 0) { currentIdx--; showCurrent() } }
        nextBtn.addActionListener {
            if (currentIdx < allQuestions.size - 1) {
                currentIdx++; showCurrent()
            } else {
                showProcessing()
                onSubmit?.invoke(answers.toMap())
            }
        }
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false; add(skipBtn); add(backBtn); add(nextBtn)
        }
        add(row, BorderLayout.EAST)
    }

    private fun buildProcessingCard(): JPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL
            gridy = GridBagConstraints.RELATIVE
            insets = Insets(JBUI.scale(4), 0, JBUI.scale(4), 0)
        }
        add(processingProgress, gbc)
        add(processingMsg,      gbc)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// RefinementArea — уточнение после генерации спецификации
// ═══════════════════════════════════════════════════════════════════════════

private class RefinementArea : JPanel(BorderLayout(JBUI.scale(8), 0)) {

    var onRefine: ((String) -> Unit)? = null

    private val inputField = JBTextArea(2, 30).apply {
        lineWrap = true; wrapStyleWord = true
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))
        setBorder(JBUI.Borders.empty(6, 8))
        putClientProperty("JTextArea.placeholderText", "Добавить уточнение или правки к ТЗ...")
    }
    private val submitBtn = JButton("Уточнить", AllIcons.Actions.Refresh).apply { isEnabled = false }
    private val hintLabel = JBLabel("Уточнить требования").apply {
        font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(11)); foreground = JBColor.GRAY
    }

    init {
        isVisible = false
        setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(8, 12)
        ))

        val scroll = JBScrollPane(inputField).apply {
            setBorder(JBUI.Borders.customLine(JBColor.border(), 1))
            preferredSize = Dimension(0, JBUI.scale(54))
        }
        submitBtn.addActionListener {
            val text = inputField.text.trim()
            if (text.isNotEmpty()) {
                onRefine?.invoke(text)
                inputField.text = ""
                submitBtn.isEnabled = false
            }
        }
        inputField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  { submitBtn.isEnabled = inputField.text.isNotBlank() }
            override fun removeUpdate(e: DocumentEvent)  { submitBtn.isEnabled = inputField.text.isNotBlank() }
            override fun changedUpdate(e: DocumentEvent) {}
        })

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(hintLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(submitBtn)
        }
        add(scroll,      BorderLayout.CENTER)
        add(rightPanel,  BorderLayout.EAST)
    }

    fun setGenerating(generating: Boolean) {
        submitBtn.isEnabled = !generating && inputField.text.isNotBlank()
        hintLabel.text = if (generating) "Генерирую..." else "Уточнить требования"
        hintLabel.foreground = if (generating) JBColor(Color(37,99,235), Color(99,155,255)) else JBColor.GRAY
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// AgentStatusBar — показывает только последние N агентов
// ═══════════════════════════════════════════════════════════════════════════

internal class AgentStatusBar : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))) {
    private val MAX_VISIBLE = 6
    // order-preserving: last added = last in list
    private val allChips = LinkedHashMap<String, JLabel>()

    init { isOpaque = false; isVisible = false; setBorder(JBUI.Borders.empty(0, 10, 4, 10)) }

    fun agentStarted(name: String, icon: String) {
        val lbl = makeChip("$icon $name…", running = true)
        allChips[name] = lbl
        rebuildVisible()
        isVisible = true; revalidate(); repaint()
    }

    fun agentFinished(name: String) {
        val lbl = allChips[name] ?: return
        lbl.text       = lbl.text.removeSuffix("…")
        lbl.icon       = AllIcons.RunConfigurations.TestPassed
        lbl.foreground = JBColor(Color(34,139,68), Color(72,199,116))
        lbl.setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLine(lbl.foreground, 1),
            JBUI.Borders.empty(1, 5)
        ))
        rebuildVisible()
    }

    fun clear() { allChips.clear(); removeAll(); isVisible = false; revalidate(); repaint() }

    /** Показываем только последние MAX_VISIBLE чипов */
    private fun rebuildVisible() {
        removeAll()
        val entries = allChips.entries.toList()
        val start = (entries.size - MAX_VISIBLE).coerceAtLeast(0)
        if (start > 0) {
            add(JLabel("…+$start").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(10))
                foreground = JBColor.GRAY
            })
        }
        entries.drop(start).forEach { (_, lbl) -> add(lbl) }
        revalidate(); repaint()
    }

    private fun makeChip(text: String, running: Boolean) = JLabel(text).apply {
        icon = if (running) AllIcons.Process.Step_1 else null
        horizontalTextPosition = SwingConstants.RIGHT
        font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(11))
        foreground = if (running) JBColor(Color(37,99,235), Color(99,155,255))
                     else JBColor(Color(34,139,68), Color(72,199,116))
        setBorder(JBUI.Borders.compound(
            JBUI.Borders.customLine(foreground, 1),
            JBUI.Borders.empty(1, 5)
        ))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Content panels
// ═══════════════════════════════════════════════════════════════════════════

internal class ReasoningLogPanel : JPanel() {
    init { layout = BoxLayout(this, BoxLayout.Y_AXIS); setBorder(JBUI.Borders.empty(8)); isOpaque = false }
    fun clear() { removeAll(); revalidate(); repaint() }
    fun rebuild(steps: List<ReasoningStep>) {
        removeAll()
        steps.forEach { step ->
            val (color, lbl) = when (step.type) {
                ReasoningStep.StepType.THINKING -> JBColor(Color(130,90,200),Color(160,120,220)) to "Рассуждение"
                ReasoningStep.StepType.QUESTION -> JBColor(Color(220,140,30),Color(230,160,50))  to "Вопрос"
                ReasoningStep.StepType.OUTPUT   -> JBColor(Color(34,139,68), Color(72,199,116))  to "Результат"
            }
            val card = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, color),
                    JBUI.Borders.empty(5, 8)
                ))
            }
            val hdr = JBLabel(lbl).apply { font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat()); foreground = color }
            val body = JTextArea(step.text).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; setBorder(null)
                font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))
            }
            val col = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false; add(hdr, BorderLayout.NORTH); add(body, BorderLayout.CENTER)
            }
            card.add(col, BorderLayout.CENTER)
            add(card); add(Box.createVerticalStrut(JBUI.scale(5)))
        }
        revalidate(); repaint()
    }
}

private class SpecViewPanel : JPanel() {
    init { layout = BoxLayout(this, BoxLayout.Y_AXIS); setBorder(JBUI.Borders.empty(8)); isOpaque = false }
    fun clear() { removeAll(); revalidate(); repaint() }
    fun render(spec: SpecDocument) {
        clear()
        section("Функциональные требования",    spec.functional,         Color(37,99,235))
        section("Нефункциональные требования",  spec.nonFunctional,      Color(100,60,180))
        section("Acceptance Criteria",          spec.acceptanceCriteria, Color(22,130,80))
        section("Edge Cases",                   spec.edgeCases,          Color(200,100,20))
        revalidate(); repaint()
    }
    private fun section(title: String, reqs: List<SpecDocument.Requirement>, c: Color) {
        if (reqs.isEmpty()) return
        val jc = JBColor(c, c.brighter())
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            isOpaque = false
            add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat()); foreground = jc })
            add(JBLabel("(${reqs.size})").apply { font = font.deriveFont(JBUI.scale(11).toFloat()); foreground = JBColor.GRAY })
        })
        reqs.forEach { r ->
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, JBUI.scale(2), 0, 0, jc),
                    JBUI.Borders.empty(3, 6)
                ))
                val id = JBLabel(r.id).apply {
                    font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(11)); foreground = jc
                    preferredSize = Dimension(JBUI.scale(72), preferredSize.height)
                }
                val desc = JTextArea(r.description).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true
                    isOpaque = false; setBorder(null); font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))
                }
                val pri = JBLabel(r.priority.name).apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    foreground = when (r.priority) {
                        SpecDocument.Priority.CRITICAL -> JBColor(Color(200,50,50),  Color(220,80,80))
                        SpecDocument.Priority.HIGH     -> JBColor(Color(200,120,30), Color(220,140,50))
                        SpecDocument.Priority.MEDIUM   -> JBColor(Color(37,99,235),  Color(99,155,255))
                        else                           -> JBColor.GRAY
                    }
                    setBorder(BorderFactory.createLineBorder(foreground, 1, true))
                    preferredSize = Dimension(JBUI.scale(62), JBUI.scale(18))
                    horizontalAlignment = SwingConstants.CENTER
                }
                add(id, BorderLayout.WEST); add(desc, BorderLayout.CENTER); add(pri, BorderLayout.EAST)
            })
            add(Box.createVerticalStrut(JBUI.scale(2)))
        }
        add(Box.createVerticalStrut(JBUI.scale(10)))
    }
}

private class PrdPanel : JPanel(BorderLayout()) {
    private val area = JTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)); setBorder(JBUI.Borders.empty(12))
    }
    private val copyBtn = JButton("Копировать", AllIcons.Actions.Copy).apply { isVisible = false }
    init {
        val bar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()))
            add(copyBtn)
        }
        add(bar, BorderLayout.NORTH); add(area, BorderLayout.CENTER)
        copyBtn.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(area.text)) }
    }
    fun render(md: String) { area.text = md; area.caretPosition = 0; copyBtn.isVisible = true }
    fun clear() { area.text = ""; copyBtn.isVisible = false }
}

private class DiagramsPanel : JPanel(BorderLayout()) {
    private val textArea = JTextArea().apply {
        isEditable = false; lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        setBorder(JBUI.Borders.empty(12))
    }
    private val copyBtn = JButton("Копировать", AllIcons.Actions.Copy).apply { isVisible = false }
    private val hintLabel = JBLabel("Диаграммы появятся после генерации PRD").apply {
        foreground = JBColor.GRAY; horizontalAlignment = SwingConstants.CENTER
    }
    init {
        val bar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()))
            add(JBLabel("Вставь код в mermaid.live для просмотра").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(11)); foreground = JBColor.GRAY
            })
            add(copyBtn)
        }
        add(bar, BorderLayout.NORTH)
        add(hintLabel, BorderLayout.CENTER)
        copyBtn.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(textArea.text)) }
    }
    fun render(md: String) {
        remove(hintLabel)
        textArea.text = md; textArea.caretPosition = 0
        if (components.none { it == textArea }) add(JBScrollPane(textArea), BorderLayout.CENTER)
        copyBtn.isVisible = true
        revalidate(); repaint()
    }
    fun clear() {
        textArea.text = ""; copyBtn.isVisible = false
        revalidate(); repaint()
    }
}

private class ValidationBanner : JPanel(BorderLayout()) {
    private var expanded = false
    private val summaryRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply { isOpaque = false }
    private val detailsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; isVisible = false
        setBorder(JBUI.Borders.empty(4, JBUI.scale(22), 2, 0))
    }
    private val toggleBtn = JButton("▸").apply {
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
        font = Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(11))
        preferredSize = Dimension(JBUI.scale(18), JBUI.scale(18))
    }

    init {
        isVisible = false
        setBorder(JBUI.Borders.empty(3, 10))
        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(summaryRow); add(detailsPanel)
        }
        add(col, BorderLayout.CENTER)
        toggleBtn.addActionListener {
            expanded = !expanded
            toggleBtn.text = if (expanded) "▾" else "▸"
            detailsPanel.isVisible = expanded
            revalidate(); repaint()
        }
    }

    fun showResult(r: SpecValidator.ValidationResult) {
        val hasE = r.errors.isNotEmpty()
        if (!hasE && r.warnings.isEmpty()) { isVisible = false; revalidate(); return }

        background = if (hasE) JBColor(Color(255,235,235), Color(80,30,30))
                     else      JBColor(Color(255,248,220), Color(70,55,20))

        val eCount = r.errors.size; val wCount = r.warnings.size
        val summary = when {
            hasE -> "${eCount} ошибок${if (wCount > 0) ", $wCount предупреждений" else ""} — идёт исправление..."
            else -> "$wCount предупреждений"
        }
        summaryRow.removeAll()
        val icon = JBLabel(if (hasE) AllIcons.General.Error else AllIcons.General.Warning)
        val titleLbl = JBLabel(summary).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        }
        summaryRow.add(icon,      BorderLayout.WEST)
        summaryRow.add(titleLbl,  BorderLayout.CENTER)
        summaryRow.add(toggleBtn, BorderLayout.EAST)

        detailsPanel.removeAll()
        r.errors.forEach { msg ->
            detailsPanel.add(JLabel("<html><body style='font-size:11px'>✗ $msg</body></html>"))
        }
        r.warnings.forEach { msg ->
            detailsPanel.add(JLabel("<html><body style='font-size:11px'>⚠ $msg</body></html>"))
        }

        // Auto-collapse if already visible
        expanded = false; toggleBtn.text = "▸"; detailsPanel.isVisible = false
        isVisible = true; revalidate(); repaint()
    }

    fun dismiss() { isVisible = false; revalidate() }
}

// ═══════════════════════════════════════════════════════════════════════════
// SpecSessionComboRenderer — отображение сессии в ComboBox (как в GigaCodeAE)
// ═══════════════════════════════════════════════════════════════════════════

internal class SpecSessionComboRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, hasFocus)
        if (value is SpecSession) {
            text = value.title.take(30).let { if (value.title.length > 30) "$it…" else it }
            toolTipText = value.title
        }
        return this
    }
}
