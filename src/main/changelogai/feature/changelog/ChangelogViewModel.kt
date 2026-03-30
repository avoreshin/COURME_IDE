package changelogai.feature.changelog

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import git4idea.GitCommit
import changelogai.core.llm.cancellation.ProgressIndicatorWrapper
import javax.swing.SwingUtilities

class ChangelogViewModel(private val project: Project) {

    private val service = ChangelogService()

    // @Volatile — callbacks устанавливаются из EDT, читаются из BGT перед invokeLater
    @Volatile var onCommitsLoaded: ((List<GitCommit>) -> Unit)? = null
    @Volatile var onCommitsError: ((String) -> Unit)? = null
    @Volatile var onGenerating: (() -> Unit)? = null
    @Volatile var onGenerationResult: ((String) -> Unit)? = null
    @Volatile var onGenerationInfo: ((String) -> Unit)? = null
    @Volatile var onGenerationError: ((String) -> Unit)? = null
    @Volatile var onSaved: (() -> Unit)? = null

    fun loadCommits() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading commits…", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val commits = service.getRecentCommits(project)
                    SwingUtilities.invokeLater { onCommitsLoaded?.invoke(commits) }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater { onCommitsError?.invoke(e.message ?: "Unknown error") }
                }
            }
        })
    }

    fun generateChangelog(commits: List<GitCommit>) {
        SwingUtilities.invokeLater { onGenerating?.invoke() }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating changelog…", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    when (val result = service.generateChangelog(project, commits, ProgressIndicatorWrapper(indicator))) {
                        is ChangelogService.GenerationResult.Content ->
                            SwingUtilities.invokeLater { onGenerationResult?.invoke(result.text) }
                        is ChangelogService.GenerationResult.Info ->
                            SwingUtilities.invokeLater { onGenerationInfo?.invoke(result.message) }
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater { onGenerationError?.invoke(e.message ?: "Unknown error") }
                }
            }
        })
    }

    fun saveToFile(changelog: String) {
        service.writeToFile(project, changelog)
        SwingUtilities.invokeLater { onSaved?.invoke() }
    }
}
