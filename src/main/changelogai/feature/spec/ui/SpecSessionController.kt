package changelogai.feature.spec.ui

import changelogai.feature.spec.SpecSessionRepository
import changelogai.feature.spec.engine.SpecOrchestrator
import changelogai.feature.spec.model.SpecDocument
import changelogai.feature.spec.model.SpecSession
import changelogai.feature.spec.model.SpecState
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox

internal class SpecSessionController(
    private val repository: SpecSessionRepository,
    private val engine: SpecOrchestrator,
    private val getTaskText: () -> String
) {
    val sessionCombo = JComboBox<SpecSession>().apply {
        renderer = SpecSessionComboRenderer()
        maximumSize = Dimension(JBUI.scale(220), JBUI.scale(26))
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(26))
        toolTipText = "История спецификаций"
    }
    val newBtn = JButton(AllIcons.General.Add).apply {
        toolTipText = "Новая спецификация"
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
    }
    val deleteBtn = JButton(AllIcons.General.Remove).apply {
        toolTipText = "Удалить спецификацию"
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
    }

    var currentSession: SpecSession? = null
    var ignoreComboEvents = false

    /** Called when a session is selected from combo — SpecPanel restores UI state */
    var onSessionRestored: ((SpecSession) -> Unit)? = null

    fun wireActions() {
        newBtn.addActionListener { onNewSession() }
        deleteBtn.addActionListener { onDeleteSelected() }
        sessionCombo.addActionListener {
            if (ignoreComboEvents) return@addActionListener
            val selected = sessionCombo.selectedItem as? SpecSession ?: return@addActionListener
            if (selected.id != currentSession?.id) onSessionRestored?.invoke(selected)
        }
    }

    fun refresh() {
        ignoreComboEvents = true
        sessionCombo.removeAllItems()
        repository.sessions.forEach { sessionCombo.addItem(it) }
        currentSession?.let { cur ->
            val idx = repository.sessions.indexOfFirst { it.id == cur.id }
            if (idx >= 0) sessionCombo.selectedIndex = idx
        }
        ignoreComboEvents = false
    }

    fun onNewSession(onReset: (() -> Unit)? = null) {
        onReset?.invoke()
        currentSession = null
        ignoreComboEvents = true
        sessionCombo.selectedIndex = -1
        ignoreComboEvents = false
    }

    fun onDeleteSelected(onReset: (() -> Unit)? = null) {
        val selected = sessionCombo.selectedItem as? SpecSession ?: return
        repository.delete(selected.id)
        if (currentSession?.id == selected.id) {
            currentSession = null
            onReset?.invoke()
        }
        refresh()
    }

    fun saveCurrentSession(spec: SpecDocument?, prd: String?, mermaid: String?) {
        val task = getTaskText()
        if (currentSession == null) {
            val title = task.lines().firstOrNull { it.isNotBlank() }?.take(50) ?: "Спецификация"
            val session = SpecSession(
                title = title,
                taskDescription = task,
                spec = spec,
                prdDocument = prd,
                mermaidDiagrams = mermaid,
                state = engine.state
            )
            currentSession = session
            repository.add(session)
            refresh()
        } else {
            currentSession!!.apply {
                this.spec = spec ?: this.spec
                this.prdDocument = prd ?: this.prdDocument
                this.mermaidDiagrams = mermaid ?: this.mermaidDiagrams
                this.state = engine.state
            }
            repository.update(currentSession!!)
        }
    }

    fun restoreSession(session: SpecSession) {
        currentSession = session
    }

    fun clearCurrent() {
        currentSession = null
        ignoreComboEvents = true
        sessionCombo.selectedIndex = -1
        ignoreComboEvents = false
    }
}
