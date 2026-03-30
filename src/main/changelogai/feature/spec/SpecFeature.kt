package changelogai.feature.spec

import changelogai.core.feature.Feature
import changelogai.feature.spec.ui.SpecPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.JPanel

class SpecFeature : Feature {
    override val id = "spec"
    override val name = "Spec Generator"
    override val description = "AI-генерация технической спецификации (FR/NFR/AC/Edge Cases) из описания задачи"
    override val icon = AllIcons.FileTypes.Text

    override fun isAvailable(project: Project): Boolean = true

    override fun createTab(project: Project): JPanel = SpecPanel(project).panel
}
