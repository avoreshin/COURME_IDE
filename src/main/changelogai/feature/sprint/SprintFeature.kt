package changelogai.feature.sprint

import changelogai.core.feature.Feature
import changelogai.feature.sprint.ui.SprintPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.JPanel

class SprintFeature : Feature {
    override val id = "sprint"
    override val name = "Sprint Analyzer"
    override val description = "War Room Dashboard: AI-анализ Jira-спринта через MCP — риски, блокеры, декомпозиция"
    override val icon = AllIcons.Vcs.Branch

    override fun isAvailable(project: Project): Boolean = project.basePath != null

    override fun createTab(project: Project): JPanel = SprintPanel(project).panel
}
