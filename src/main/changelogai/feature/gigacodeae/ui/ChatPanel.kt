package changelogai.feature.gigacodeae.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import changelogai.feature.gigacodeae.UiMessage
import changelogai.feature.gigacodeae.orchestrator.AgentLogEvent
import changelogai.feature.gigacodeae.tools.RunTerminalTool
import changelogai.feature.gigacodeae.tools.ToolResult
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.border.Border
import javax.swing.text.html.HTMLEditorKit

/**
 * Чат-панель на основе нативных Swing-компонентов.
 * Каждое сообщение — отдельная JPanel, что обеспечивает корректный layout и выравнивание.
 */
class ChatPanel(private val project: Project) : JScrollPane() {

    var onCommandResult: ((command: String, output: String) -> Unit)? = null
    var onSaveToFile: ((content: String) -> Unit)? = null

    private val timeFmt = SimpleDateFormat("HH:mm")
    private var confirmCounter = 0
    private val pendingConfirms = mutableMapOf<Int, Pair<() -> Unit, () -> Unit>>()

    private val feed = object : JPanel() {
        init {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border   = JBUI.Borders.empty(8, 12, 8, 12)
        }
        // Constrain width to viewport — prevents children from pushing the scroll pane wider
        override fun getPreferredSize(): Dimension {
            val ps = super.getPreferredSize()
            val vw = this@ChatPanel.viewport.width
            return Dimension(if (vw > 0) vw else ps.width, ps.height)
        }
    }

    init {
        setViewportView(feed)
        verticalScrollBarPolicy   = VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
        border     = JBUI.Borders.empty()
        isOpaque   = false
        viewport.isOpaque = false
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun addMessage(msg: UiMessage) = post(buildView(msg))

    fun addMessageStreaming(msg: UiMessage) {
        if (msg.role != "assistant" || msg.content.length < 20) { addMessage(msg); return }
        val card = AssistantCard()
        post(card)
        val content = msg.content
        var pos = 0
        val timer = Timer(30, null)
        timer.addActionListener {
            val step = when { pos < 80 -> 2; pos < 400 -> 6; else -> 14 }
            pos = (pos + step).coerceAtMost(content.length)
            card.setContent(content.substring(0, pos))
            feed.revalidate()
            scrollToBottom()
            if (pos >= content.length) {
                timer.stop()
                card.showSaveButton(content)
                feed.revalidate()
            }
        }
        timer.start()
    }

    fun clearMessages() {
        pendingConfirms.values.forEach { it.second() }
        pendingConfirms.clear()
        feed.removeAll()
        feed.revalidate(); feed.repaint()
    }

    fun setTyping(typing: Boolean) { if (typing) scrollToBottom() }

    fun addLogEvent(event: AgentLogEvent) = post(agentLogCard(event))

    // ── Post helper ───────────────────────────────────────────────────────

    private fun post(view: JComponent) {
        view.alignmentX = Component.LEFT_ALIGNMENT
        feed.add(view)
        feed.add(spacer(6))
        feed.revalidate(); feed.repaint()
        scrollToBottom()
    }

    private fun scrollToBottom() =
        SwingUtilities.invokeLater { verticalScrollBar.value = verticalScrollBar.maximum }

    // ── View factory ──────────────────────────────────────────────────────

    private fun buildView(msg: UiMessage): JComponent = when (msg.role) {
        "user"      -> userBubble(msg)
        "assistant" -> AssistantCard().also { it.setContent(msg.content) }
        "tool_call" -> toolCard(msg)
        "confirm"   -> confirmCard(msg)
        else        -> JPanel()
    }

    // ── User bubble ───────────────────────────────────────────────────────

    private fun userBubble(msg: UiMessage): JComponent {
        val muted = JBUI.CurrentTheme.Label.disabledForeground()
        val time  = timeFmt.format(Date(msg.timestamp))

        val isCode = looksLikeCode(msg.content)

        val metaLabel = JLabel("$time  Вы").apply {
            font       = font.deriveFont(Font.PLAIN, 10f)
            foreground = muted
            alignmentX = Component.RIGHT_ALIGNMENT
        }

        val contentComp: JComponent = if (isCode) {
            inlineCodeBlock(msg.content, showRun = false)
        } else {
            JTextArea(msg.content).apply {
                isEditable    = false
                isOpaque      = false
                lineWrap      = true
                wrapStyleWord = true
                font          = UIUtil.getLabelFont()
                foreground    = UIUtil.getLabelForeground()
                border        = JBUI.Borders.empty()
                alignmentX    = Component.RIGHT_ALIGNMENT
            }
        }
        contentComp.alignmentX = Component.RIGHT_ALIGNMENT

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border = JBUI.Borders.emptyLeft(JBUI.scale(100))
            add(metaLabel)
            add(spacer(2))
            add(contentComp)
        }
    }

