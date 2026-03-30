package changelogai.platform.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import changelogai.core.settings.PluginDefaults
import changelogai.core.settings.PluginState
import changelogai.core.settings.model.CommitLanguage
import changelogai.core.settings.model.CommitSize
import changelogai.core.settings.model.LLMTemperature
import changelogai.platform.services.AIModelsService
import java.awt.*
import java.awt.event.ActionListener
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SettingsTab(private val project: Project) {

    private val settings = PluginState.getInstance()
    private val aiModelsService = AIModelsService()

    private val isDebugModeCheckbox = JCheckBox()
    private val commitLanguagesComboBox = ComboBox(CommitLanguage.entries.toTypedArray())
    private val temperatureComboBox = ComboBox(LLMTemperature.entries.toTypedArray())
    private val commitSizeComboBox = ComboBox(CommitSize.entries.toTypedArray())
    private val aiAPIUrlField = JBTextField()
    private val aiTokenField = JPasswordField()
    private val aiTokenPanel = buildTokenField()
    private val aiModelComboBox = ComboBox(aiModelsService.getModelNames())
    private val aiModelsStatusPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val aiModelsStatusIndicator = JLabel()
    private val getModelsButton = JButton("Get models / Test connection")
    private val aiCertField = TextFieldWithBrowseButton()
    private val aiKeyField = TextFieldWithBrowseButton()

    // Embedding API
    private val embeddingUrlField = JBTextField()
    private val embeddingTokenField = JPasswordField()
    private val embeddingModelComboBox = ComboBox(arrayOf<String>()).apply { isEditable = true }

    private val applyButton = JButton("Apply")
    private val resetButton = JButton("Reset")
    private val resetDefaultsButton = JButton("Reset defaults")
    private val supportButton = JButton("Support SberChat")

    private var hasUnsavedChanges = false

    val panel: JPanel = createMainPanel()

    private fun buildTokenField(): JPanel {
        aiTokenField.echoChar = '•'
        val eyeBtn = JButton(AllIcons.Actions.Show).apply {
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            toolTipText = "Показать / скрыть токен"
            addActionListener {
                if (aiTokenField.echoChar == '•') {
                    aiTokenField.echoChar = 0.toChar()
                    icon = AllIcons.Actions.ToggleVisibility
                } else {
                    aiTokenField.echoChar = '•'
                    icon = AllIcons.Actions.Show
                }
            }
        }
        return JPanel(BorderLayout(2, 0)).apply {
            isOpaque = false
            add(aiTokenField, BorderLayout.CENTER)
            add(eyeBtn, BorderLayout.EAST)
        }
    }

    init {
        loadSettings()
        setupListeners()
        setupStatusIndicator()
        SwingUtilities.invokeLater { panel.rootPane?.defaultButton = applyButton }
    }

    private fun createMainPanel(): JPanel {
        val mainPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(10) }
        val headerPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(10); add(supportButton) }
        val featuresPanel = FeaturesPanel(project).panel
        val mcpPanel      = McpPanel(project).panel
        val aiSettingsPanel = createAiSettingsPanel()
        val buttonPanel = createButtonPanel()

        featuresPanel.alignmentX = Component.LEFT_ALIGNMENT
        mcpPanel.alignmentX      = Component.LEFT_ALIGNMENT
        aiSettingsPanel.alignmentX = Component.LEFT_ALIGNMENT
        val scrollContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(featuresPanel)
            add(Box.createVerticalStrut(8))
            add(mcpPanel)
            add(Box.createVerticalStrut(8))
            add(aiSettingsPanel)
        }
        val scrollPane = JBScrollPane(scrollContent).apply { alignmentX = Component.LEFT_ALIGNMENT }
        buttonPanel.alignmentX = Component.LEFT_ALIGNMENT

        mainPanel.add(headerPanel)
        mainPanel.add(scrollPane)
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.add(buttonPanel)
        return mainPanel
    }

    private fun createAiSettingsPanel(): JPanel {
        val modelsButtonPanel = JPanel(BorderLayout()).apply {
            add(getModelsButton, BorderLayout.WEST)
            add(aiModelsStatusPanel, BorderLayout.CENTER)
        }
        val settingsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Debug mode:", isDebugModeCheckbox)
            .addSeparator()
            .addLabeledComponent("Language:", commitLanguagesComboBox)
            .addLabeledComponent("Temperature:", temperatureComboBox)
            .addTooltip("Higher -> more random, Lower -> more deterministic")
            .addLabeledComponent("Response size:", commitSizeComboBox)
            .addLabeledComponent("AI API URL:", aiAPIUrlField)
            .addLabeledComponent("AI model:", aiModelComboBox)
            .addComponent(modelsButtonPanel)
            .addSeparator()
            .addLabeledComponent("AI token:", aiTokenPanel)
            .addTooltip("Token has higher priority than certificate")
            .addLabeledComponent("Cert path (.crt):", aiCertField)
            .addLabeledComponent("Key path (.key):", aiKeyField)
            .addSeparator()
            .addLabeledComponent("Embedding URL:", embeddingUrlField)
            .addTooltip("OpenAI-compatible embeddings endpoint (e.g. https://api.openai.com/v1)")
            .addLabeledComponent("Embedding token:", embeddingTokenField)
            .addLabeledComponent("Embedding model:", embeddingModelComboBox)
            .addTooltip("Model name for embeddings (e.g. text-embedding-ada-002). Loaded via 'Get models'.")
            .addSeparator()
            .panel

        val titledBorder = BorderFactory.createTitledBorder(
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 1, 3, 1),
                JBUI.Borders.empty(8)
            ), "AI Settings"
        ) as TitledBorder
        titledBorder.titleFont = titledBorder.titleFont.deriveFont(Font.BOLD)
        titledBorder.titleColor = UIUtil.getLabelForeground()
        settingsPanel.border = titledBorder
        return settingsPanel
    }

    private fun createButtonPanel() = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        add(resetDefaultsButton); add(resetButton); add(applyButton)
    }

    private fun setupStatusIndicator() {
        aiModelsStatusIndicator.apply {
            preferredSize = Dimension(24, 24)
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            isVisible = false
        }
        aiModelsStatusPanel.add(aiModelsStatusIndicator)
        aiModelsStatusPanel.isOpaque = false
    }

    private fun loadSettings() {
        isDebugModeCheckbox.isSelected = settings.isDebugMode
        commitLanguagesComboBox.selectedItem = settings.commitLanguage
        temperatureComboBox.selectedItem = settings.temperature
        commitSizeComboBox.selectedItem = settings.commitSize
        aiModelComboBox.selectedItem = settings.aiModel
        aiAPIUrlField.text = settings.aiUrl
        aiTokenField.text  = settings.aiToken
        aiCertField.text = settings.aiCertPath
        aiKeyField.text = settings.aiKeyPath
        embeddingUrlField.text = settings.embeddingUrl
        embeddingTokenField.text = settings.embeddingToken
        if (settings.embeddingModel.isNotBlank()) {
            if ((embeddingModelComboBox.model as? DefaultComboBoxModel)?.getIndexOf(settings.embeddingModel) == -1) {
                embeddingModelComboBox.addItem(settings.embeddingModel)
            }
            embeddingModelComboBox.selectedItem = settings.embeddingModel
        }
        hasUnsavedChanges = false
        applyButton.isEnabled = false
    }

    private fun setupListeners() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = markAsChanged()
            override fun removeUpdate(e: DocumentEvent) = markAsChanged()
            override fun changedUpdate(e: DocumentEvent) = markAsChanged()
        }
        aiAPIUrlField.document.addDocumentListener(docListener)
        aiTokenField.document.addDocumentListener(docListener)
        embeddingUrlField.document.addDocumentListener(docListener)
        embeddingTokenField.document.addDocumentListener(docListener)

        aiCertField.addDocumentListener(docListener)
        aiKeyField.addDocumentListener(docListener)

        val changeListener = ActionListener { markAsChanged() }
        embeddingModelComboBox.addActionListener(changeListener)
        isDebugModeCheckbox.addActionListener(changeListener)
        commitLanguagesComboBox.addActionListener(changeListener)
        temperatureComboBox.addActionListener(changeListener)
        commitSizeComboBox.addActionListener(changeListener)
        aiModelComboBox.addActionListener(changeListener)

        val fileDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
        aiCertField.addBrowseFolderListener(TextBrowseFolderListener(fileDescriptor))
        aiKeyField.addBrowseFolderListener(TextBrowseFolderListener(fileDescriptor))

        getModelsButton.addActionListener { updateModelComboBox() }
        applyButton.addActionListener { applySettings() }
        resetButton.addActionListener { loadSettings() }
        resetDefaultsButton.addActionListener { resetDefaults() }
        supportButton.addActionListener {
            try { BrowserUtil.browse("https://sberchat.sberbank.ru/@changelogai") }
            catch (ex: Exception) { JOptionPane.showMessageDialog(panel, ex.message, "Error", JOptionPane.ERROR_MESSAGE) }
        }
    }

    private fun updateModelComboBox() {
        getModelsButton.isEnabled = false
        val url = aiAPIUrlField.text
        val token = String(aiTokenField.password)
        val cert = aiCertField.text
        val key = aiKeyField.text
        CompletableFuture.supplyAsync { aiModelsService.getModelNames(url, token, cert, key) }
            .whenComplete { models, _ ->
                SwingUtilities.invokeLater {
                    getModelsButton.isEnabled = true
                    if (models.isNullOrEmpty()) {
                        aiModelsStatusIndicator.icon = AllIcons.General.Error
                        aiModelsStatusIndicator.toolTipText = "Failed: ${aiModelsService.lastError ?: "Empty response"}"
                        aiModelsStatusIndicator.isVisible = true
                        Timer(10000) { aiModelsStatusIndicator.isVisible = false }.apply { isRepeats = false; start() }
                    } else {
                        aiModelsStatusIndicator.icon = AllIcons.General.InspectionsOK
                        aiModelsStatusIndicator.toolTipText = "Models loaded successfully"
                        aiModelsStatusIndicator.isVisible = true
                        Timer(5000) { aiModelsStatusIndicator.isVisible = false }.apply { isRepeats = false; start() }
                        aiModelComboBox.model = DefaultComboBoxModel(models)
                        aiModelComboBox.selectedItem = if (models.contains(settings.aiModel)) settings.aiModel else models[0]
                        // Также обновляем список embedding-моделей теми же моделями
                        val currentEmbModel = embeddingModelComboBox.selectedItem?.toString() ?: settings.embeddingModel
                        embeddingModelComboBox.model = DefaultComboBoxModel(models)
                        embeddingModelComboBox.selectedItem = if (models.contains(currentEmbModel)) currentEmbModel else null
                    }
                }
            }
    }

    private fun resetDefaults() {
        if (JOptionPane.showConfirmDialog(panel,
                "Reset all settings to default values?", "Reset to Defaults",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
            isDebugModeCheckbox.isSelected = PluginDefaults.isDebugMode
            commitLanguagesComboBox.selectedItem = PluginDefaults.commitLanguage
            temperatureComboBox.selectedItem = PluginDefaults.temperature
            commitSizeComboBox.selectedItem = PluginDefaults.commitSize
            aiAPIUrlField.text = PluginDefaults.aiUrl
            markAsChanged()
        }
    }

    private fun markAsChanged() { hasUnsavedChanges = true; applyButton.isEnabled = true }

    private fun applySettings() {
        try {
            settings.isDebugMode = isDebugModeCheckbox.isSelected
            settings.commitLanguage = commitLanguagesComboBox.item
            settings.temperature = temperatureComboBox.item
            settings.commitSize = commitSizeComboBox.item
            settings.aiUrl = aiAPIUrlField.text
            aiModelComboBox.item?.let { settings.aiModel = it }
            settings.aiToken = String(aiTokenField.password)
            settings.aiCertPath = aiCertField.text
            settings.aiKeyPath = aiKeyField.text
            settings.embeddingUrl = embeddingUrlField.text
            settings.embeddingToken = String(embeddingTokenField.password)
            settings.embeddingModel = embeddingModelComboBox.selectedItem?.toString() ?: ""
            hasUnsavedChanges = false
            applyButton.isEnabled = false
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(panel, "Invalid input: ${e.message}", "Settings Error", JOptionPane.ERROR_MESSAGE)
        }
    }
}
