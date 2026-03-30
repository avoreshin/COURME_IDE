package changelogai.feature.changelog

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import changelogai.core.feature.Feature
import changelogai.feature.changelog.ui.ChangelogTab
import javax.swing.JPanel

class ChangelogFeature : Feature {
    override val id = "changelog"
    override val name = "Changelog Generator"
    override val description = "Генерация CHANGELOG.md из git-коммитов через LLM"
    override val icon = AllIcons.Vcs.History

    override fun isAvailable(project: Project): Boolean =
        GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    override fun createTab(project: Project): JPanel = ChangelogTab(project).panel
}
