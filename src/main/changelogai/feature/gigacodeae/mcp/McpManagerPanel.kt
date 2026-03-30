package changelogai.feature.gigacodeae.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Панель управления MCP-серверами.
 * Показывает авто-обнаруженные серверы (из IDE конфигов) + вручную добавленные.
 * Для каждого сервера: статус, список инструментов, кнопка Probe.
 */
class McpManagerPanel(private val project: Project) {

    private data class ServerRow(
        val discovered: McpDiscovery.DiscoveredServer?,
        val manual: McpEntry?,
        var status: Status = Status.UNKNOWN,
        var tools: List<McpToolInfo> = emptyList(),
        var error: String? = null
    ) {
        val name: String get() = discovered?.name ?: manual?.name ?: "?"
        val source: String get() = discovered?.source ?: "manual"
        val config: McpServerConfig? get() = discovered?.config ?: manual?.toServerConfig()
    }

    enum class Status { UNKNOWN, CONNECTING, OK, ERROR }

    private val feed = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private val refreshBtn = JButton("Обновить", AllIcons.Actions.Refresh)
    private var rows: List<ServerRow> = emptyList()

    val panel: JPanel = buildLayout()

    init {
        refreshBtn.addActionListener { reload() }
        reload()
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private fun buildLayout(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(refreshBtn)
            add(statusLabel)
        }
        return JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(feed).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private fun reload() {
        refreshBtn.isEnabled = false
        statusLabel.text = "Сканирование…"
        feed.removeAll()
        feed.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
        feed.revalidate(); feed.repaint()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MCP: сканирование…", false) {
            override fun run(indicator: ProgressIndicator) {
                // Авто-обнаруженные
                val discovered = McpDiscovery.discover(project)
                // Вручную добавленные (только те чьих имён нет среди discovered)
                val discoveredNames = discovered.map { it.name }.toSet()
                val manual = McpState.getInstance().servers
                    .filter { it.name.isNotBlank() && it.name !in discoveredNames }

                val newRows = discovered.map { ServerRow(discovered = it, manual = null) } +
                              manual.map { ServerRow(discovered = null, manual = it) }

                SwingUtilities.invokeLater {
                    rows = newRows
                    rebuildFeed()
                    statusLabel.text = "${newRows.size} серверов"
                    refreshBtn.isEnabled = true
                    if (newRows.isEmpty()) {
                        statusLabel.text = "MCP-серверы не найдены"
                    }
                }

                // Probe каждый сервер в фоне
                newRows.forEach { row -> probeRow(row) }
            }
        })
    }

    private fun rebuildFeed() {
        feed.removeAll()
        if (rows.isEmpty()) {
            feed.add(emptyHint())
        } else {
            rows.forEach { row -> feed.add(serverCard(row)) }
        }
        feed.add(Box.createVerticalGlue())
        feed.revalidate()
        feed.repaint()
    }

    private fun probeRow(row: ServerRow) {
        val cfg = row.config ?: return
        row.status = Status.CONNECTING
        SwingUtilities.invokeLater { rebuildFeed() }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MCP: probe ${row.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = McpClient(cfg)
                    client.connect()
                    val tools = client.listTools()
                    client.close()
                    row.status = Status.OK
                    row.tools = tools
                    row.error = null
                } catch (e: Exception) {
                    row.status = Status.ERROR
                    row.tools = emptyList()
                    row.error = e.message
                }
                SwingUtilities.invokeLater { rebuildFeed() }
            }
        })
    }

    // ── Cards ───────────────────────────────────────────────────────────────

    private fun serverCard(row: ServerRow): JPanel {
        val card = JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(4, 6),
                JBUI.Borders.customLine(JBColor.border(), 1)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // ── Header row ──────────────────────────────────────────────────────
        val statusDot = statusDot(row.status)
        val nameLabel = JLabel(row.name).apply { font = font.deriveFont(Font.BOLD, 13f) }
        val sourceBadge = badge(row.source)
        val typeBadge = row.config?.type?.name?.let { badge(it.lowercase()) }

        val probeBtn = JButton(AllIcons.Actions.Execute).apply {
            toolTipText = "Проверить соединение и загрузить инструменты"
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            addActionListener { probeRow(row) }
        }

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
            add(statusDot); add(nameLabel); add(sourceBadge)
            if (typeBadge != null) add(typeBadge)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerLeft, BorderLayout.CENTER)
            add(probeBtn, BorderLayout.EAST)
        }

        card.add(header, BorderLayout.NORTH)

        // ── Error ────────────────────────────────────────────────────────────
        if (row.error != null) {
            card.add(JLabel("  ${row.error}").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor(Color(180, 60, 60), Color(220, 100, 100))
                border = JBUI.Borders.empty(0, 8, 4, 4)
            }, BorderLayout.CENTER)
        }

        // ── Tools ────────────────────────────────────────────────────────────
        if (row.tools.isNotEmpty()) {
            val toolsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(2, 8, 6, 8)
            }
            row.tools.forEach { tool ->
                val rawToolName = tool.name.removePrefix("${row.name}__")
                toolsPanel.add(toolRow(rawToolName, tool.description))
            }
            card.add(toolsPanel, BorderLayout.SOUTH)
        } else if (row.status == Status.OK) {
            card.add(JLabel("  Инструментов нет").apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(0, 8, 4, 4)
            }, BorderLayout.SOUTH)
        }

        return card
    }

    private fun toolRow(name: String, description: String): JPanel =
        JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(JLabel(AllIcons.Nodes.Plugin).apply { }, BorderLayout.WEST)
            add(JLabel("<html><b>$name</b>${if (description.isNotBlank()) " — <span style='color:gray'>$description</span>" else ""}</html>").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
            }, BorderLayout.CENTER)
        }

    private fun emptyHint(): JPanel =
        JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(JLabel("<html><center>MCP-серверы не найдены.<br>" +
                "Добавьте серверы в <b>.cursor/mcp.json</b>, <b>mcp.json</b><br>" +
                "или в Claude Desktop, затем нажмите Обновить.</center></html>").apply {
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
        }

    // ── Widgets ─────────────────────────────────────────────────────────────

    private fun statusDot(status: Status): JComponent {
        val color = when (status) {
            Status.OK         -> JBColor(Color(60, 180, 60),  Color(70, 170, 70))
            Status.ERROR      -> JBColor(Color(200, 60, 60),  Color(200, 80, 80))
            Status.CONNECTING -> JBColor(Color(200, 160, 40), Color(200, 160, 40))
            Status.UNKNOWN    -> JBColor.border()
        }
        return object : JComponent() {
            init { preferredSize = Dimension(JBUI.scale(10), JBUI.scale(10)) }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(0, 0, width - 1, height - 1)
                g2.dispose()
            }
        }
    }

    private fun badge(text: String): JLabel {
        val colors = mapOf(
            "cursor-project" to Pair(Color(70, 130, 200), Color(40, 100, 180)),
            "cursor-global"  to Pair(Color(70, 130, 200), Color(40, 100, 180)),
            "claude-desktop" to Pair(Color(180, 100, 40), Color(160, 80, 20)),
            "project-mcp"    to Pair(Color(80, 160, 80),  Color(60, 140, 60)),
            "manual"         to Pair(Color(120, 120, 120), Color(100, 100, 100)),
            "stdio"          to Pair(Color(100, 80, 180),  Color(80, 60, 160)),
            "http"           to Pair(Color(60, 160, 160),  Color(40, 140, 140)),
        )
        val (light, dark) = colors[text] ?: Pair(Color(120, 120, 120), Color(100, 100, 100))
        return JLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 10f)
            foreground = Color.WHITE
            background = JBColor(light, dark)
            isOpaque = true
            border = JBUI.Borders.empty(1, 5)
        }
    }
}
