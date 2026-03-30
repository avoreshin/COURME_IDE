package changelogai.feature.kb.ui

import changelogai.core.settings.PluginDefaults
import changelogai.core.settings.PluginState
import changelogai.feature.kb.KnowledgeBaseService
import changelogai.feature.kb.model.KBIndexState
import changelogai.platform.services.AIModelsService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*
import javax.swing.SpinnerNumberModel

class KnowledgeBasePanel(private val project: Project) {

    val panel: JPanel = JPanel(BorderLayout())

    // ── Source selection ─────────────────────────────────────────────────
    private val modeSpace = JRadioButton("Confluence Space")
    private val modePageTree = JRadioButton("Дерево страниц")
    private val modeManual = JRadioButton("Выбранные страницы")
    private val modeGroup = ButtonGroup().apply { add(modeSpace); add(modePageTree); add(modeManual) }

    private val spaceKeyField = JBTextField(20)
    private val rootPageUrlField = JBTextField(40)
    private val manualUrlsArea = JBTextArea(5, 40).apply { lineWrap = true; wrapStyleWord = true }

    // ── Embedding model ─────────────────────────────────────────────────
    private val embeddingModelComboBox = ComboBox(arrayOf<String>()).apply { isEditable = true; preferredSize = Dimension(220, preferredSize.height) }
    private val loadModelsBtn = JButton("↻").apply { toolTipText = "Загрузить модели из API"; preferredSize = Dimension(32, preferredSize.height) }
    private val aiModelsService = AIModelsService()

    // ── Chunk settings ──────────────────────────────────────────────────
    private val chunkSizeSpinner = JSpinner(SpinnerNumberModel(PluginDefaults.chunkSize, 100, 4000, 50))
    private val chunkOverlapSpinner = JSpinner(SpinnerNumberModel(PluginDefaults.chunkOverlap, 0, 500, 25))

    // ── Actions ─────────────────────────────────────────────────────────
    private val indexBtn = JButton("Индексировать")
    private val reindexBtn = JButton("Обновить индекс")
    private val clearBtn = JButton("Очистить")
    private val cancelBtn = JButton("Отмена").apply { isVisible = false }

    // ── Status ──────────────────────────────────────────────────────────
    private val statusLabel = JBLabel(" ")
    private val progressBar = JProgressBar().apply { isVisible = false; isIndeterminate = true }
    private val statsLabel = JBLabel(" ")

