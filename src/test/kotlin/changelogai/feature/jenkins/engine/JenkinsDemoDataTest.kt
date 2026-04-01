package changelogai.feature.jenkins.engine

import changelogai.feature.jenkins.model.BuildStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JenkinsDemoDataTest {

    @Test
    fun `pipelines returns at least 2 entries`() {
        assertTrue(JenkinsDemoData.pipelines.size >= 2)
    }

    @Test
    fun `at least one pipeline has FAILURE status`() {
        val statuses = JenkinsDemoData.pipelines.map { it.status }
        assertTrue(statuses.any { it == BuildStatus.FAILURE }, "Expected at least one FAILURE pipeline")
    }

    @Test
    fun `failed pipeline has non-empty log`() {
        val failed = JenkinsDemoData.pipelines.first { it.status == BuildStatus.FAILURE }
        assertTrue((failed.lastBuild?.log ?: "").isNotBlank())
    }

    @Test
    fun `demoAnalysis has non-empty rootCause and suggestions`() {
        assertTrue(JenkinsDemoData.demoAnalysis.rootCause.isNotBlank())
        assertTrue(JenkinsDemoData.demoAnalysis.suggestions.isNotEmpty())
    }
}
