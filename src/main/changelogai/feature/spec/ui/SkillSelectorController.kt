package changelogai.feature.spec.ui

import changelogai.core.skill.SkillDefinition
import changelogai.core.skill.SkillState
import changelogai.feature.gigacodeae.skill.SkillsDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox

internal class SkillSelectorController(private val project: Project) {

    val skillCombo = JComboBox<SkillDefinition>().apply {
        maximumSize = Dimension(JBUI.scale(160), JBUI.scale(24))
        preferredSize = Dimension(JBUI.scale(160), JBUI.scale(24))
        toolTipText = "Специализация: добавляет доменный контекст из скилла в промпт генерации ТЗ"
    }

    val editSkillBtn = JButton(AllIcons.Actions.Edit).apply {
        toolTipText = "Редактировать скиллы"
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
    }

    val selectedSkillContext: String
        get() = (skillCombo.selectedItem as? SkillDefinition)?.systemPrompt?.takeIf { it.isNotBlank() } ?: ""

    val selectedSkill: SkillDefinition?
        get() = skillCombo.selectedItem as? SkillDefinition

    init {
        editSkillBtn.addActionListener {
            SkillsDialog(project) { refresh() }.show()
        }
        refresh()
    }

    fun refresh() {
        val skills = SkillState.getInstance().allSkills()
        skillCombo.removeAllItems()
        skills.forEach { skillCombo.addItem(it) }
        skillCombo.selectedIndex = 0
    }

    fun selectValidationSkill() {
        val validationSkill = SkillState.getInstance().allSkills()
            .firstOrNull { it.id == "VALIDATION_TZ" }
        if (validationSkill != null) skillCombo.selectedItem = validationSkill
    }
}