    // ── Log ──────────────────────────────────────────────────────────────
    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(4, 6)
    }
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    // ── Test search ─────────────────────────────────────────────────────
    private val searchField = JBTextField(40)
    private val searchBtn = JButton("Искать")
    private val searchResults = JBTextArea(10, 50).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    @Volatile private var indexing = false

    init {
        buildUI()
        wireActions()
        loadSettings()
        refreshState()
    }

    private fun buildUI() {
        panel.border = JBUI.Borders.empty(8)

        val content = Box.createVerticalBox()

        // ── Embedding model ──
        val embeddingPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createTitledBorder("Embedding")
            add(JLabel("Model:"))
            add(embeddingModelComboBox)
            add(loadModelsBtn)
            add(JBLabel("(URL и Token — в Settings)").apply { foreground = JBColor.GRAY })
        }
        content.add(embeddingPanel)
        content.add(Box.createVerticalStrut(4))

        // ── Chunk settings ──
        val chunkPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createTitledBorder("Параметры чанкинга")
            add(JLabel("Размер (токены):"))
            add(chunkSizeSpinner)
            add(Box.createHorizontalStrut(12))
            add(JLabel("Перекрытие:"))
            add(chunkOverlapSpinner)
        }
        content.add(chunkPanel)
        content.add(Box.createVerticalStrut(4))

        // ── Source selection ──
        val sourcePanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Источник данных")
        }
        val sg = GridBagConstraints().apply {
            insets = JBUI.insets(2, 4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
        }
        sg.gridx = 0; sg.gridy = 0; sg.gridwidth = 2; sourcePanel.add(modeSpace, sg)
        sg.gridy = 1; sg.gridwidth = 1; sg.weightx = 0.0; sourcePanel.add(JLabel("  Space Key:"), sg)
        sg.gridx = 1; sg.weightx = 1.0; sourcePanel.add(spaceKeyField, sg)

        sg.gridx = 0; sg.gridy = 2; sg.gridwidth = 2; sg.weightx = 0.0; sourcePanel.add(modePageTree, sg)
        sg.gridy = 3; sg.gridwidth = 1; sourcePanel.add(JLabel("  Root URL:"), sg)
        sg.gridx = 1; sg.weightx = 1.0; sourcePanel.add(rootPageUrlField, sg)

        sg.gridx = 0; sg.gridy = 4; sg.gridwidth = 2; sg.weightx = 0.0; sourcePanel.add(modeManual, sg)
        sg.gridy = 5; sg.gridwidth = 2; sg.weightx = 1.0; sg.weighty = 1.0; sg.fill = GridBagConstraints.BOTH
        sourcePanel.add(JBScrollPane(manualUrlsArea), sg)

        content.add(sourcePanel)
        content.add(Box.createVerticalStrut(8))

        // ── Actions ──
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(indexBtn); add(reindexBtn); add(clearBtn); add(cancelBtn)
        }
        content.add(actionsPanel)

        // ── Status ──
        val statusPanel = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
            add(statsLabel, BorderLayout.SOUTH)
        }
        content.add(statusPanel)

        // ── Log ──
        val clearLogBtn = JButton(AllIcons.Actions.GC).apply {
            toolTipText = "Очистить лог"
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            addActionListener { logArea.text = "" }
        }
        val logToolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            add(JLabel("Лог").apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() }, BorderLayout.WEST)
            add(clearLogBtn, BorderLayout.EAST)
        }
        val logScroll = JBScrollPane(logArea).apply {
            preferredSize = Dimension(0, JBUI.scale(120))
            border = JBUI.Borders.customLine(JBColor.border())
        }
        content.add(logToolbar)
        content.add(logScroll)
        content.add(Box.createVerticalStrut(12))

        // ── Test search ──
        val searchPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Тест поиска")
        }
        val tg = GridBagConstraints().apply {
            insets = JBUI.insets(2, 4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
        }
        tg.gridx = 0; tg.gridy = 0; tg.weightx = 1.0; searchPanel.add(searchField, tg)
        tg.gridx = 1; tg.weightx = 0.0; searchPanel.add(searchBtn, tg)
        tg.gridx = 0; tg.gridy = 1; tg.gridwidth = 2; tg.weightx = 1.0; tg.weighty = 1.0; tg.fill = GridBagConstraints.BOTH
        searchPanel.add(JBScrollPane(searchResults), tg)
        content.add(searchPanel)

        panel.add(JBScrollPane(content), BorderLayout.CENTER)
        modeSpace.isSelected = true
    }

    private fun wireActions() {
        indexBtn.addActionListener { startIndexing() }
        reindexBtn.addActionListener { startReindex() }
        clearBtn.addActionListener { clearIndex() }
        cancelBtn.addActionListener { /* TODO: cancellation support */ }
        searchBtn.addActionListener { doSearch() }
        loadModelsBtn.addActionListener { loadModelsFromApi() }

        embeddingModelComboBox.addActionListener { saveEmbeddingSettings() }
    }

    private fun loadSettings() {
        val s = PluginState.getInstance()
        val model = s.embeddingModel
        if (model.isNotBlank()) {
            if ((embeddingModelComboBox.model as? DefaultComboBoxModel)?.getIndexOf(model) == -1) {
                embeddingModelComboBox.addItem(model)
            }
            embeddingModelComboBox.selectedItem = model
        }
        chunkSizeSpinner.value = s.chunkSize
        chunkOverlapSpinner.value = s.chunkOverlap
    }

    private fun saveEmbeddingSettings() {
        val s = PluginState.getInstance()
        s.embeddingModel = embeddingModelComboBox.selectedItem?.toString()?.trim() ?: ""
        s.chunkSize = (chunkSizeSpinner.value as? Int) ?: PluginDefaults.chunkSize
        s.chunkOverlap = (chunkOverlapSpinner.value as? Int) ?: PluginDefaults.chunkOverlap
    }

    private fun loadModelsFromApi() {
        val s = PluginState.getInstance()
        if (s.embeddingUrl.isBlank()) {
            setStatus("Укажите Embedding URL в Settings", JBColor.RED)
            return
        }
        loadModelsBtn.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = aiModelsService.getModelNames(s.embeddingUrl, s.embeddingToken, s.aiCertPath, s.aiKeyPath)
            SwingUtilities.invokeLater {
                loadModelsBtn.isEnabled = true
                if (models.isEmpty()) {
                    setStatus("Не удалось загрузить модели: ${aiModelsService.lastError ?: "пустой ответ"}", JBColor.RED)
                } else {
                    val current = embeddingModelComboBox.selectedItem?.toString() ?: s.embeddingModel
                    embeddingModelComboBox.model = DefaultComboBoxModel(models)
                    embeddingModelComboBox.selectedItem = if (models.contains(current)) current else models[0]
                    setStatus("Загружено ${models.size} моделей", JBColor.GRAY)
                }
            }
        }
    }

    private fun refreshState() {
        val kbService = KnowledgeBaseService.getInstance(project)
        val state = kbService.getState()
        if (state != null) {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(state.lastIndexedAt))
            statsLabel.text = "Страниц: ${state.pageCount} | Чанков: ${state.chunkCount} | Обновлено: $date"
            reindexBtn.isEnabled = true
            clearBtn.isEnabled = true
            searchBtn.isEnabled = true
        } else {
            statsLabel.text = "База знаний не проиндексирована"
            reindexBtn.isEnabled = false
            clearBtn.isEnabled = false
            searchBtn.isEnabled = false
        }
    }

    private fun startIndexing() {
        if (indexing) return
        saveEmbeddingSettings()

        indexing = true
        setUIIndexing(true)

        log("━━━ Начало индексации ━━━")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val kbService = KnowledgeBaseService.getInstance(project)
                val onProgress: (String) -> Unit = { msg ->
                    log(msg)
                    SwingUtilities.invokeLater { setStatus(msg, JBColor.GRAY) }
                }
                val state: KBIndexState = when {
                    modeSpace.isSelected -> {
                        val key = spaceKeyField.text.trim()
                        if (key.isBlank()) throw IllegalArgumentException("Укажите Space Key")
                        kbService.indexSpace(key, onProgress = onProgress)
                    }
                    modePageTree.isSelected -> {
                        val url = rootPageUrlField.text.trim()
                        if (url.isBlank()) throw IllegalArgumentException("Укажите Root Page URL")
                        kbService.indexPageTree(url, onProgress = onProgress)
                    }
                    else -> {
                        val urls = manualUrlsArea.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                        if (urls.isEmpty()) throw IllegalArgumentException("Введите URL страниц")
                        kbService.indexManualUrls(urls, onProgress = onProgress)
                    }
                }
                val summary = "Индексация завершена: ${state.pageCount} страниц, ${state.chunkCount} чанков"
                log("✓ $summary")
                SwingUtilities.invokeLater {
                    setStatus(summary, JBColor(0x2E7D32, 0x66BB6A))
                    setUIIndexing(false)
                    refreshState()
                }
            } catch (e: Exception) {
                log("✗ Ошибка: ${e.message}")
                SwingUtilities.invokeLater {
                    setStatus("Ошибка: ${e.message}", JBColor.RED)
                    setUIIndexing(false)
                }
            } finally {
                indexing = false
            }
        }
    }

    private fun startReindex() {
        if (indexing) return
        indexing = true
        setUIIndexing(true)

        log("━━━ Обновление индекса ━━━")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val kbService = KnowledgeBaseService.getInstance(project)
                val state = kbService.reindex { msg ->
                    log(msg)
                    SwingUtilities.invokeLater { setStatus(msg, JBColor.GRAY) }
                }
                val summary = "Обновление завершено: ${state.pageCount} страниц, ${state.chunkCount} чанков"
                log("✓ $summary")
                SwingUtilities.invokeLater {
                    setStatus(summary, JBColor(0x2E7D32, 0x66BB6A))
                    setUIIndexing(false)
                    refreshState()
                }
            } catch (e: Exception) {
                log("✗ Ошибка: ${e.message}")
                SwingUtilities.invokeLater {
                    setStatus("Ошибка: ${e.message}", JBColor.RED)
                    setUIIndexing(false)
                }
            } finally {
                indexing = false
            }
        }
    }

    private fun clearIndex() {
        val kbService = KnowledgeBaseService.getInstance(project)
        kbService.clearIndex()
        searchResults.text = ""
        setStatus("Индекс очищен", JBColor.GRAY)
        refreshState()
    }

    private fun doSearch() {
        val query = searchField.text.trim()
        if (query.isBlank()) return
        saveEmbeddingSettings()

        searchBtn.isEnabled = false
        searchResults.text = "Поиск..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val kbService = KnowledgeBaseService.getInstance(project)
                val results = kbService.search(query, topK = 5)
                SwingUtilities.invokeLater {
                    searchBtn.isEnabled = true
                    if (results.isEmpty()) {
                        searchResults.text = "Результатов не найдено"
                    } else {
                        searchResults.text = results.mapIndexed { i, r ->
                            "--- #${i + 1} (${(r.score * 100).toInt()}%) ${r.pageTitle} ---\n${r.chunkText}\n"
                        }.joinToString("\n")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    searchBtn.isEnabled = true
                    searchResults.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun setStatus(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }

    private fun log(msg: String) {
        val line = "[${LocalTime.now().format(timeFmt)}] $msg\n"
        SwingUtilities.invokeLater {
            logArea.append(line)
            logArea.caretPosition = logArea.document.length
        }
    }

    private fun setUIIndexing(active: Boolean) {
        progressBar.isVisible = active
        progressBar.isIndeterminate = active
        cancelBtn.isVisible = active
        indexBtn.isEnabled = !active
        reindexBtn.isEnabled = !active
        clearBtn.isEnabled = !active
    }
}
