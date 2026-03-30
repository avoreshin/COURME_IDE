package changelogai.feature.changelog

import com.intellij.openapi.project.Project
import git4idea.GitCommit
import changelogai.core.llm.cancellation.Cancelable

class ChangelogService(
    private val commitRepository: CommitRepository = CommitRepository(),
    private val writer: ChangelogWriter = ChangelogWriter(),
    private val generator: ChangelogGenerator = ChangelogGenerator()
) {
    fun getRecentCommits(project: Project): List<GitCommit> =
        commitRepository.getRecent(project)

    sealed class GenerationResult {
        data class Content(val text: String) : GenerationResult()
        data class Info(val message: String) : GenerationResult()
    }

    fun generateChangelog(project: Project, commits: List<GitCommit>, cancelable: Cancelable?): GenerationResult {
        val processed = ChangelogValidator.processedHashes(project)
        val notProcessed = commits.filter { it.id.toShortString() !in processed }
        if (notProcessed.isEmpty()) return GenerationResult.Info("Все выбранные коммиты уже присутствуют в CHANGELOG.md")
        val newCommits = ChangelogValidator.filterRelevant(notProcessed)
        if (newCommits.isEmpty()) return GenerationResult.Info("Нет значимых изменений для changelog (только refactor/chore/style/test/ci/build)")
        val messages = newCommits.map { "${it.id.toShortString()} ${it.subject}" }
        val existingChangelog = ChangelogValidator.existingContent(project)
        val result = generator.generate(project, messages, existingChangelog, cancelable)
        return if (result.sections.isNotBlank()) GenerationResult.Content(result.sections)
        else GenerationResult.Info(result.rawText.ifBlank { "Нет значимых изменений для changelog" })
    }

    fun writeToFile(project: Project, changelog: String) =
        writer.prepend(project, changelog)
}
