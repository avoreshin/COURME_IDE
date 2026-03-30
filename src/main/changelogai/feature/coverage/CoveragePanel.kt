package changelogai.feature.coverage

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

class CoveragePanel(private val project: Project) {
    private val log = Logger.getInstance(CoveragePanel::class.java)

    // ── State ──────────────────────────────────────────────────────────────

    private var report: CoverageReport? = null

    // ── Summary widgets ────────────────────────────────────────────────────

    private val sourceLabel = JLabel("Нет данных").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private val classBar  = CoverageBar()
    private val methodBar = CoverageBar()
    private val instrBar  = CoverageBar()

    private val classLabel  = summaryLabel("Классы")
    private val methodLabel = summaryLabel("Методы")
    private val instrLabel  = summaryLabel("Инструкции")

    // ── Table ──────────────────────────────────────────────────────────────

    private val tableModel = CoverageTableModel()
    private val table = buildTable()
    private val filterField = JTextField().apply {
        toolTipText = "Фильтр по имени класса"
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    private val runBtn = JButton("Запустить тесты", AllIcons.Actions.Execute).apply {
        toolTipText = "Выполнить ./gradlew test jacocoTestReport"
    }
    private val refreshBtn = JButton("Обновить", AllIcons.Actions.Refresh).apply {
        toolTipText = "Перечитать отчёт"
    }
    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    // ── Root panel ─────────────────────────────────────────────────────────

    val panel: JPanel = buildLayout()

    init {
        runBtn.addActionListener   { runTests() }
        refreshBtn.addActionListener { refresh() }
        filterField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })
        refresh()
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private fun buildLayout(): JPanel {
        val summaryPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            val gbc = GridBagConstraints().apply { insets = Insets(3, 6, 3, 6); fill = GridBagConstraints.HORIZONTAL }

            fun row(label: JLabel, bar: CoverageBar, pct: JLabel, row: Int) {
                gbc.gridy = row
                gbc.gridx = 0; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                add(label, gbc)
                gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
                add(bar, gbc)
                gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
                add(pct, gbc)
            }
            row(summaryLabel("Классы"),     classBar,  classLabel,  0)
            row(summaryLabel("Методы"),     methodBar, methodLabel, 1)
            row(summaryLabel("Инструкции"), instrBar,  instrLabel,  2)
        }

