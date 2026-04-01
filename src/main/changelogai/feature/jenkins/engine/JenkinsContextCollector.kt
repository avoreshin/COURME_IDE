package changelogai.feature.jenkins.engine

import com.intellij.openapi.project.Project
import java.io.File

class JenkinsContextCollector(private val project: Project) {

    /** Собирает контекст для LLM-анализа. Запускать с фонового потока. */
    fun collect(): JenkinsContext {
        val basePath = project.basePath ?: return JenkinsContext()
        val changedFiles = runGitDiff(basePath)
        val jenkinsfile = findJenkinsfile(basePath)
        return JenkinsContext(
            changedFiles = changedFiles,
            jenkinsfileContent = jenkinsfile
        )
    }

    private fun runGitDiff(basePath: String): List<String> {
        return try {
            val process = ProcessBuilder("git", "diff", "--name-status", "HEAD~1")
                .directory(File(basePath))
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            parseGitDiffOutput(output)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun parseGitDiffOutput(output: String): List<String> {
            if (output.isBlank()) return emptyList()
            return output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) parts[1].trim() else null
                }
        }

        fun findJenkinsfile(basePath: String): String? {
            val candidates = listOf("Jenkinsfile", "jenkinsfile", "Jenkinsfile.groovy")
            return candidates.firstNotNullOfOrNull { name ->
                val f = File(basePath, name)
                if (f.exists()) f.readText() else null
            }
        }

        fun truncateLog(log: String, maxChars: Int): String =
            if (log.length <= maxChars) log else log.takeLast(maxChars)
    }
}

data class JenkinsContext(
    val changedFiles: List<String> = emptyList(),
    val jenkinsfileContent: String? = null
)
