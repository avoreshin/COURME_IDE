package changelogai.feature.jenkins.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JenkinsAnalyzerTest {

    @Test
    fun `parseAnalysisResponse extracts rootCause and suggestions`() {
        val markdown = """
            ## Root Cause
            Ошибка компиляции в OrderService.kt — отсутствует UserRepository.

            ## Related Files
            - src/main/kotlin/OrderService.kt
            - src/main/kotlin/UserRepository.kt

            ## Suggestions
            - Проверить импорты в OrderService.kt
            - Убедиться что UserRepository не был переименован
        """.trimIndent()

        val result = JenkinsAnalyzer.parseAnalysisResponse(markdown)

        assertTrue(result.rootCause.contains("OrderService.kt"))
        assertEquals(2, result.relatedFiles.size)
        assertEquals(2, result.suggestions.size)
    }

    @Test
    fun `parseAnalysisResponse handles missing sections gracefully`() {
        val markdown = "Что-то пошло не так при сборке."
        val result = JenkinsAnalyzer.parseAnalysisResponse(markdown)
        assertTrue(result.rootCause.isNotBlank())
        assertTrue(result.relatedFiles.isEmpty())
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun `buildPrompt includes log and changed files`() {
        val log = "BUILD FAILED: NPE in Foo.kt"
        val context = JenkinsContext(
            changedFiles = listOf("src/main/kotlin/Foo.kt"),
            jenkinsfileContent = null
        )
        val prompt = JenkinsAnalyzer.buildPrompt(log, context)
        assertTrue(prompt.contains("BUILD FAILED"))
        assertTrue(prompt.contains("Foo.kt"))
    }
}
