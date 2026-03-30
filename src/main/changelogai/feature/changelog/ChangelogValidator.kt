package changelogai.feature.changelog

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitCommit
import java.io.File

object ChangelogValidator {

    private val HASH_PATTERN = Regex("""\(([0-9a-f]{6,12})\)""")

    /** Префиксы Conventional Commits, не влияющие на пользователя */
    private val SKIP_PREFIXES = setOf("refactor", "chore", "style", "test", "ci", "build", "docs", "perf")

    /** Фильтрует коммиты — оставляет только релевантные для changelog */
    fun filterRelevant(commits: List<GitCommit>): List<GitCommit> = commits.filter { isRelevant(it) }

    private fun isRelevant(commit: GitCommit): Boolean {
        val subject = commit.subject.trim().lowercase()
        if (subject.startsWith("merge ")) return false
        // conventional commit: "type: ..." или "type(scope): ..."
        val type = subject.substringBefore(":").substringBefore("(").trim()
        return type !in SKIP_PREFIXES
    }

    /** Хэши коммитов, уже присутствующих в CHANGELOG.md */
    fun processedHashes(project: Project): Set<String> {
        val file = changelogFile(project)
        if (!file.exists()) return emptySet()
        return HASH_PATTERN.findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /** Текущее содержимое CHANGELOG.md (для передачи в LLM как контекст) */
    fun existingContent(project: Project): String {
        val file = changelogFile(project)
        return if (file.exists()) file.readText().trim() else ""
    }

    private fun changelogFile(project: Project): File {
        val file = File(project.basePath ?: "", "CHANGELOG.md")
        // Сбрасываем VFS-кэш — иначе ручные правки файла могут не подхватиться
        LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
            ?.refresh(false, false)
        return file
    }
}