    // ── Assistant card ────────────────────────────────────────────────────

    inner class AssistantCard : JPanel(BorderLayout(0, 0)) {

        private val accentBar = JPanel().apply {
            background   = accent()
            isOpaque     = true
            preferredSize = Dimension(JBUI.scale(3), 1)
        }

        private val metaLabel = JLabel().apply {
            font       = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border     = JBUI.Borders.empty(0, 0, 4, 0)
        }

        private val contentBox = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        private var lastText = ""

        init {
            isOpaque    = false
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border      = JBUI.Borders.emptyRight(JBUI.scale(60))

            metaLabel.text = "GigaCodeAE  ${timeFmt.format(Date())}"
            metaLabel.alignmentX = Component.LEFT_ALIGNMENT

            val inner = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(accentBar,  BorderLayout.WEST)
                add(JPanel().apply {
                    layout   = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    border   = JBUI.Borders.emptyLeft(4)
                    add(metaLabel)
                    add(contentBox)
                }, BorderLayout.CENTER)
            }
            add(inner, BorderLayout.CENTER)
        }

        fun showSaveButton(fullContent: String) {
            // Показываем кнопку только если ответ содержит блок кода
            val hasCode = parseSegments(fullContent).any { it is Segment.Code }
            if (!hasCode) return
            val muted = JBUI.CurrentTheme.Label.disabledForeground()
            val saveBtn = JButton("Сохранить в файл", AllIcons.Actions.Download).apply {
                font                = font.deriveFont(Font.PLAIN, 11f)
                foreground          = muted
                isBorderPainted     = false
                isContentAreaFilled = false
                isFocusPainted      = false
                cursor              = Cursor(Cursor.HAND_CURSOR)
                alignmentX          = Component.LEFT_ALIGNMENT
                addActionListener   { onSaveToFile?.invoke(fullContent) }
            }
            saveBtn.alignmentX = Component.LEFT_ALIGNMENT
            contentBox.add(Box.createVerticalStrut(JBUI.scale(4)))
            contentBox.add(saveBtn)
            contentBox.revalidate()
        }