        val sourceRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(JLabel(AllIcons.General.Information))
            add(sourceLabel)
        }

        val topPanel = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(6, 6, 0, 6)
            add(summaryPanel, BorderLayout.CENTER)
            add(sourceRow, BorderLayout.SOUTH)
        }

        val filterRow = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.empty(6, 6, 4, 6)
            add(JLabel(AllIcons.Actions.Find), BorderLayout.WEST)
            add(filterField, BorderLayout.CENTER)
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(runBtn); add(refreshBtn); add(statusLabel)
        }

        return JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(JPanel(BorderLayout(0, 0)).apply {
                add(topPanel, BorderLayout.NORTH)
                add(filterRow, BorderLayout.CENTER)
                add(toolbar, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JBScrollPane(table).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1)
            }, BorderLayout.CENTER)
        }
    }

    // ── Table ──────────────────────────────────────────────────────────────

    private fun buildTable(): JTable {
        val t = JTable(tableModel)
        t.rowHeight = JBUI.scale(22)
        t.setShowGrid(false)
        t.intercellSpacing = Dimension(0, 0)
        t.tableHeader.reorderingAllowed = false
        t.selectionBackground = JBUI.CurrentTheme.List.Selection.background(true)
        t.selectionForeground = JBUI.CurrentTheme.List.Selection.foreground(true)

        // Сортировка
        val sorter = TableRowSorter(tableModel)
        t.rowSorter = sorter

        // Рендереры
        val copyRenderer = CopyButtonRenderer()
        val copyEditor   = CopyButtonEditor(tableModel)
        t.columnModel.getColumn(0).apply {
            maxWidth = JBUI.scale(28); minWidth = JBUI.scale(28)
            cellRenderer = copyRenderer; cellEditor = copyEditor
        }
        t.columnModel.getColumn(1).apply { preferredWidth = JBUI.scale(190); cellRenderer = ClassNameRenderer() }
        t.columnModel.getColumn(2).apply { preferredWidth = JBUI.scale(170); cellRenderer = PackageRenderer() }
        t.columnModel.getColumn(3).apply { preferredWidth = JBUI.scale(110); cellRenderer = BarRenderer() }
        t.columnModel.getColumn(4).apply { preferredWidth = JBUI.scale(110); cellRenderer = BarRenderer() }
        t.columnModel.getColumn(5).apply { preferredWidth = JBUI.scale(160); cellRenderer = UncoveredRenderer() }

        return t
    }

    private fun applyFilter() {
        val text = filterField.text.trim()
        val sorter = table.rowSorter as? TableRowSorter<*> ?: return
        if (text.isBlank()) sorter.rowFilter = null
        else sorter.rowFilter = RowFilter.regexFilter("(?i)${Regex.escape(text)}", 1, 2)
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private fun refresh() {
        statusLabel.text = "Загрузка…"
        statusLabel.icon = AllIcons.Actions.Execute
        runBtn.isEnabled = false; refreshBtn.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Анализ покрытия…", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val r = CoverageAnalyzer.analyze(project)
                    SwingUtilities.invokeLater { applyReport(r) }
                } catch (e: Exception) {
                    log.warn("Coverage refresh failed", e)
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Ошибка: ${e.message ?: e::class.simpleName}"
                        statusLabel.icon = AllIcons.General.Error
                        runBtn.isEnabled = true; refreshBtn.isEnabled = true
                    }
                }
            }
        })
    }

    private fun applyReport(r: CoverageReport) {
        report = r
        tableModel.setData(r.classes)

        classBar.value  = r.classCoverage
        methodBar.value = r.methodCoverage
        instrBar.value  = r.instructionCoverage

        classLabel.text  = "${pct(r.classCoverage)} (${r.classes.count { it.coveredMethods > 0 }}/${r.classes.size})"
        methodLabel.text = "${pct(r.methodCoverage)} (${r.coveredMethods}/${r.totalMethods})"
        instrLabel.text  = "${pct(r.instructionCoverage)} (${r.coveredInstructions}/${r.totalInstructions})"

        val buildSys = project.basePath?.let { CoverageAnalyzer.detectBuildSystem(it).name.lowercase() } ?: ""
        val sourceText = when (r.source) {
            "jacoco" -> "Источник: JaCoCo XML ($buildSys)"
            "llm"    -> "Источник: LLM-анализ ($buildSys, нет JaCoCo отчёта — запустите тесты)"
            else     -> "Источник: ${r.source}"
        }
        sourceLabel.text = sourceText

        statusLabel.text = " "; statusLabel.icon = null
        runBtn.isEnabled = true; refreshBtn.isEnabled = true
    }

    // ── Run tests ──────────────────────────────────────────────────────────

    private fun runTests() {
        runBtn.isEnabled = false; refreshBtn.isEnabled = false
        statusLabel.text = "Запуск тестов…"; statusLabel.icon = AllIcons.Actions.Execute

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Запуск тестов…", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val basePath = project.basePath ?: return
                    val cmd = CoverageAnalyzer.buildTestCommand(basePath)
                    val buildSystem = CoverageAnalyzer.detectBuildSystem(basePath)

                    SwingUtilities.invokeLater {
                        statusLabel.text = "Запуск ${buildSystem.name.lowercase()}: ${cmd.joinToString(" ")}…"
                    }

                    log.info(
                        "Running coverage tests: buildSystem=${buildSystem.name}, cmd=${cmd.joinToString(" ")}"
                    )
                    val process = ProcessBuilder(cmd)
                        .directory(File(basePath))
                        .redirectErrorStream(true)
                        .start()

                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    SwingUtilities.invokeLater {
                        if (exitCode == 0) {
                            statusLabel.text = "Тесты завершены"
                            statusLabel.icon = AllIcons.RunConfigurations.TestPassed
                        } else {
                            statusLabel.text = "Ошибка (exit $exitCode)"
                            statusLabel.icon = AllIcons.RunConfigurations.TestFailed
                        }
                        refresh()
                    }

                    if (exitCode != 0) {
                        log.warn("Coverage tests failed: exitCode=$exitCode, output=${output.take(4000)}")
                    } else {
                        log.info("Coverage tests finished successfully; output=${output.take(2000)}")
                    }
                } catch (e: Exception) {
                    log.warn("Coverage tests execution failed", e)
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Ошибка: ${e.message}"
                        statusLabel.icon = AllIcons.General.Error
                        runBtn.isEnabled = true; refreshBtn.isEnabled = true
                    }
                }
            }
        })
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun summaryLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 12f)
    }

    private fun pct(v: Double) = "${(v * 100).toInt()}%"
}

// ── Table Model ────────────────────────────────────────────────────────────

private class CoverageTableModel : AbstractTableModel() {
    private val COLS = arrayOf("", "Класс", "Пакет", "Методы", "Инструкции", "Непокрытые методы")
    private var data: List<ClassCoverage> = emptyList()

    fun setData(d: List<ClassCoverage>) { data = d; fireTableDataChanged() }
    fun getClass(row: Int): ClassCoverage? = data.getOrNull(row)

