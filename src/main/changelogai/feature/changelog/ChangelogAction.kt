package changelogai.feature.changelog

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import git4idea.repo.GitRepositoryManager
import changelogai.platform.PluginConstants

class ChangelogAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(PluginConstants.TOOL_WINDOW_ID)?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasGitRepo = project != null &&
                GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
        e.presentation.isEnabledAndVisible = hasGitRepo
    }
}
