package changelogai.feature.gigacodeae.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import changelogai.feature.gigacodeae.orchestrator.OrchestratorMode
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Панель ввода: многострочное поле с закруглённой рамкой, плейсхолдер, Ctrl+Enter = отправить.
 * Поддерживает @ для вставки файлов проекта.
 */
class InputPanel(
    private val project: Project,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onModeChanged: ((OrchestratorMode) -> Unit)? = null
) : JPanel(BorderLayout(0, 4)) {

    private val placeholder = "Спросите что-нибудь…"

    private val textArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        font = com.intellij.util.ui.UIUtil.getLabelFont()
        border = JBUI.Borders.empty(6, 8)
        isOpaque = false
    }

    private val sendButton = JButton("Отправить", AllIcons.Actions.Execute).apply {
        toolTipText = "Отправить (Ctrl+Enter)"
        horizontalTextPosition = SwingConstants.RIGHT
    }

    private val stopButton = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Остановить"
        preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isEnabled = false
    }

    // Флаг: показывать ли плейсхолдер
    private var showingPlaceholder = true

    // @ mention
    private val atPopup = AtMentionPopup(project, textArea) { path -> insertMention(path) }
    private var mentionStart = -1
    private var suppressDocumentEvents = false

    // Mode pills
    private var selectedMode = OrchestratorMode.AUTO
    private val pillButtons = mutableListOf<JButton>()

    init {
        border = JBUI.Borders.emptyTop(4)

        setupPlaceholder()

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (atPopup.isShowing) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP     -> { atPopup.selectPrev(); e.consume() }
                        KeyEvent.VK_DOWN   -> { atPopup.selectNext(); e.consume() }
                        KeyEvent.VK_ENTER  -> { atPopup.selectCurrent(); e.consume() }
                        KeyEvent.VK_ESCAPE -> { atPopup.hide(); e.consume() }
                    }
                    return
                }
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    e.consume()
                    doSend()
                }
            }
        })

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { SwingUtilities.invokeLater { checkAtMention() } }
            override fun removeUpdate(e: DocumentEvent) { SwingUtilities.invokeLater { checkAtMention() } }
            override fun changedUpdate(e: DocumentEvent) {}
        })

        sendButton.addActionListener { doSend() }
        stopButton.addActionListener { onStop() }

        val hintLabel = JLabel("Ctrl+Enter — отправить").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            font = font.deriveFont(Font.PLAIN, 10f)
        }

        val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(hintLabel); add(stopButton); add(sendButton)
        }

        // ── Mode pills (Cursor-style) ──────────────────────────────────
        val modePills = createModePills()

        val bottomRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(modePills, BorderLayout.WEST)
            add(rightButtons, BorderLayout.EAST)
        }

        // Скроллпейн с закруглённой рамкой
        val scrollPane = JBScrollPane(textArea).apply {
            border = RoundedBorder(12)
            isOpaque = false
            viewport.isOpaque = false
        }

        add(scrollPane, BorderLayout.CENTER)
        add(bottomRow, BorderLayout.SOUTH)
    }

    // ── Mode pills (Cursor-style) ──────────────────────────────────────

    private fun createModePills(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(2)
        }

        OrchestratorMode.entries.forEach { mode ->
            val btn = JButton(mode.label).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                isBorderPainted = false
                isFocusPainted = false
                cursor = Cursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(JBUI.scale(if (mode == OrchestratorMode.MULTI_STEP) 90 else 56), JBUI.scale(22))
                border = JBUI.Borders.empty(2, 8, 2, 8)
                addActionListener {
                    selectedMode = mode
                    updatePillStyles()
                    onModeChanged?.invoke(mode)
                }
            }
            pillButtons.add(btn)
            panel.add(btn)
        }
        updatePillStyles()
        return panel
    }

    private fun updatePillStyles() {
        val accent   = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val muted    = JBUI.CurrentTheme.Label.disabledForeground()
        val activeBg = JBUI.CurrentTheme.ActionButton.pressedBackground()

        OrchestratorMode.entries.forEachIndexed { i, mode ->
            val btn = pillButtons[i]
            if (mode == selectedMode) {
                btn.foreground = accent
                btn.background = activeBg
                btn.isContentAreaFilled = true
                btn.isOpaque = true
            } else {
                btn.foreground = muted
                btn.isContentAreaFilled = false
                btn.isOpaque = false
            }
        }
    }

    fun setLoading(loading: Boolean) {
        sendButton.isEnabled = !loading
        stopButton.isEnabled = loading
        textArea.isEnabled = !loading
    }

    /** Предзаполняет поле ввода текстом (например, из Spec Generator) */
    fun setPrefilledText(text: String) {
        val normalColor = JBUI.CurrentTheme.Label.foreground()
        showingPlaceholder = false
        textArea.foreground = normalColor
        textArea.text = text
        textArea.requestFocusInWindow()
        textArea.caretPosition = 0
    }

    private fun setupPlaceholder() {
        val placeholderColor = JBUI.CurrentTheme.Label.disabledForeground()
        val normalColor = JBUI.CurrentTheme.Label.foreground()

        // Рисуем плейсхолдер поверх пустого поля
        textArea.foreground = placeholderColor
        textArea.text = placeholder

        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (showingPlaceholder) {
                    textArea.text = ""
                    textArea.foreground = normalColor
                    showingPlaceholder = false
                }
            }

            override fun focusLost(e: FocusEvent) {
                if (textArea.text.isEmpty()) {
                    textArea.foreground = placeholderColor
                    textArea.text = placeholder
                    showingPlaceholder = true
                }
            }
        })
    }

    private fun checkAtMention() {
        if (suppressDocumentEvents || showingPlaceholder) return
        val caret = textArea.caretPosition.coerceIn(0, textArea.document.length)
        val text = textArea.text
        if (text.isEmpty()) { atPopup.hide(); return }
        val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val segment = text.substring(lineStart, caret)
        val atIdx = segment.lastIndexOf('@')
        if (atIdx >= 0) {
            val query = segment.substring(atIdx + 1)
            if (!query.contains(' ') && !query.contains('\n')) {
                mentionStart = lineStart + atIdx
                atPopup.updateFilter(query)
                if (!atPopup.isShowing) atPopup.show(query)
                return
            }
        }
        atPopup.hide()
    }

    private fun insertMention(path: String) {
        suppressDocumentEvents = true
        try {
            val text = textArea.text
            val caret = textArea.caretPosition.coerceIn(0, text.length)
            val before = text.substring(0, mentionStart)
            val after = text.substring(caret)
            val inserted = "@$path"
            textArea.text = before + inserted + after
            textArea.caretPosition = before.length + inserted.length
        } finally {
            suppressDocumentEvents = false
        }
    }

    private fun doSend() {
        if (showingPlaceholder) return
        val text = textArea.text.trim()
        if (text.isNotEmpty()) {
            textArea.text = ""
            showingPlaceholder = false
            onSend(text)
        }
    }
}

/**
 * Закруглённая рамка для JScrollPane с цветом из темы IDE.
 */
private class RoundedBorder(private val radius: Int) : AbstractBorder() {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBUI.CurrentTheme.ActionButton.focusedBorder()
        val arc = radius.toDouble()
        g2.draw(RoundRectangle2D.Double(x + 0.5, y + 0.5, (width - 1).toDouble(), (height - 1).toDouble(), arc, arc))
        g2.dispose()
    }

    override fun getBorderInsets(c: Component) = Insets(4, 4, 4, 4)
    override fun getBorderInsets(c: Component, insets: Insets): Insets {
        insets.set(4, 4, 4, 4); return insets
    }
}
