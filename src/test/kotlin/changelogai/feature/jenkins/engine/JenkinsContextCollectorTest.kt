package changelogai.feature.jenkins.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class JenkinsContextCollectorTest {

    @Test
    fun `parseGitDiffOutput extracts changed file paths`() {
        val gitOutput = "M\tsrc/main/kotlin/OrderService.kt\nA\tsrc/main/kotlin/NewClass.kt\nD\tsrc/test/kotlin/OldTest.kt"
        val files = JenkinsContextCollector.parseGitDiffOutput(gitOutput)
        assertEquals(
            listOf(
                "src/main/kotlin/OrderService.kt",
                "src/main/kotlin/NewClass.kt",
                "src/test/kotlin/OldTest.kt"
            ),
            files
        )
    }

    @Test
    fun `parseGitDiffOutput returns empty list for blank input`() {
        val files = JenkinsContextCollector.parseGitDiffOutput("")
        assertTrue(files.isEmpty())
    }

    @Test
    fun `findJenkinsfile returns content when file exists`(@TempDir tempDir: Path) {
        val jf = File(tempDir.toFile(), "Jenkinsfile")
        jf.writeText("pipeline { agent any }")
        val result = JenkinsContextCollector.findJenkinsfile(tempDir.toString())
        assertEquals("pipeline { agent any }", result)
    }

    @Test
    fun `findJenkinsfile returns null when no Jenkinsfile`(@TempDir tempDir: Path) {
        val result = JenkinsContextCollector.findJenkinsfile(tempDir.toString())
        assertNull(result)
    }

    @Test
    fun `truncateLog keeps last N chars`() {
        val log = "a".repeat(6000)
        val result = JenkinsContextCollector.truncateLog(log, 4000)
        assertEquals(4000, result.length)
        assertEquals(log.takeLast(4000), result)
    }

    @Test
    fun `truncateLog returns full log if shorter than limit`() {
        val log = "short log"
        assertEquals(log, JenkinsContextCollector.truncateLog(log, 4000))
    }
}
