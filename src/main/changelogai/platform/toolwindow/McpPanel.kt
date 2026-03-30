package changelogai.platform.toolwindow

import changelogai.core.mcp.McpJsonSync
import changelogai.core.mcp.McpState
import changelogai.feature.gigacodeae.mcp.McpClient
import changelogai.feature.gigacodeae.mcp.McpService
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.*

/**
 * MCP-настройки через JSON-файл (аналогично Claude Code / Cursor).
 *
 * Два файла:
 *  - Проект: {projectDir}/.mcp.json
 *  - Глобальный: ~/.changelogai/mcp.json
 */
class McpPanel(private val project: Project) {

    private val projectFile  get() = File(project.basePath ?: System.getProperty("user.home"), ".mcp.json")
    private val globalFile   = File(System.getProperty("user.home"), ".changelogai/mcp.json")

    private var currentFile: File = projectFile

    // ── UI ────────────────────────────────────────────────────────────────

    private val projectRadio = JRadioButton("Проект (.mcp.json)").apply { isSelected = true }
    private val globalRadio  = JRadioButton("Глобальный (~/.changelogai/mcp.json)")
    private val fileLabel    = JBLabel("").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(10))
        foreground = JBColor.GRAY
    }
    private val openBtn = JButton("Открыть в IDE", AllIcons.Actions.EditSource).apply {
        toolTipText = "Открыть файл в редакторе IDE"
        font = font.deriveFont(Font.PLAIN, 12f)
    }

    private val editor = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        background = UIUtil.getTextFieldBackground()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(6, 8)
        tabSize = 2
    }

    private val saveBtn     = JButton("Сохранить и переподключить", AllIcons.Actions.MenuSaveall).apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val templateBtn = JButton("Шаблон", AllIcons.FileTypes.Json).apply {
        toolTipText = "Вставить пример конфигурации"
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val testBtn     = JButton("Тест подключений", AllIcons.Actions.Refresh).apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }

    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(4, 6)
    }
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    val panel: JPanel = build()

    init {
        loadFile(currentFile)

        val group = ButtonGroup().also { it.add(projectRadio); it.add(globalRadio) }

        projectRadio.addActionListener { switchFile(projectFile) }
        globalRadio.addActionListener  { switchFile(globalFile) }

        saveBtn.addActionListener     { save() }
        templateBtn.addActionListener { insertTemplate() }
        testBtn.addActionListener     { testConnections() }
        openBtn.addActionListener     { openInIde() }
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private fun build(): JPanel {
        // File selector row
        val selectorRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(projectRadio)
            add(globalRadio)
        }

        val pathRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(fileLabel, BorderLayout.CENTER)
            add(openBtn, BorderLayout.EAST)
        }

        val topPanel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(selectorRow, BorderLayout.NORTH)
            add(pathRow, BorderLayout.CENTER)
        }

        // JSON editor
        val editorScroll = JBScrollPane(editor).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }

        // Bottom toolbar
        val bottomToolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(saveBtn)
            add(templateBtn)
            add(testBtn)
        }

        // Log
        val logScroll = JBScrollPane(logArea).apply {
            preferredSize = Dimension(0, JBUI.scale(100))
            border = JBUI.Borders.customLine(JBColor.border())
        }

        val bottomPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(bottomToolbar, BorderLayout.NORTH)
            add(logScroll, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(topPanel,    BorderLayout.NORTH)
            add(editorScroll, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
    }

    // ── File operations ───────────────────────────────────────────────────

    private fun switchFile(file: File) {
        currentFile = file
        loadFile(file)
    }

    private fun loadFile(file: File) {
        fileLabel.text = file.absolutePath
        editor.text = McpJsonSync.loadForPanel(file)
        editor.caretPosition = 0
    }

    private fun save() {
        val json = editor.text.trim()
        if (json.isEmpty()) { log("⚠ Файл пустой — не сохранён"); return }

        // Validate JSON
        val entries = try {
            McpJsonSync.fromJson(json)
        } catch (e: Exception) {
            log("✗ Ошибка JSON: ${e.message}"); return
        }

        // Write file
        try {
            currentFile.parentFile?.mkdirs()
            currentFile.writeText(json, Charsets.UTF_8)
            log("✓ Сохранено в ${currentFile.name} (${entries.size} серверов)")
        } catch (e: Exception) {
            log("✗ Ошибка записи: ${e.message}"); return
        }

        // Sync McpState
        McpJsonSync.syncFromFile(currentFile)

        // Refresh virtual file system so IDE sees the change
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(currentFile)

        // Reconnect
        log("⏳ Переподключение MCP...")
        McpService.getInstance(project).reconnect { msg ->
            SwingUtilities.invokeLater { log(msg) }
        }
    }

    private fun insertTemplate() {
        if (editor.text.isBlank() || editor.text.trim() == McpJsonSync.TEMPLATE) {
            editor.text = McpJsonSync.TEMPLATE
        } else {
            val choice = JOptionPane.showConfirmDialog(
                panel,
                "Заменить текущий JSON шаблоном?",
                "Вставить шаблон",
                JOptionPane.YES_NO_OPTION
            )
            if (choice == JOptionPane.YES_OPTION) editor.text = McpJsonSync.TEMPLATE
        }
    }

    private fun openInIde() {
        // Ensure file exists before opening
        if (!currentFile.exists()) {
            currentFile.parentFile?.mkdirs()
            currentFile.writeText(McpJsonSync.loadForPanel(currentFile), Charsets.UTF_8)
        }
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(currentFile)
        if (vFile != null) {
            FileEditorManager.getInstance(project).openFile(vFile, true)
        } else {
            log("⚠ Файл не найден: ${currentFile.absolutePath}")
        }
    }

    // ── Connection test ───────────────────────────────────────────────────

    private fun testConnections() {
        val servers = McpState.getInstance().servers.filter { it.enabled }
        if (servers.isEmpty()) {
            log("⚠ Нет серверов. Сохраните конфигурацию сначала.")
            return
        }
        testBtn.isEnabled = false
        logArea.text = ""
        log("🔍 Тестируем ${servers.size} серверов...")

        CompletableFuture.runAsync {
            servers.forEach { entry ->
                log("━━━ ${entry.name} [${entry.type}] ━━━")
                val cfg = entry.toServerConfig()
                val future = CompletableFuture.supplyAsync {
                    val client = McpClient(cfg)
                    client.connect()
                    val tools = client.listTools()
                    client.close()
                    tools
                }
                try {
                    val tools = future.get(10, TimeUnit.SECONDS)
                    tools.forEach { t ->
                        log("     • ${t.name.removePrefix("${entry.name}__")}  — ${t.description.take(60)}")
                    }
                    log("  ✓ ${tools.size} инструментов\n")
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    log("  ✗ Таймаут подключения (10 сек)\n")
                } catch (e: Exception) {
                    log("  ✗ ${e.cause?.message ?: e.message}\n")
                }
            }
            SwingUtilities.invokeLater { testBtn.isEnabled = true }
        }
    }

    // ── Log ───────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        val line = "[${LocalTime.now().format(timeFmt)}] $msg\n"
        SwingUtilities.invokeLater {
            logArea.append(line)
            logArea.caretPosition = logArea.document.length
        }
    }
}
