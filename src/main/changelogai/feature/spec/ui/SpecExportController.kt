package changelogai.feature.spec.ui

import changelogai.feature.spec.SpecCodeBridge
import changelogai.feature.spec.engine.SpecFormatter
import changelogai.feature.spec.engine.SpecOrchestrator
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.datatransfer.StringSelection
import javax.swing.JButton

internal class SpecExportController(
    private val project: Project,
    private val engine: SpecOrchestrator,
    private val formatter: SpecFormatter
) {
    val copyBtn = JButton("Копировать MD", AllIcons.Actions.Copy).apply { isEnabled = false }
    val saveBtn = JButton("Сохранить", AllIcons.Actions.Menu_saveall).apply { isEnabled = false }
    val sendCodeBtn = JButton("→ Код", AllIcons.Actions.Execute).apply {
        isEnabled = false; toolTipText = "Отправить ТЗ в GigaCodeAE для генерации кода"
    }

    fun wireActions() {
        copyBtn.addActionListener {
            val md = engine.prdDocument ?: engine.spec?.let { formatter.toMarkdown(it) } ?: return@addActionListener
            CopyPasteManager.getInstance().setContents(StringSelection(md))
        }
        saveBtn.addActionListener { saveToFile() }
        sendCodeBtn.addActionListener {
            val prd = engine.prdDocument ?: engine.spec?.let { formatter.toMarkdown(it) } ?: return@addActionListener
            SpecCodeBridge.sendToChat(prd)
        }
    }

    private fun saveToFile() {
        val desc = FileSaverDescriptor("Сохранить документ", "", "md", "json", "html")
        val dlg  = FileChooserFactory.getInstance().createSaveFileDialog(desc, project)
        val name = engine.spec?.title?.take(40) ?: "requirements"
        val w    = dlg.save("$name.md") ?: return
        val f    = w.file
        val text = when (f.extension) {
            "json" -> engine.spec?.let { formatter.toJson(it) } ?: "{}"
            "html" -> engine.prdDocument?.let { mdToHtml(it) }
                      ?: engine.spec?.let { formatter.toHtml(it) } ?: ""
            else   -> engine.prdDocument ?: engine.spec?.let { formatter.toMarkdown(it) } ?: ""
        }
        f.writeText(text, Charsets.UTF_8)
    }

    private fun mdToHtml(md: String): String {
        val b = md.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace(Regex("^# (.+)$",  RegexOption.MULTILINE), "<h1>$1</h1>")
            .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>").replace("\n","<br>")
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<style>body{font-family:sans-serif;max-width:900px;margin:40px auto;line-height:1.6}
h1{border-bottom:2px solid #333}table{border-collapse:collapse;width:100%}
th,td{border:1px solid #ccc;padding:8px}</style></head><body>$b</body></html>"""
    }
}
