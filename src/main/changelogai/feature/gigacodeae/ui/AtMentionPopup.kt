package changelogai.feature.gigacodeae.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * Всплывающий список файлов проекта при вводе @ в поле чата.
 * Файлы отображаются с иконками типа, жирным именем и приглушённым путём.
 */
class AtMentionPopup(
    private val project: Project,
    private val textArea: JTextArea,
    private val onSelect: (relPath: String) -> Unit
) {
    private data class FileEntry(val relPath: String, val name: String, val dir: String, val icon: Icon?)

    private var window: JWindow? = null
    private val model = DefaultListModel<FileEntry>()
    private val list = JBList(model)
    private val queryLabel = JLabel("@")
    private val countLabel = JLabel("")

    private val allFiles: List<FileEntry> by lazy { loadProjectFiles() }

    var isShowing: Boolean = false
        private set

    fun show(query: String) {
        updateFilter(query)
        if (model.isEmpty) return
        val w = getOrCreateWindow()
        positionWindow(w)
        w.pack()
        w.isVisible = true
        isShowing = true
        if (list.model.size > 0) list.selectedIndex = 0
    }

    fun hide() {
        window?.isVisible = false
        isShowing = false
    }

    fun updateFilter(query: String) {
        val filtered = if (query.isBlank()) allFiles.take(50)
                       else allFiles.filter { it.relPath.contains(query, ignoreCase = true) }.take(50)
        model.clear()
        filtered.forEach { model.addElement(it) }
        if (!model.isEmpty) list.selectedIndex = 0

        val q = if (query.isBlank()) "" else query
        queryLabel.text = if (q.isBlank()) "@ Выберите файл" else "@$q"
        countLabel.text = "${filtered.size} файлов"

        window?.let { if (it.isVisible) { positionWindow(it); it.pack() } }
    }

    fun selectNext() {
        val i = list.selectedIndex
        if (i < model.size - 1) list.selectedIndex = i + 1
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    fun selectPrev() {
        val i = list.selectedIndex
        if (i > 0) list.selectedIndex = i - 1
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    fun selectCurrent() {
        val entry = list.selectedValue ?: return
        hide()
        onSelect(entry.relPath)
    }

    // ── Window construction ───────────────────────────────────────────────

    private fun getOrCreateWindow(): JWindow {
        return window ?: run {
            val owner = SwingUtilities.getWindowAncestor(textArea)
            JWindow(owner).also { w ->
                list.selectionMode = ListSelectionModel.SINGLE_SELECTION
                list.cellRenderer = FileEntryRenderer()
                list.background = popupBg()
                list.selectionBackground = JBUI.CurrentTheme.List.Selection.background(true)
                list.selectionForeground = JBUI.CurrentTheme.List.Selection.foreground(true)
                list.fixedCellHeight = JBUI.scale(32)
                list.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { selectCurrent() }
                })

                // ── header
                val header = JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = true
                    background = headerBg()
                    border = JBUI.Borders.empty(6, 10, 6, 10)
                    val atIcon = JLabel(AllIcons.Actions.Find).apply { isOpaque = false }
                    queryLabel.apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                        foreground = JBUI.CurrentTheme.Label.foreground()
                        isOpaque = false
                    }
                    countLabel.apply {
                        font = font.deriveFont(Font.PLAIN, 10f)
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                        isOpaque = false
                    }
                    add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                        isOpaque = false; add(atIcon); add(queryLabel)
                    }, BorderLayout.WEST)
                    add(countLabel, BorderLayout.EAST)
                }

                // ── footer hint
                val footer = JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = headerBg()
                    border = JBUI.Borders.empty(4, 10)
                    val hint = JLabel("↑↓ навигация  •  Enter выбор  •  Esc закрыть").apply {
                        font = font.deriveFont(Font.PLAIN, 10f)
                        foreground = JBUI.CurrentTheme.Label.disabledForeground()
                    }
                    add(hint, BorderLayout.WEST)
                }

                val scroll = JBScrollPane(list).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(JBUI.scale(440), JBUI.scale(220))
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                    viewport.background = popupBg()
                }

                val panel = JPanel(BorderLayout()).apply {
                    background = popupBg()
                    border = PopupBorder()
                    add(header, BorderLayout.NORTH)
                    add(scroll, BorderLayout.CENTER)
                    add(footer, BorderLayout.SOUTH)
                }

                w.contentPane = panel
                w.isAlwaysOnTop = true
                window = w
            }
        }
    }

    private fun positionWindow(w: JWindow) {
        val popupH = JBUI.scale(290)
        val popupW = JBUI.scale(440)
        try {
            // Позиционируем от левого края text area, выше поля ввода
            val loc = textArea.locationOnScreen
            val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            val x = loc.x.coerceAtMost(screenBounds.width - popupW - 8)
            val y = (loc.y - popupH - 6).coerceAtLeast(8)
            w.setLocation(x, y)
            w.setSize(popupW, popupH)
        } catch (_: Exception) { /* компонент ещё не добавлен в иерархию */ }
    }

    // ── Data loading ──────────────────────────────────────────────────────

    private fun loadProjectFiles(): List<FileEntry> {
        val basePath = project.basePath ?: return emptyList()
        val result = mutableListOf<FileEntry>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory) {
                val rel = vf.path.removePrefix("$basePath/")
                if (isRelevantFile(rel)) {
                    val icon = fileIcon(vf)
                    val slash = rel.lastIndexOf('/')
                    val name = if (slash >= 0) rel.substring(slash + 1) else rel
                    val dir  = if (slash >= 0) rel.substring(0, slash + 1) else ""
                    result.add(FileEntry(rel, name, dir, icon))
                }
            }
            true
        }
        return result.sortedWith(compareBy({ it.dir }, { it.name }))
    }

    private fun isRelevantFile(rel: String) =
        !rel.startsWith(".") &&
        !rel.contains("/.") &&
        !rel.contains("/build/") &&
        !rel.contains("/.gradle/") &&
        !rel.endsWith(".class")

    private fun fileIcon(vf: VirtualFile): Icon? =
        try { FileTypeManager.getInstance().getFileTypeByFileName(vf.name).icon }
        catch (_: Exception) { AllIcons.FileTypes.Unknown }

    // ── Colors ────────────────────────────────────────────────────────────

    private fun popupBg()  = JBUI.CurrentTheme.ToolWindow.background()
    private fun headerBg(): Color {
        val b = JBUI.CurrentTheme.ToolWindow.background()
        return Color(
            (b.red   * 0.92).toInt().coerceIn(0, 255),
            (b.green * 0.92).toInt().coerceIn(0, 255),
            (b.blue  * 0.95).toInt().coerceIn(0, 255)
        )
    }

    // ── Cell Renderer ─────────────────────────────────────────────────────

    private inner class FileEntryRenderer : JPanel(BorderLayout(0, 0)), ListCellRenderer<FileEntry> {
        private val iconLabel = JLabel()
        private val nameLabel = JLabel()
        private val dirLabel  = JLabel()

        init {
            isOpaque = true
            border = JBUI.Borders.empty(0, 8)

            iconLabel.border = JBUI.Borders.emptyRight(6)
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 12f)
            dirLabel.font  = dirLabel.font.deriveFont(Font.PLAIN, 11f)

            val textPane = JPanel(BorderLayout(4, 0)).apply { isOpaque = false }
            textPane.add(nameLabel, BorderLayout.WEST)
            textPane.add(dirLabel,  BorderLayout.CENTER)

            add(iconLabel, BorderLayout.WEST)
            add(textPane,  BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out FileEntry>, value: FileEntry?, index: Int,
            isSelected: Boolean, hasFocus: Boolean
        ): Component {
            val entry = value ?: return this

            background = if (isSelected) list.selectionBackground else list.background
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            dirLabel.foreground  = if (isSelected)
                list.selectionForeground.let { Color(it.red, it.green, it.blue, 160) }
            else
                JBUI.CurrentTheme.Label.disabledForeground()

            iconLabel.icon = entry.icon ?: AllIcons.FileTypes.Unknown
            nameLabel.text = entry.name
            dirLabel.text  = if (entry.dir.isNotBlank()) "  ${entry.dir}" else ""

            return this
        }
    }

    // ── Border ────────────────────────────────────────────────────────────

    private class PopupBorder : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBUI.CurrentTheme.ActionButton.focusedBorder()
            g2.drawRoundRect(x, y, w - 1, h - 1, 8, 8)
            g2.dispose()
        }
        override fun getBorderInsets(c: Component) = Insets(1, 1, 1, 1)
    }
}
