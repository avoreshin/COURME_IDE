package changelogai.feature.spec.ui

import changelogai.feature.spec.model.SpecState
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.Color
import javax.swing.JButton
import javax.swing.JLabel

internal class SpecStateController(
    private val statusDot: JLabel,
    private val statusLabel: JBLabel,
    private val analyzeBtn: JButton,
    private val cancelBtn: JButton,
    private val copyBtn: JButton,
    private val saveBtn: JButton,
    private val sendCodeBtn: JButton
) {
    fun applyState(s: SpecState) {
        statusDot.icon = when (s) {
            SpecState.ANALYZING, SpecState.GENERATING -> AllIcons.Process.Step_1
            SpecState.COMPLETE   -> AllIcons.General.InspectionsOK
            SpecState.CLARIFYING -> AllIcons.General.Information
            SpecState.ERROR      -> AllIcons.General.Error
            else                 -> null
        }
        analyzeBtn.isEnabled = s in setOf(SpecState.IDLE, SpecState.COMPLETE, SpecState.ERROR)
        cancelBtn .isEnabled = s in setOf(SpecState.ANALYZING, SpecState.GENERATING, SpecState.CLARIFYING)

        val (msg, clr) = when (s) {
            SpecState.IDLE       -> "Готов к анализу"      to JBColor.GRAY
            SpecState.ANALYZING  -> "Анализирую..."         to JBColor(Color(37,99,235),  Color(99,155,255))
            SpecState.CLARIFYING -> "Уточнение вопросов"    to JBColor(Color(100,150,255),Color(100,150,255))
            SpecState.GENERATING -> "Генерирую..."          to JBColor(Color(37,99,235),  Color(99,155,255))
            SpecState.COMPLETE   -> "Готово"                 to JBColor(Color(34,139,68),  Color(72,199,116))
            SpecState.ERROR      -> "Ошибка"                to JBColor.RED
        }
        statusLabel.text = msg; statusLabel.foreground = clr
    }

    fun setStatus(s: SpecState, msg: String) {
        applyState(s)
        statusLabel.text = msg
    }

    fun setExport(on: Boolean) {
        copyBtn.isEnabled = on; saveBtn.isEnabled = on; sendCodeBtn.isEnabled = on
    }
}