    override fun getRowCount() = data.size
    override fun getColumnCount() = COLS.size
    override fun getColumnName(col: Int) = COLS[col]
    override fun isCellEditable(row: Int, col: Int) = col == 0
    override fun getColumnClass(col: Int) = when (col) { 3, 4 -> Double::class.java; else -> String::class.java }

    override fun getValueAt(row: Int, col: Int): Any {
        val c = data[row]
        return when (col) {
            0 -> ""    // кнопка копирования (иконка в рендерере)
            1 -> c.name
            2 -> c.packageName
            3 -> c.methodCoverage
            4 -> c.instructionCoverage
            5 -> c.methods.filter { !it.covered }.joinToString(", ") { it.name }
            else -> ""
        }
    }
}

// ── Cell Renderers ─────────────────────────────────────────────────────────

private class ClassNameRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
        super.getTableCellRendererComponent(t, v, sel, foc, r, c)
        icon = AllIcons.Nodes.Class
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(0, 6)
        return this
    }
}

private class PackageRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
        super.getTableCellRendererComponent(t, v, sel, foc, r, c)
        foreground = if (sel) t.selectionForeground else JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.empty(0, 6)
        return this
    }
}

private class BarRenderer : DefaultTableCellRenderer() {
    private val bar = CoverageBar()

    override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
        val pct = (v as? Double) ?: 0.0
        bar.value = pct
        bar.background = if (sel) t.selectionBackground else t.background
        bar.isOpaque = true
        return bar
    }
}

private class UncoveredRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
        super.getTableCellRendererComponent(t, v, sel, foc, r, c)
        val s = v?.toString() ?: ""
        text = s
        foreground = if (sel) t.selectionForeground
                     else if (s.isNotBlank()) JBColor(Color(180, 60, 60), Color(220, 100, 100))
                     else JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.empty(0, 6)
        toolTipText = if (s.isNotBlank()) s else null
        return this
    }
}

// ── Copy Button ────────────────────────────────────────────────────────────

private class CopyButtonRenderer : JButton(AllIcons.Actions.Copy), TableCellRenderer {
    init {
        isOpaque = true
        toolTipText = "Копировать полное имя класса"
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
    }
    override fun getTableCellRendererComponent(
        t: JTable, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int
    ): Component {
        background = if (sel) t.selectionBackground else t.background
        return this
    }
}

private class CopyButtonEditor(
    private val model: CoverageTableModel
) : AbstractCellEditor(), TableCellEditor {
    private val btn = JButton(AllIcons.Actions.Copy).apply {
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
        toolTipText = "Копировать полное имя класса"
    }
    private var currentRow = -1

    init {
        btn.addActionListener {
            fireEditingStopped()
            val cls = model.getClass(currentRow) ?: return@addActionListener
            val selection = StringSelection(cls.name)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
        }
    }

    override fun getTableCellEditorComponent(
        t: JTable, v: Any?, sel: Boolean, row: Int, col: Int
    ): Component {
        currentRow = t.convertRowIndexToModel(row)
        btn.background = t.selectionBackground
        return btn
    }
    override fun getCellEditorValue(): Any = ""
}

// ── Coverage Bar ───────────────────────────────────────────────────────────

private class CoverageBar : JComponent() {
    var value: Double = 0.0
        set(v) { field = v.coerceIn(0.0, 1.0); repaint() }

    init {
        preferredSize = Dimension(JBUI.scale(100), JBUI.scale(14))
        minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width; val h = height
        val filled = (w * value).toInt()
        val arc = JBUI.scale(6)

        // Background track
        g2.color = JBColor(Color(220, 220, 220), Color(70, 70, 70))
        g2.fillRoundRect(0, (h - JBUI.scale(8)) / 2, w, JBUI.scale(8), arc, arc)

        // Fill
        if (filled > 0) {
            g2.color = coverColor(value)
            g2.fillRoundRect(0, (h - JBUI.scale(8)) / 2, filled.coerceAtMost(w), JBUI.scale(8), arc, arc)
        }

        // Percentage text
        val pct = "${(value * 100).toInt()}%"
        g2.font = font.deriveFont(Font.BOLD, 10f)
        g2.color = foreground
        val fm = g2.fontMetrics
        val tx = (w - fm.stringWidth(pct)) / 2
        val ty = (h + fm.ascent - fm.descent) / 2
        g2.drawString(pct, tx, ty)

        g2.dispose()
    }

    private fun coverColor(v: Double) = when {
        v >= 0.8 -> JBColor(Color(80, 180, 80),  Color(70, 160, 70))
        v >= 0.5 -> JBColor(Color(210, 170, 40),  Color(190, 150, 30))
        else     -> JBColor(Color(200, 70, 70),   Color(180, 60, 60))
    }
}
