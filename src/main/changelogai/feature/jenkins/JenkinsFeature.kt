package changelogai.feature.jenkins

import changelogai.core.feature.Feature
import changelogai.feature.jenkins.ui.JenkinsPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.JPanel

class JenkinsFeature : Feature {
    override val id = "jenkins"
    override val name = "Jenkins"
    override val description = "Jenkins Dashboard: мониторинг пайплайнов и AI-анализ упавших сборок"
    override val icon = AllIcons.Nodes.Plugin

    override fun isAvailable(project: Project): Boolean = project.basePath != null

    override fun createTab(project: Project): JPanel = JenkinsPanel(project).panel
}
