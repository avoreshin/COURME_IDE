package changelogai.feature.coverage

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import changelogai.core.feature.Feature
import javax.swing.JPanel

class CoverageFeature : Feature {
    override val id = "coverage"
    override val name = "Test Coverage"
    override val description = "Анализ покрытия тестами: % по классам/методам, список непокрытых"
    override val icon = AllIcons.RunConfigurations.TestPassed

    override fun isAvailable(project: Project): Boolean = project.basePath != null

    override fun createTab(project: Project): JPanel = CoveragePanel(project).panel
}
