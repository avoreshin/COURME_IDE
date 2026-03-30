package changelogai.feature.changelog

import com.intellij.openapi.project.Project
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager

private const val DEFAULT_LIMIT = 50

class CommitRepository {
    fun getRecent(project: Project, limit: Int = DEFAULT_LIMIT): List<GitCommit> {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return emptyList()
        return GitHistoryUtils.history(project, repos[0].root, "--max-count=$limit")
    }
}