        fun setContent(text: String) {
            if (text == lastText) return
            lastText = text

            // color accent based on error state
            accentBar.background = if (text.startsWith("**Ошибка:")) errorColor() else accent()

            contentBox.removeAll()
            parseSegments(text).forEachIndexed { i, seg ->
                if (i > 0) contentBox.add(spacer(4))
                when (seg) {
                    is Segment.Text -> if (seg.content.isNotBlank())
                        contentBox.add(textSegment(seg.content))
                    is Segment.Code ->
                        contentBox.add(inlineCodeBlock(seg.content, lang = seg.lang, showRun = true))
                }
            }
            contentBox.revalidate(); contentBox.repaint()
        }
    }

    // ── Text segment (markdown→HTML inside JEditorPane) ───────────────────

    private fun textSegment(text: String): JComponent {
        val fg    = UIUtil.getLabelForeground()
        val muted = JBUI.CurrentTheme.Label.disabledForeground()
        val html  = buildHtml(text, fg.hex(), muted.hex())
        val labelFont = UIUtil.getLabelFont()

        return object : JEditorPane("text/html", html) {
            init {
                isEditable = false
                isOpaque   = false
                alignmentX = Component.LEFT_ALIGNMENT
                putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
                font = labelFont
                (editorKit as? HTMLEditorKit)?.styleSheet?.also { ss ->
                    ss.addRule("body { font-family: '${labelFont.family}'; font-size: ${labelFont.size}px; margin:0; padding:0; }")
                    ss.addRule("code { font-family: JetBrains Mono, Menlo, Consolas; font-size: ${labelFont.size - 1}px; }")
                    ss.addRule("h3,h4 { margin: 4px 0; }")
                }
            }
            override fun getPreferredSize(): Dimension {
                val w = (parent?.width ?: 600).coerceAtLeast(80)
                setSize(w, Int.MAX_VALUE)
                return Dimension(w, super.getPreferredSize().height)
            }
            override fun getScrollableTracksViewportWidth() = true
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    // ── Code block ────────────────────────────────────────────────────────

    private fun inlineCodeBlock(
        code: String,
        lang: String = "",
        showRun: Boolean = true
    ): JComponent {
        val bg     = codeBg()
        val fg     = UIUtil.getLabelForeground()
        val muted  = JBUI.CurrentTheme.Label.disabledForeground()
        val border = codeBorderColor()

        val codeArea = JTextArea(code).apply {
            isEditable = false
            isOpaque   = true
            background = bg
            foreground = fg
            font       = Font("JetBrains Mono", Font.PLAIN, 12)
            lineWrap   = false
            setBorder(JBUI.Borders.empty(8, 10, 10, 10))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val langLabel = JLabel(lang.ifBlank { "code" }).apply {
            font       = font.deriveFont(Font.PLAIN, 10f)
            foreground = muted
        }

        val copyBtn = codeIconBtn(AllIcons.Actions.Copy,    "Копировать") { copyToClipboard(code) }
        val runBtn  = codeIconBtn(AllIcons.Actions.Execute, "Запустить")  { confirmAndRun(code) }

        val header = JPanel(BorderLayout()).apply {
            isOpaque   = true
            background = bg
            setBorder(JBUI.Borders.empty(4, 10, 2, 6))
            add(langLabel, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(copyBtn)
                if (showRun) add(runBtn)
            }, BorderLayout.EAST)
        }

        val codeScroll = JScrollPane(codeArea).apply {
            setBorder(JBUI.Borders.empty())
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            isOpaque                  = false
            viewport.background       = bg
        }

        return JPanel(BorderLayout()).apply {
            isOpaque    = true
            background  = bg
            alignmentX  = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            this.border = BorderFactory.createLineBorder(codeBorderColor(), 1)
            add(header,      BorderLayout.NORTH)
            add(codeScroll,  BorderLayout.CENTER)
        }
    }

    private fun codeIconBtn(icon: javax.swing.Icon, tooltip: String, action: () -> Unit) =
        JButton(icon).apply {
            toolTipText         = tooltip
            isBorderPainted     = false
            isContentAreaFilled = false
            isFocusPainted      = false
            cursor              = Cursor(Cursor.HAND_CURSOR)
            preferredSize       = Dimension(JBUI.scale(24), JBUI.scale(22))
            addActionListener   { action() }
        }

    // ── Tool card ─────────────────────────────────────────────────────────

    private fun toolCard(msg: UiMessage): JComponent {
        val fg    = UIUtil.getLabelForeground()
        val muted = JBUI.CurrentTheme.Label.disabledForeground()
        val bg    = toolBg()
        val name  = msg.functionCall?.name ?: msg.content
        val args  = msg.functionCall?.let { summarizeArgs(it.arguments) } ?: ""
        val dur   = msg.durationMs?.let { "${it}мс" } ?: ""

        val (icon, iconColor) = when (msg.toolResult) {
            is ToolResult.Ok    -> AllIcons.RunConfigurations.TestPassed to Color(80, 180, 80)
            is ToolResult.Error -> AllIcons.RunConfigurations.TestFailed to errorColor()
            ToolResult.Denied   -> AllIcons.Actions.Cancel              to muted
            null                -> AllIcons.RunConfigurations.TestNotRan to muted
        }

        val resultText = when (val r = msg.toolResult) {
            is ToolResult.Ok    -> r.content.take(240).let { if (r.content.length > 240) "$it…" else it }
            is ToolResult.Error -> r.message
            else                -> ""
        }

        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            
            val leftFlow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(JLabel(icon).apply { foreground = iconColor })
                add(JLabel(name).apply { font = font.deriveFont(Font.BOLD, 12f); foreground = fg })
                if (dur.isNotBlank()) {
                    add(separator(muted))
                    add(JLabel(dur).apply { font = font.deriveFont(Font.PLAIN, 10f); foreground = muted })
                }
            }
            add(leftFlow, BorderLayout.WEST)

            val inspectBtn = codeIconBtn(AllIcons.Actions.Show, "Посмотреть вход/выход (как в Cursor)") {
                showToolInspector(msg)
            }
            val rightFlow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(inspectBtn)
            }
            add(rightFlow, BorderLayout.EAST)
        }

        val card = JPanel(BorderLayout(0, 0)).apply {
            isOpaque    = true
            background  = bg
            border      = JBUI.Borders.empty(4, 8, 4, 8)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(headerRow, BorderLayout.NORTH)
            if (resultText.isNotBlank()) {
                add(JTextArea(resultText).apply {
                    isEditable    = false
                    isOpaque      = false
                    lineWrap      = true
                    wrapStyleWord = true
                    foreground    = muted
                    font          = Font("JetBrains Mono", Font.PLAIN, 11)
                    border        = JBUI.Borders.empty(2, 18, 0, 0)
                }, BorderLayout.CENTER)
            }
        }
        card.alignmentX = Component.LEFT_ALIGNMENT

        return JPanel().apply {
            layout      = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque    = false
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border      = JBUI.Borders.emptyRight(JBUI.scale(80))
            add(card)
        }
    }

    // ── Agent log card ────────────────────────────────────────────────────

    private fun agentLogCard(event: AgentLogEvent): JComponent {
        val muted = JBUI.CurrentTheme.Label.disabledForeground()
        val time = timeFmt.format(Date(event.timestamp))

        val (icon, textColor) = when (event.type) {
            AgentLogEvent.Type.INTENT       -> AllIcons.General.Balloon              to JBUI.CurrentTheme.Link.Foreground.ENABLED
            AgentLogEvent.Type.AGENT_START  -> AllIcons.Actions.Execute              to Color(80, 180, 80)
            AgentLogEvent.Type.AGENT_DONE   -> AllIcons.RunConfigurations.TestPassed to Color(80, 180, 80)
            AgentLogEvent.Type.PLAN_CREATED -> AllIcons.Vcs.Changelist               to JBUI.CurrentTheme.Link.Foreground.ENABLED
            AgentLogEvent.Type.PLAN_STEP    -> AllIcons.Actions.Forward              to Color(200, 160, 40)
            AgentLogEvent.Type.SUMMARIZE    -> AllIcons.Actions.Collapseall          to muted
            AgentLogEvent.Type.ERROR        -> AllIcons.RunConfigurations.TestFailed to errorColor()
        }

        val headerLabel = JLabel("  $time  ${event.message}", icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = textColor
        }

        val card = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = true
            background = logBg()
            border = JBUI.Borders.empty(3, 8, 3, 8)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(if (event.details != null) 50 else 24))
            add(headerLabel, BorderLayout.NORTH)
        }

        if (event.details != null) {
            card.add(JLabel(event.details).apply {
                font = Font("JetBrains Mono", Font.PLAIN, 10)
                foreground = muted
                border = JBUI.Borders.emptyLeft(18)
            }, BorderLayout.CENTER)
        }

        card.alignmentX = Component.LEFT_ALIGNMENT
        return card
    }

    private fun showToolInspector(msg: UiMessage) {
        val args = msg.functionCall?.arguments ?: "(нет аргументов)"
        val fullContent = when (val r = msg.toolResult) {
            is ToolResult.Ok    -> r.content.ifBlank { "(пустой ответ)" }
            is ToolResult.Error -> "ОШИБКА:\n${r.message}"
            ToolResult.Denied   -> "Выполнение отменено пользователем"
            null                -> "Инструмент еще не выполнился"
        }

        val text = "=== ИНСТРУМЕНТ: ${msg.functionCall?.name} ===\n\n" +
                   "--- ВХОДЯЩИЕ АРГУМЕНТЫ (JSON) ---\n" +
                   "$args\n\n" +
                   "--- ОТВЕТ ОТ MCP СЕРВЕРА ---\n" +
                   fullContent

        val area = JTextArea(text).apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            margin = JBUI.insets(10)
            caretPosition = 0
            lineWrap = true
            wrapStyleWord = true
        }
        val scroll = JScrollPane(area).apply {
            preferredSize = Dimension(700, 500)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
        }

        DialogBuilder(project).apply {
            setTitle("Детали вызова инструмента")
            setCenterPanel(scroll)
            addOkAction()
            show()
        }
    }

    private fun separator(color: Color) = JLabel("·").apply { foreground = color }

    // ── Confirm card ──────────────────────────────────────────────────────

    private fun confirmCard(msg: UiMessage): JComponent {
        val idx = confirmCounter++
        if (msg.onApprove != null && msg.onDeny != null)
            pendingConfirms[idx] = Pair(msg.onApprove, msg.onDeny)

        val fg    = UIUtil.getLabelForeground()
        val bg    = warnBg()
        val muted = JBUI.CurrentTheme.Label.disabledForeground()

        // Parse tool name from content ("**write_file**\n  key: value\n...")
        val lines = msg.content.trimStart().lines()
        val toolName = lines.firstOrNull()?.trim()?.removePrefix("**")?.removeSuffix("**") ?: ""
        val argsText = lines.drop(1).joinToString("\n").trim()

        val titleLabel = JLabel("  $toolName", AllIcons.General.Warning, SwingConstants.LEFT).apply {
            font       = font.deriveFont(Font.BOLD, 13f)
            foreground = fg
        }

        val argsArea = JTextArea(argsText).apply {
            isEditable    = false
            isOpaque      = false
            lineWrap      = true
            wrapStyleWord = true
            foreground    = fg
            font          = Font("JetBrains Mono", Font.PLAIN, 12)
            border        = JBUI.Borders.empty(6, 0, 8, 0)
        }

        val approveBtn = confirmButton("Разрешить", AllIcons.RunConfigurations.TestPassed, accent()) {
            pendingConfirms.remove(idx)?.first?.invoke()
        }
        val denyBtn = confirmButton("Отклонить", AllIcons.RunConfigurations.TestFailed, errorColor()) {
            pendingConfirms.remove(idx)?.second?.invoke()
        }

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(approveBtn); add(denyBtn)
        }

        val card = JPanel(BorderLayout(0, 0)).apply {
            isOpaque    = true
            background  = bg
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border      = compound(
                LeftBarBorder(Color(200, 140, 0), JBUI.scale(3)),
                JBUI.Borders.empty(10, 14, 10, 14)
            )
            add(titleLabel, BorderLayout.NORTH)
            add(argsArea,   BorderLayout.CENTER)
            add(btnRow,     BorderLayout.SOUTH)
        }
        card.alignmentX = Component.LEFT_ALIGNMENT

        return JPanel().apply {
            layout      = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque    = false
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border      = JBUI.Borders.emptyRight(JBUI.scale(80))
            add(card)
        }
    }

    private fun confirmButton(label: String, icon: javax.swing.Icon, bg: Color, action: () -> Unit) =
        JButton(label, icon).apply {
            font                = font.deriveFont(Font.BOLD, 13f)
            foreground          = Color.WHITE
            background          = bg
            isBorderPainted     = false
            isFocusPainted      = false
            cursor              = Cursor(Cursor.HAND_CURSOR)
            border              = JBUI.Borders.empty(6, 16, 6, 16)
            addActionListener   { action(); isEnabled = false }
        }

    // ── Markdown → HTML ───────────────────────────────────────────────────

    private fun buildHtml(text: String, fg: String, muted: String): String {
        var s = escape(text)
        s = s.replace("\n\n", "<br><br>").replace("\n", "<br>")
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        s = s.replace(Regex("(?<![*])\\*(?![*])(.+?)(?<![*])\\*(?![*])"), "<i>$1</i>")
        s = s.replace(Regex("`([^`]+?)`"),         "<code>$1</code>")
        s = s.replace(Regex("### (.+?)(<br>|$)"),  "<h4 style='margin:4px 0'>$1</h4>")
        s = s.replace(Regex("## (.+?)(<br>|$)"),   "<h3 style='margin:4px 0'>$1</h3>")
        return "<html><body>$s</body></html>"
    }

    // ── Segment parser ────────────────────────────────────────────────────

    sealed class Segment {
        data class Text(val content: String) : Segment()
        data class Code(val lang: String, val content: String) : Segment()
    }

    private fun parseSegments(text: String): List<Segment> {
        val result  = mutableListOf<Segment>()
        val pattern = Regex("```(\\w*)\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        var lastEnd = 0
        for (mr in pattern.findAll(text)) {
            if (mr.range.first > lastEnd)
                result.add(Segment.Text(text.substring(lastEnd, mr.range.first)))
            result.add(Segment.Code(mr.groupValues[1], mr.groupValues[2]))
            lastEnd = mr.range.last + 1
        }
        if (lastEnd < text.length)
            result.add(Segment.Text(text.substring(lastEnd)))
        return result.filter { it !is Segment.Text || it.content.isNotBlank() }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String) =
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    private fun confirmAndRun(command: String) {
        val choice = Messages.showYesNoDialog(
            project,
            "Выполнить:\n\n${command.take(200)}",
            "Запустить команду",
            Messages.getQuestionIcon()
        )
        if (choice != Messages.YES) return
        CompletableFuture.runAsync {
            val res = RunTerminalTool().execute(project, mapOf("command" to command, "timeout_seconds" to 60))
            val out = when (res) {
                is ToolResult.Ok    -> res.content.ifBlank { "(нет вывода)" }
                is ToolResult.Error -> "Ошибка: ${res.message}"
                ToolResult.Denied   -> "Отменено"
            }
            SwingUtilities.invokeLater { onCommandResult?.invoke(command, out) }
        }
    }

    private fun summarizeArgs(json: String): String {
        val m = Regex(""""(\w+)"\s*:\s*"([^"]{0,40})"""").find(json) ?: return ""
        val v = m.groupValues[2].let { if (it.length >= 40) "$it…" else it }
        return "${m.groupValues[1]}=\"$v\""
    }

    private fun looksLikeCode(text: String) =
        text.lines().size > 3 && (
            text.trimStart().startsWith("package ") ||
            text.trimStart().startsWith("import ")  ||
            text.trimStart().startsWith("class ")   ||
            text.trimStart().startsWith("fun ")     ||
            (text.contains("{") && text.contains("}"))
        )

    // ── Layout helpers ────────────────────────────────────────────────────

    private fun spacer(px: Int): Component = Box.createVerticalStrut(JBUI.scale(px))

    // ── Colors ────────────────────────────────────────────────────────────

    private fun accent()      = JBUI.CurrentTheme.Link.Foreground.ENABLED
    private fun errorColor()  = JBUI.CurrentTheme.NotificationError.foregroundColor()

    // Subtle tinted backgrounds — defined as JBColor for consistent light/dark rendering
    private fun logBg()  = JBColor(Color(245, 248, 255), Color(48, 50, 58))
    private fun toolBg() = JBColor(Color(242, 245, 252), Color(45, 48, 60))
    private fun warnBg() = JBColor(Color(255, 251, 235), Color(55, 50, 38))
    private fun codeBg() = JBColor(Color(238, 240, 245), Color(40, 42, 47))
    private fun codeBorderColor() = JBColor.border()

    private fun Color.hex() = "#%02x%02x%02x".format(red, green, blue)

    private fun escape(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")

    // ── Custom borders ────────────────────────────────────────────────────

    private class LeftBarBorder(private val color: Color, private val w: Int) : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            (g.create() as Graphics2D).also { g2 ->
                g2.color = color
                g2.fillRect(x, y, w, height)
                g2.dispose()
            }
        }
        override fun getBorderInsets(c: Component) = Insets(0, w, 0, 0)
    }

    private fun compound(outer: Border, inner: Border): Border =
        BorderFactory.createCompoundBorder(outer, inner)
}
