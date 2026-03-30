package changelogai.feature.gigacodeae

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import changelogai.core.feature.Feature
import changelogai.feature.gigacodeae.ui.GigaCodeAETab
import javax.swing.JPanel

class GigaCodeAEFeature : Feature {
    override val id = "gigacodeae"
    override val name = "GigaCodeAE"
    override val description = "AI-ассистент с вызовом инструментов и работой с файлами проекта"
    override val icon = AllIcons.General.Balloon

    override fun isAvailable(project: Project): Boolean = true

    override fun createTab(project: Project): JPanel = GigaCodeAETab(project).panel
}
