package changelogai.feature.jenkins.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JenkinsModelsTest {

    @Test
    fun `BuildStatus fromString maps known values`() {
        assertEquals(BuildStatus.SUCCESS, BuildStatus.fromString("SUCCESS"))
        assertEquals(BuildStatus.FAILURE, BuildStatus.fromString("FAILED"))
        assertEquals(BuildStatus.FAILURE, BuildStatus.fromString("FAILURE"))
        assertEquals(BuildStatus.IN_PROGRESS, BuildStatus.fromString("IN_PROGRESS"))
        assertEquals(BuildStatus.IN_PROGRESS, BuildStatus.fromString("INPROGRESS"))
        assertEquals(BuildStatus.IN_PROGRESS, BuildStatus.fromString("BUILDING"))
        assertEquals(BuildStatus.ABORTED, BuildStatus.fromString("ABORTED"))
        assertEquals(BuildStatus.UNSTABLE, BuildStatus.fromString("UNSTABLE"))
        assertEquals(BuildStatus.UNKNOWN, BuildStatus.fromString("whatever"))
    }

    @Test
    fun `JenkinsPipeline default lastBuild is null`() {
        val p = JenkinsPipeline(name = "my-service", url = "http://jenkins/job/my-service")
        assertNull(p.lastBuild)
        assertEquals(BuildStatus.UNKNOWN, p.status)
    }

    @Test
    fun `JenkinsAnalysis relatedFiles defaults to empty`() {
        val a = JenkinsAnalysis(
            rootCause = "NPE in Foo.kt",
            suggestions = listOf("Add null check")
        )
        assertTrue(a.relatedFiles.isEmpty())
    }
}
