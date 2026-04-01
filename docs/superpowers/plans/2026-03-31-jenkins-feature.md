# Jenkins Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить вкладку Jenkins Dashboard в плагин AI-OAssist с просмотром пайплайнов, логов, запуском сборок и inline AI-анализом упавших билдов через MCP.

**Architecture:** Новый пакет `feature/jenkins/` по образцу `feature/sprint/`. Данные получаются через `JenkinsMcpFetcher` (MCP-клиент с пресетом `jenkins`). AI-анализ делает `JenkinsAnalyzer` через существующий `LLMClientFactory`. Интеграция с GigaCodeAE — через `JenkinsChatBridge` (объект-слушатель, аналог `SpecCodeBridge`).

**Tech Stack:** Kotlin, IntelliJ Platform SDK (Swing UI), MCP через `McpService`/`McpState` (уже в плагине), LLM через `LLMClientFactory` (уже в плагине), JUnit 5 + Mockito для тестов.

---

## Файловая карта

| Файл | Действие | Назначение |
|---|---|---|
| `feature/jenkins/model/JenkinsPipeline.kt` | Создать | data class пайплайна |
| `feature/jenkins/model/JenkinsBuild.kt` | Создать | data class сборки + enum статуса |
| `feature/jenkins/model/JenkinsAnalysis.kt` | Создать | data class результата AI-анализа |
| `feature/jenkins/engine/JenkinsDemoData.kt` | Создать | Захардкоженные demo-данные |
| `feature/jenkins/engine/JenkinsMcpFetcher.kt` | Создать | MCP-клиент для Jenkins |
| `feature/jenkins/engine/JenkinsContextCollector.kt` | Создать | Сбор git-контекста для LLM |
| `feature/jenkins/engine/JenkinsAnalyzer.kt` | Создать | LLM-анализ лога сборки |
| `feature/jenkins/JenkinsChatBridge.kt` | Создать | Мост для передачи в GigaCodeAE |
| `feature/jenkins/ui/JenkinsPipelineList.kt` | Создать | Список пайплайнов (Swing) |
| `feature/jenkins/ui/JenkinsBuildLog.kt` | Создать | Лог сборки + кнопки (Swing) |
| `feature/jenkins/ui/JenkinsAnalysisPanel.kt` | Создать | Inline AI-результат (Swing) |
| `feature/jenkins/ui/JenkinsPanel.kt` | Создать | Корневая панель < 300 строк |
| `feature/jenkins/JenkinsFeature.kt` | Создать | Feature entry point |
| `feature/gigacodeae/ui/GigaCodeAETab.kt` | Изменить | Подписаться на JenkinsChatBridge |
| `META-INF/plugin.xml` | Изменить | Зарегистрировать JenkinsFeature |
| `test/.../jenkins/model/JenkinsModelsTest.kt` | Создать | Тесты data-классов |
| `test/.../jenkins/engine/JenkinsDemoDataTest.kt` | Создать | Тест demo-данных |
| `test/.../jenkins/engine/JenkinsContextCollectorTest.kt` | Создать | Тест парсинга git-вывода |
| `test/.../jenkins/engine/JenkinsAnalyzerTest.kt` | Создать | Тест LLM-анализа с Mockito |
| `test/.../jenkins/JenkinsChatBridgeTest.kt` | Создать | Тест bridge-паттерна |

---

## Task 1: Data Models

**Files:**
- Create: `src/main/changelogai/feature/jenkins/model/JenkinsPipeline.kt`
- Create: `src/main/changelogai/feature/jenkins/model/JenkinsBuild.kt`
- Create: `src/main/changelogai/feature/jenkins/model/JenkinsAnalysis.kt`
- Test: `src/test/kotlin/changelogai/feature/jenkins/model/JenkinsModelsTest.kt`

- [ ] **Step 1: Написать тест моделей**

```kotlin
// src/test/kotlin/changelogai/feature/jenkins/model/JenkinsModelsTest.kt
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
```

- [ ] **Step 2: Запустить тест — убедиться что падает (класс не существует)**

```
./gradlew test --tests "changelogai.feature.jenkins.model.JenkinsModelsTest" --rerun-tasks
```

Ожидается: FAILED — `error: unresolved reference: changelogai.feature.jenkins.model`

- [ ] **Step 3: Создать модели**

```kotlin
// src/main/changelogai/feature/jenkins/model/JenkinsPipeline.kt
package changelogai.feature.jenkins.model

data class JenkinsPipeline(
    val name: String,
    val url: String,
    val status: BuildStatus = BuildStatus.UNKNOWN,
    val lastBuild: JenkinsBuild? = null
)
```

```kotlin
// src/main/changelogai/feature/jenkins/model/JenkinsBuild.kt
package changelogai.feature.jenkins.model

data class JenkinsBuild(
    val number: Int,
    val status: BuildStatus,
    val timestamp: Long = 0L,
    val durationMs: Long = 0L,
    val log: String = ""
)

enum class BuildStatus {
    SUCCESS, FAILURE, IN_PROGRESS, ABORTED, UNKNOWN;

    companion object {
        fun fromString(value: String): BuildStatus = when (value.uppercase()) {
            "SUCCESS" -> SUCCESS
            "FAILURE", "FAILED" -> FAILURE
            "IN_PROGRESS", "INPROGRESS", "BUILDING" -> IN_PROGRESS
            "ABORTED" -> ABORTED
            else -> UNKNOWN
        }
    }
}
```

```kotlin
// src/main/changelogai/feature/jenkins/model/JenkinsAnalysis.kt
package changelogai.feature.jenkins.model

data class JenkinsAnalysis(
    val rootCause: String,
    val relatedFiles: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)
```

- [ ] **Step 4: Запустить тест — убедиться что проходит**

```
./gradlew test --tests "changelogai.feature.jenkins.model.JenkinsModelsTest" --rerun-tasks
```

Ожидается: PASSED (3 теста)

- [ ] **Step 5: Commit**

```bash
git add src/main/changelogai/feature/jenkins/model/ \
        src/test/kotlin/changelogai/feature/jenkins/model/
git commit -m "feat(jenkins) data models: JenkinsPipeline, JenkinsBuild, JenkinsAnalysis"
```

---

## Task 2: Demo Data + MCP Fetcher

**Files:**
- Create: `src/main/changelogai/feature/jenkins/engine/JenkinsDemoData.kt`
- Create: `src/main/changelogai/feature/jenkins/engine/JenkinsMcpFetcher.kt`
- Test: `src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsDemoDataTest.kt`

- [ ] **Step 1: Написать тест demo-данных**

```kotlin
// src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsDemoDataTest.kt
package changelogai.feature.jenkins.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JenkinsDemoDataTest {

    @Test
    fun `pipelines returns at least 2 entries`() {
        assertTrue(JenkinsDemoData.pipelines.size >= 2)
    }

    @Test
    fun `at least one pipeline has FAILURE status`() {
        val statuses = JenkinsDemoData.pipelines.map { it.status.name }
        assertTrue(statuses.any { it == "FAILURE" }, "Expected at least one FAILURE pipeline")
    }

    @Test
    fun `failed pipeline has non-empty log`() {
        val failed = JenkinsDemoData.pipelines.first { it.status.name == "FAILURE" }
        assertTrue((failed.lastBuild?.log ?: "").isNotBlank())
    }

    @Test
    fun `demoAnalysis has non-empty rootCause and suggestions`() {
        assertTrue(JenkinsDemoData.demoAnalysis.rootCause.isNotBlank())
        assertTrue(JenkinsDemoData.demoAnalysis.suggestions.isNotEmpty())
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться что падает**

```
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsDemoDataTest" --rerun-tasks
```

Ожидается: FAILED — `unresolved reference: JenkinsDemoData`

- [ ] **Step 3: Создать JenkinsDemoData**

```kotlin
// src/main/changelogai/feature/jenkins/engine/JenkinsDemoData.kt
package changelogai.feature.jenkins.engine

import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsAnalysis
import changelogai.feature.jenkins.model.JenkinsBuild
import changelogai.feature.jenkins.model.JenkinsPipeline

object JenkinsDemoData {

    private val demoLog = """
        [Pipeline] Start of Pipeline
        [Pipeline] node
        Running on Jenkins in /var/jenkins_home/workspace/my-service
        [Pipeline] stage (Build)
        [Pipeline] sh
        + ./gradlew build
        > Task :compileKotlin FAILED
        
        FAILURE: Build failed with an exception.
        
        * What went wrong:
        Execution failed for task ':compileKotlin'.
        > Compilation error. See log for more details
        
        e: src/main/kotlin/com/example/OrderService.kt: (42, 15): 
          Unresolved reference: UserRepository
        
        e: src/main/kotlin/com/example/PaymentController.kt: (87, 9): 
          None of the following functions can be called with the arguments supplied:
          public final fun process(order: Order): Result<Unit>
        
        * Try:
        > Run with --stacktrace option to get a stack trace.
        
        BUILD FAILED in 23s
        3 actionable tasks: 3 executed
    """.trimIndent()

    val demoAnalysis = JenkinsAnalysis(
        rootCause = "Ошибка компиляции: `UserRepository` не найден в `OrderService.kt`. " +
                "Вероятно, был удалён или переименован класс/интерфейс в последнем коммите.",
        relatedFiles = listOf(
            "src/main/kotlin/com/example/OrderService.kt",
            "src/main/kotlin/com/example/PaymentController.kt"
        ),
        suggestions = listOf(
            "Проверить, не был ли переименован `UserRepository` в последнем коммите",
            "Убедиться, что зависимость модуля с `UserRepository` добавлена в build.gradle.kts",
            "Проверить сигнатуру метода `process()` в `PaymentController.kt` — ожидается `Order`, передаётся другой тип"
        )
    )

    val pipelines: List<JenkinsPipeline> = listOf(
        JenkinsPipeline(
            name = "my-service",
            url = "http://jenkins.demo/job/my-service",
            status = BuildStatus.FAILURE,
            lastBuild = JenkinsBuild(
                number = 142,
                status = BuildStatus.FAILURE,
                timestamp = System.currentTimeMillis() - 600_000,
                durationMs = 23_000,
                log = demoLog
            )
        ),
        JenkinsPipeline(
            name = "api-gateway",
            url = "http://jenkins.demo/job/api-gateway",
            status = BuildStatus.SUCCESS,
            lastBuild = JenkinsBuild(
                number = 89,
                status = BuildStatus.SUCCESS,
                timestamp = System.currentTimeMillis() - 1_800_000,
                durationMs = 45_000,
                log = "BUILD SUCCESSFUL in 45s"
            )
        ),
        JenkinsPipeline(
            name = "frontend",
            url = "http://jenkins.demo/job/frontend",
            status = BuildStatus.IN_PROGRESS,
            lastBuild = JenkinsBuild(
                number = 55,
                status = BuildStatus.IN_PROGRESS,
                timestamp = System.currentTimeMillis() - 120_000,
                durationMs = 0,
                log = "Build in progress..."
            )
        )
    )
}
```

- [ ] **Step 4: Создать JenkinsMcpFetcher**

```kotlin
// src/main/changelogai/feature/jenkins/engine/JenkinsMcpFetcher.kt
package changelogai.feature.jenkins.engine

import changelogai.core.mcp.McpState
import changelogai.feature.gigacodeae.mcp.McpClient
import changelogai.feature.gigacodeae.mcp.McpService
import changelogai.feature.gigacodeae.mcp.McpToolInfo
import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsBuild
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.Project

class JenkinsMcpFetcher(private val project: Project) {

    private val mapper = jacksonObjectMapper()

    private fun findJenkinsClient(): McpClient? {
        val entry = McpState.getInstance().servers
            .filter { it.enabled }
            .firstOrNull { it.preset == "jenkins" }
            ?: return null
        return McpService.getInstance(project).clients
            .firstOrNull { it.name == entry.name }
    }

    fun isConfigured(): Boolean = findJenkinsClient() != null

    private fun listTools(): List<McpToolInfo> =
        findJenkinsClient()?.listTools() ?: emptyList()

    private fun callTool(toolName: String, args: Map<String, Any>): String {
        val client = findJenkinsClient()
            ?: throw JenkinsMcpNotConfiguredException()
        return client.callTool(toolName, args)
    }

    /** Перебирает кандидатов и вызывает первый доступный инструмент. */
    private fun resolveAndCall(candidates: List<String>, args: Map<String, Any>): String {
        val available = listTools().map { it.name }.toSet()
        val tool = candidates.firstOrNull { candidate ->
            available.any { it.endsWith("__$candidate") || it == candidate }
        } ?: candidates.first()
        return callTool(tool, args)
    }

    /** Загружает список пайплайнов с текущим статусом. */
    fun getPipelines(): List<JenkinsPipeline> {
        val json = resolveAndCall(JenkinsTool.GET_PIPELINES, emptyMap())
        return parsePipelines(json)
    }

    /** Загружает лог сборки. */
    fun getBuildLog(pipelineName: String, buildNumber: Int): String {
        return resolveAndCall(
            JenkinsTool.GET_BUILD_LOG,
            mapOf("pipeline" to pipelineName, "job" to pipelineName, "buildNumber" to buildNumber, "build_number" to buildNumber)
        )
    }

    /** Запускает сборку. */
    fun triggerBuild(pipelineName: String) {
        resolveAndCall(
            JenkinsTool.TRIGGER_BUILD,
            mapOf("pipeline" to pipelineName, "job" to pipelineName)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePipelines(json: String): List<JenkinsPipeline> {
        return try {
            val data: Any = mapper.readValue(json, Any::class.java)
            val items: List<Map<String, Any>> = when {
                data is List<*> -> data as List<Map<String, Any>>
                data is Map<*, *> && data["jobs"] != null -> data["jobs"] as List<Map<String, Any>>
                data is Map<*, *> && data["pipelines"] != null -> data["pipelines"] as List<Map<String, Any>>
                else -> return emptyList()
            }
            items.mapNotNull { item ->
                val name = item["name"]?.toString() ?: return@mapNotNull null
                val url = item["url"]?.toString() ?: ""
                val statusRaw = item["color"]?.toString()
                    ?: item["status"]?.toString()
                    ?: item["result"]?.toString()
                    ?: "UNKNOWN"
                // Jenkins uses "color" field: blue=success, red=failure, etc.
                val status = when (statusRaw.lowercase()) {
                    "blue", "success" -> BuildStatus.SUCCESS
                    "red", "failure", "failed" -> BuildStatus.FAILURE
                    "blue_anime", "red_anime", "notbuilt_anime", "in_progress" -> BuildStatus.IN_PROGRESS
                    "aborted" -> BuildStatus.ABORTED
                    else -> BuildStatus.UNKNOWN
                }
                val lastBuildMap = item["lastBuild"] as? Map<String, Any>
                val lastBuild = lastBuildMap?.let {
                    JenkinsBuild(
                        number = it["number"]?.toString()?.toIntOrNull() ?: 0,
                        status = status,
                        timestamp = it["timestamp"]?.toString()?.toLongOrNull() ?: 0L,
                        durationMs = it["duration"]?.toString()?.toLongOrNull() ?: 0L
                    )
                }
                JenkinsPipeline(name = name, url = url, status = status, lastBuild = lastBuild)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class JenkinsMcpNotConfiguredException :
    Exception("Jenkins MCP сервер не настроен. Добавьте сервер с preset='jenkins' в MCP Settings.")

object JenkinsTool {
    val GET_PIPELINES = listOf(
        "jenkins_get_pipelines", "get_pipelines", "list_jobs", "jenkins_list_jobs", "getJobs"
    )
    val GET_BUILD_LOG = listOf(
        "jenkins_get_build_log", "get_build_log", "getBuildLog", "jenkins_build_log"
    )
    val TRIGGER_BUILD = listOf(
        "jenkins_trigger_build", "trigger_build", "triggerBuild", "jenkins_build"
    )
}
```

- [ ] **Step 5: Запустить тест demo-данных — убедиться что проходит**

```
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsDemoDataTest" --rerun-tasks
```

Ожидается: PASSED (4 теста)

- [ ] **Step 6: Commit**

```bash
git add src/main/changelogai/feature/jenkins/engine/ \
        src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsDemoDataTest.kt
git commit -m "feat(jenkins) MCP fetcher and demo data"
```

---

## Task 3: Context Collector

**Files:**
- Create: `src/main/changelogai/feature/jenkins/engine/JenkinsContextCollector.kt`
- Test: `src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsContextCollectorTest.kt`

- [ ] **Step 1: Написать тест**

```kotlin
// src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsContextCollectorTest.kt
package changelogai.feature.jenkins.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class JenkinsContextCollectorTest {

    @Test
    fun `parseGitDiffOutput extracts changed file paths`() {
        val gitOutput = """
            M\tsrc/main/kotlin/OrderService.kt
            A\tsrc/main/kotlin/NewClass.kt
            D\tsrc/test/kotlin/OldTest.kt
        """.trimIndent()
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
    fun `findJenkinsfile returns path when file exists`(@TempDir tempDir: Path) {
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
```

- [ ] **Step 2: Запустить тест — убедиться что падает**

```
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsContextCollectorTest" --rerun-tasks
```

Ожидается: FAILED — `unresolved reference: JenkinsContextCollector`

- [ ] **Step 3: Создать JenkinsContextCollector**

```kotlin
// src/main/changelogai/feature/jenkins/engine/JenkinsContextCollector.kt
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
                    // Format: "M\tpath/to/file" or "A\tpath" or "D\tpath"
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
```

- [ ] **Step 4: Запустить тест — убедиться что проходит**

```
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsContextCollectorTest" --rerun-tasks
```

Ожидается: PASSED (6 тестов)

- [ ] **Step 5: Commit**

```bash
git add src/main/changelogai/feature/jenkins/engine/JenkinsContextCollector.kt \
        src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsContextCollectorTest.kt
git commit -m "feat(jenkins) context collector for LLM prompt"
```

---

## Task 4: Jenkins Analyzer

**Files:**
- Create: `src/main/changelogai/feature/jenkins/engine/JenkinsAnalyzer.kt`
- Test: `src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsAnalyzerTest.kt`

- [ ] **Step 1: Написать тест**

```kotlin
// src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsAnalyzerTest.kt
package changelogai.feature.jenkins.engine

import changelogai.feature.jenkins.model.JenkinsAnalysis
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
        // rootCause gets full text when no section found
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
```

- [ ] **Step 2: Запустить тест — убедиться что падает**

```
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsAnalyzerTest" --rerun-tasks
```

Ожидается: FAILED — `unresolved reference: JenkinsAnalyzer`

- [ ] **Step 3: Создать JenkinsAnalyzer**

```kotlin
// src/main/changelogai/feature/jenkins/engine/JenkinsAnalyzer.kt
package changelogai.feature.jenkins.engine

import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.feature.jenkins.model.JenkinsAnalysis
import changelogai.platform.LLMClientFactory
import com.intellij.openapi.project.Project

class JenkinsAnalyzer(private val project: Project) {

    private val contextCollector = JenkinsContextCollector(project)

    /** Анализирует лог сборки через LLM. Запускать с фонового потока. */
    fun analyze(log: String): JenkinsAnalysis {
        val context = contextCollector.collect()
        val truncatedLog = JenkinsContextCollector.truncateLog(log, 4000)
        val prompt = buildPrompt(truncatedLog, context)
        val state = PluginState.getInstance()
        val request = ChatRequest(
            model = state.aiModel,
            temperature = 0.2,
            maxTokens = 1500,
            messages = listOf(ChatMessage("user", prompt))
        )
        return LLMClientFactory.create(state, null).use { client ->
            val raw = client.postChatCompletions(request).choices.firstOrNull()?.message?.content ?: ""
            parseAnalysisResponse(raw)
        }
    }

    companion object {

        fun buildPrompt(log: String, context: JenkinsContext): String {
            val filesSection = if (context.changedFiles.isNotEmpty())
                "\n\nИзменённые файлы в последнем коммите:\n" +
                context.changedFiles.joinToString("\n") { "- $it" }
            else ""
            val jenkinsfileSection = if (!context.jenkinsfileContent.isNullOrBlank())
                "\n\nСодержимое Jenkinsfile:\n```groovy\n${context.jenkinsfileContent.take(1000)}\n```"
            else ""

            return """
Ты эксперт по CI/CD. Проанализируй лог упавшей Jenkins-сборки и ответь на русском языке.

Лог сборки (последние 4000 символов):
```
$log
```$filesSection$jenkinsfileSection

Ответь строго в формате markdown с тремя разделами:

## Root Cause
(1-2 предложения: краткая причина падения)

## Related Files
(список файлов проекта через дефис, которые вероятно вызвали ошибку; пустой если неизвестно)

## Suggestions
(конкретные шаги для исправления через дефис, минимум 2)
            """.trimIndent()
        }

        fun parseAnalysisResponse(markdown: String): JenkinsAnalysis {
            val rootCause = extractSection(markdown, "Root Cause")
                ?: markdown.lines().firstOrNull { it.isNotBlank() }
                ?: "Не удалось определить причину"

            val relatedFiles = extractListSection(markdown, "Related Files")
            val suggestions = extractListSection(markdown, "Suggestions")

            return JenkinsAnalysis(
                rootCause = rootCause.trim(),
                relatedFiles = relatedFiles,
                suggestions = suggestions
            )
        }

        private fun extractSection(markdown: String, header: String): String? {
            val regex = Regex("""##\s+$header\s*\n(.*?)(?=\n##|\z)""", RegexOption.DOT_MATCHES_ALL)
            return regex.find(markdown)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun extractListSection(markdown: String, header: String): List<String> {
            val sectionText = extractSection(markdown, header) ?: return emptyList()
            return sectionText.lines()
                .map { it.trimStart('-', '*', ' ').trim() }
                .filter { it.isNotBlank() }
        }
    }
}
```

- [ ] **Step 4: Запустить тест — убедиться что проходит**

```
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsAnalyzerTest" --rerun-tasks
```

Ожидается: PASSED (3 теста)

- [ ] **Step 5: Commit**

```bash
git add src/main/changelogai/feature/jenkins/engine/JenkinsAnalyzer.kt \
        src/test/kotlin/changelogai/feature/jenkins/engine/JenkinsAnalyzerTest.kt
git commit -m "feat(jenkins) LLM analyzer for failed builds"
```

---

## Task 5: Chat Bridge

**Files:**
- Create: `src/main/changelogai/feature/jenkins/JenkinsChatBridge.kt`
- Modify: `src/main/changelogai/feature/gigacodeae/ui/GigaCodeAETab.kt`
- Test: `src/test/kotlin/changelogai/feature/jenkins/JenkinsChatBridgeTest.kt`

- [ ] **Step 1: Написать тест bridge**

```kotlin
// src/test/kotlin/changelogai/feature/jenkins/JenkinsChatBridgeTest.kt
package changelogai.feature.jenkins

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JenkinsChatBridgeTest {

    @BeforeEach
    fun setup() {
        // Clear listeners before each test
        JenkinsChatBridge.clearListeners()
    }

    @Test
    fun `addListener receives message from sendToChat`() {
        var received: String? = null
        JenkinsChatBridge.addListener { received = it }

        JenkinsChatBridge.sendToChat("Анализ сборки #42")

        assertEquals("Анализ сборки #42", received)
    }

    @Test
    fun `removeListener stops receiving messages`() {
        var callCount = 0
        val listener: (String) -> Unit = { callCount++ }
        JenkinsChatBridge.addListener(listener)
        JenkinsChatBridge.removeListener(listener)

        JenkinsChatBridge.sendToChat("test")

        assertEquals(0, callCount)
    }

    @Test
    fun `multiple listeners all receive the message`() {
        val received = mutableListOf<String>()
        JenkinsChatBridge.addListener { received.add("A: $it") }
        JenkinsChatBridge.addListener { received.add("B: $it") }

        JenkinsChatBridge.sendToChat("hello")

        assertEquals(2, received.size)
        assertTrue(received.any { it.startsWith("A:") })
        assertTrue(received.any { it.startsWith("B:") })
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться что падает**

```
./gradlew test --tests "changelogai.feature.jenkins.JenkinsChatBridgeTest" --rerun-tasks
```

Ожидается: FAILED — `unresolved reference: JenkinsChatBridge`

- [ ] **Step 3: Создать JenkinsChatBridge**

```kotlin
// src/main/changelogai/feature/jenkins/JenkinsChatBridge.kt
package changelogai.feature.jenkins

/**
 * Мост между Jenkins Feature и GigaCodeAE.
 * Аналог SpecCodeBridge — позволяет передать контекст упавшей сборки в чат.
 */
object JenkinsChatBridge {
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** Отправить текст в GigaCodeAE чат. Вызывать из EDT. */
    fun sendToChat(text: String) {
        listeners.forEach { it(text) }
    }

    /** Только для тестов. */
    internal fun clearListeners() {
        listeners.clear()
    }
}
```

- [ ] **Step 4: Запустить тест bridge — убедиться что проходит**

```
./gradlew test --tests "changelogai.feature.jenkins.JenkinsChatBridgeTest" --rerun-tasks
```

Ожидается: PASSED (3 теста)

- [ ] **Step 5: Добавить подписку в GigaCodeAETab**

Открыть `src/main/changelogai/feature/gigacodeae/ui/GigaCodeAETab.kt`.

Добавить поле рядом с `specBridgeListener` (строка ~58):

```kotlin
private val jenkinsBridgeListener: (String) -> Unit = { text ->
    SwingUtilities.invokeLater {
        inputPanel.setPrefilledText(text)
    }
}
```

В блоке `init { ... }` (рядом со строкой `SpecCodeBridge.addListener(specBridgeListener)`):

```kotlin
JenkinsChatBridge.addListener(jenkinsBridgeListener)
```

Добавить импорт в начале файла:

```kotlin
import changelogai.feature.jenkins.JenkinsChatBridge
```

- [ ] **Step 6: Проверить что проект компилируется**

```
./gradlew compileKotlin
```

Ожидается: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/changelogai/feature/jenkins/JenkinsChatBridge.kt \
        src/main/changelogai/feature/gigacodeae/ui/GigaCodeAETab.kt \
        src/test/kotlin/changelogai/feature/jenkins/JenkinsChatBridgeTest.kt
git commit -m "feat(jenkins) chat bridge to GigaCodeAE"
```

---

## Task 6: UI Components

**Files:**
- Create: `src/main/changelogai/feature/jenkins/ui/JenkinsPipelineList.kt`
- Create: `src/main/changelogai/feature/jenkins/ui/JenkinsBuildLog.kt`
- Create: `src/main/changelogai/feature/jenkins/ui/JenkinsAnalysisPanel.kt`

> UI-компоненты — Swing без бизнес-логики. Тесты нецелесообразны (требуют EDT/platform). Верификация — ручной запуск `./gradlew runIde`.

- [ ] **Step 1: Создать JenkinsPipelineList**

```kotlin
// src/main/changelogai/feature/jenkins/ui/JenkinsPipelineList.kt
package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsBuild
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsPipelineList(
    private val onPipelineSelected: (JenkinsPipeline) -> Unit
) {

    private val listModel = DefaultListModel<JenkinsPipeline>()
    private val list = JBList(listModel).apply {
        cellRenderer = PipelineCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    val component: JScrollPane = JScrollPane(list).apply {
        border = JBUI.Borders.empty()
    }

    init {
        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                list.selectedValue?.let { onPipelineSelected(it) }
            }
        }
    }

    fun setPipelines(pipelines: List<JenkinsPipeline>) {
        listModel.clear()
        pipelines.forEach { listModel.addElement(it) }
    }

    private inner class PipelineCellRenderer : ListCellRenderer<JenkinsPipeline> {
        override fun getListCellRendererComponent(
            list: JList<out JenkinsPipeline>, value: JenkinsPipeline, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(JBUI.scale(6), 0))
            panel.border = JBUI.Borders.empty(4, 8)
            panel.isOpaque = true
            panel.background = if (isSelected) list.selectionBackground else list.background

            val statusIcon = JLabel(statusIcon(value.status))
            val nameLabel = JLabel(value.name).apply {
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
            val buildLabel = JLabel(value.lastBuild?.let { "#${it.number}" } ?: "—").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            }

            panel.add(statusIcon, BorderLayout.WEST)
            panel.add(nameLabel, BorderLayout.CENTER)
            panel.add(buildLabel, BorderLayout.EAST)
            return panel
        }

        private fun statusIcon(status: BuildStatus): Icon = when (status) {
            BuildStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
            BuildStatus.FAILURE -> AllIcons.RunConfigurations.TestFailed
            BuildStatus.IN_PROGRESS -> AllIcons.Process.Step_1
            BuildStatus.ABORTED -> AllIcons.RunConfigurations.TestIgnored
            BuildStatus.UNKNOWN -> AllIcons.RunConfigurations.TestUnknown
        }
    }
}
```

- [ ] **Step 2: Создать JenkinsBuildLog**

```kotlin
// src/main/changelogai/feature/jenkins/ui/JenkinsBuildLog.kt
package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsBuild
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsBuildLog(
    private val onAnalyzeClicked: (log: String) -> Unit,
    private val onTriggerBuildClicked: (pipelineName: String) -> Unit
) {

    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
        background = JBColor(Color(13, 17, 23), Color(13, 17, 23))
        foreground = JBColor(Color(201, 209, 217), Color(201, 209, 217))
        border = JBUI.Borders.empty(8)
    }
    private val analyzeBtn = JButton("Анализировать", AllIcons.Actions.Find).apply {
        isVisible = false
    }
    private val triggerBtn = JButton("Запустить сборку", AllIcons.Actions.Execute).apply {
        isVisible = false
    }
    private var currentPipeline: JenkinsPipeline? = null

    val component: JPanel = buildLayout()

    private fun buildLayout(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.isOpaque = false

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        toolbar.isOpaque = false
        toolbar.add(triggerBtn)
        toolbar.add(analyzeBtn)

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(logArea), BorderLayout.CENTER)

        analyzeBtn.addActionListener {
            val log = logArea.text
            if (log.isNotBlank()) onAnalyzeClicked(log)
        }
        triggerBtn.addActionListener {
            currentPipeline?.name?.let { onTriggerBuildClicked(it) }
        }

        return panel
    }

    fun setPipeline(pipeline: JenkinsPipeline) {
        currentPipeline = pipeline
        val build = pipeline.lastBuild
        if (build != null) {
            showBuild(build, pipeline)
        } else {
            logArea.text = "Нет данных о сборках"
            analyzeBtn.isVisible = false
            triggerBtn.isVisible = true
        }
    }

    fun setLog(log: String) {
        logArea.text = log
        logArea.caretPosition = 0
    }

    fun setAnalyzing(analyzing: Boolean) {
        analyzeBtn.isEnabled = !analyzing
        analyzeBtn.text = if (analyzing) "Анализирую..." else "Анализировать"
    }

    private fun showBuild(build: JenkinsBuild, pipeline: JenkinsPipeline) {
        val header = "Сборка #${build.number} — ${build.status}\n" +
                "Длительность: ${build.durationMs / 1000}с\n\n"
        logArea.text = header + build.log.ifBlank { "Лог пуст" }
        logArea.caretPosition = 0
        analyzeBtn.isVisible = build.status == BuildStatus.FAILURE
        triggerBtn.isVisible = true
    }
}
```

- [ ] **Step 3: Создать JenkinsAnalysisPanel**

```kotlin
// src/main/changelogai/feature/jenkins/ui/JenkinsAnalysisPanel.kt
package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.JenkinsChatBridge
import changelogai.feature.jenkins.model.JenkinsAnalysis
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsAnalysisPanel {

    private val rootCauseArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(Color(22, 27, 34), Color(22, 27, 34))
        foreground = JBColor(Color(248, 166, 79), Color(248, 166, 79))
        border = JBUI.Borders.empty(6)
        font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
    }
    private val suggestionsArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(Color(22, 27, 34), Color(22, 27, 34))
        foreground = JBColor(Color(201, 209, 217), Color(201, 209, 217))
        border = JBUI.Borders.empty(6)
        font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
    }
    private val continueBtn = JButton("Продолжить в GigaCodeAE", AllIcons.Actions.Forward).apply {
        isVisible = false
    }
    private val spinner = JLabel("Анализирую...", AllIcons.Process.Step_1, SwingConstants.LEFT)
    private var lastAnalysis: JenkinsAnalysis? = null
    private var lastLog: String = ""

    val component: JPanel = buildLayout()

    private fun buildLayout(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4)

        val header = JLabel("AI-анализ", AllIcons.Actions.Find, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        }

        val causePanel = JPanel(BorderLayout())
        causePanel.isOpaque = false
        causePanel.add(JLabel("Причина:").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
        causePanel.add(rootCauseArea, BorderLayout.CENTER)

        val suggestPanel = JPanel(BorderLayout())
        suggestPanel.isOpaque = false
        suggestPanel.add(JLabel("Рекомендации:").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
        suggestPanel.add(suggestionsArea, BorderLayout.CENTER)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(causePanel)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(suggestPanel)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(continueBtn)
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(spinner, BorderLayout.CENTER)

        spinner.isVisible = false

        continueBtn.addActionListener {
            val analysis = lastAnalysis ?: return@addActionListener
            val prompt = buildChatPrompt(analysis, lastLog)
            JenkinsChatBridge.sendToChat(prompt)
        }

        panel.add(content, BorderLayout.CENTER)
        return panel
    }

    fun setLoading(loading: Boolean) {
        spinner.isVisible = loading
        rootCauseArea.isVisible = !loading
        suggestionsArea.isVisible = !loading
        continueBtn.isVisible = !loading && lastAnalysis != null
    }

    fun setAnalysis(analysis: JenkinsAnalysis, originalLog: String) {
        lastAnalysis = analysis
        lastLog = originalLog
        rootCauseArea.text = analysis.rootCause
        val suggestionsText = analysis.suggestions.joinToString("\n") { "• $it" }
        val filesText = if (analysis.relatedFiles.isNotEmpty())
            "\nСвязанные файлы:\n" + analysis.relatedFiles.joinToString("\n") { "  - $it" }
        else ""
        suggestionsArea.text = suggestionsText + filesText
        continueBtn.isVisible = true
        setLoading(false)
    }

    private fun buildChatPrompt(analysis: JenkinsAnalysis, log: String): String {
        val truncatedLog = if (log.length > 1000) "...\n" + log.takeLast(1000) else log
        return """
Продолжи анализ упавшей Jenkins-сборки.

**Причина:** ${analysis.rootCause}

**Связанные файлы:** ${analysis.relatedFiles.joinToString(", ").ifBlank { "не определены" }}

**Рекомендации:**
${analysis.suggestions.joinToString("\n") { "- $it" }}

**Лог сборки:**
```
$truncatedLog
```

Помоги исправить проблему.
        """.trimIndent()
    }
}
```

- [ ] **Step 4: Проверить компиляцию UI-компонентов**

```
./gradlew compileKotlin
```

Ожидается: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/changelogai/feature/jenkins/ui/
git commit -m "feat(jenkins) UI components: PipelineList, BuildLog, AnalysisPanel"
```

---

## Task 7: JenkinsPanel (Root UI)

**Files:**
- Create: `src/main/changelogai/feature/jenkins/ui/JenkinsPanel.kt`

- [ ] **Step 1: Создать JenkinsPanel**

```kotlin
// src/main/changelogai/feature/jenkins/ui/JenkinsPanel.kt
package changelogai.feature.jenkins.ui

import changelogai.feature.jenkins.engine.JenkinsDemoData
import changelogai.feature.jenkins.engine.JenkinsMcpFetcher
import changelogai.feature.jenkins.engine.JenkinsMcpNotConfiguredException
import changelogai.feature.jenkins.engine.JenkinsAnalyzer
import changelogai.feature.jenkins.model.JenkinsPipeline
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class JenkinsPanel(private val project: Project) {

    private val fetcher = JenkinsMcpFetcher(project)
    private val analyzer = JenkinsAnalyzer(project)

    private val isDemoMode = JCheckBox("Demo").apply {
        isOpaque = false
        toolTipText = "Использовать демо-данные вместо MCP"
        isSelected = !fetcher.isConfigured()
    }
    private val refreshBtn = JButton("Обновить", AllIcons.Actions.Refresh)
    private val statusLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.ITALIC, JBUI.scale(11))
        foreground = JBColor.GRAY
    }
    private val mcpSetupBtn = JButton("Настроить Jenkins MCP", AllIcons.General.Settings).apply {
        isVisible = !fetcher.isConfigured()
    }

    private val pipelineList = JenkinsPipelineList { pipeline -> onPipelineSelected(pipeline) }
    private val buildLog = JenkinsBuildLog(
        onAnalyzeClicked = { log -> startAnalysis(log) },
        onTriggerBuildClicked = { name -> triggerBuild(name) }
    )
    private val analysisPanel = JenkinsAnalysisPanel()

    val panel: JPanel = buildLayout()

    private fun buildLayout(): JPanel {
        val root = JPanel(BorderLayout(0, JBUI.scale(6)))
        root.border = JBUI.Borders.empty(8)

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        toolbar.isOpaque = false
        toolbar.add(refreshBtn)
        toolbar.add(isDemoMode)
        toolbar.add(mcpSetupBtn)

        val topPanel = JPanel(BorderLayout(0, JBUI.scale(4)))
        topPanel.isOpaque = false
        topPanel.add(toolbar, BorderLayout.CENTER)
        topPanel.add(statusLabel, BorderLayout.SOUTH)

        // Split: left = pipeline list, right = log + analysis
        val rightPanel = JPanel(BorderLayout(0, JBUI.scale(6)))
        rightPanel.isOpaque = false
        rightPanel.add(buildLog.component, BorderLayout.CENTER)
        rightPanel.add(analysisPanel.component, BorderLayout.SOUTH)

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pipelineList.component, rightPanel).apply {
            dividerLocation = JBUI.scale(200)
            isContinuousLayout = true
            border = null
        }

        root.add(topPanel, BorderLayout.NORTH)
        root.add(splitPane, BorderLayout.CENTER)

        // Wire actions
        refreshBtn.addActionListener { loadPipelines() }
        mcpSetupBtn.addActionListener { showMcpSetupHint() }
        isDemoMode.addActionListener { loadPipelines() }

        // Initial load
        loadPipelines()

        return root
    }

    private fun loadPipelines() {
        refreshBtn.isEnabled = false
        setStatus("⏳ Загружаю пайплайны...", JBColor.GRAY)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pipelines = if (isDemoMode.isSelected) {
                    JenkinsDemoData.pipelines
                } else {
                    fetcher.getPipelines()
                }
                SwingUtilities.invokeLater {
                    pipelineList.setPipelines(pipelines)
                    setStatus("✓ ${pipelines.size} пайплайнов", JBColor(Color(61, 214, 140), Color(61, 214, 140)))
                    refreshBtn.isEnabled = true
                    // Select first pipeline automatically
                    if (pipelines.isNotEmpty()) onPipelineSelected(pipelines.first())
                }
            } catch (e: JenkinsMcpNotConfiguredException) {
                SwingUtilities.invokeLater {
                    isDemoMode.isSelected = true
                    mcpSetupBtn.isVisible = true
                    setStatus("⚠ Jenkins MCP не настроен — включён Demo режим", JBColor.ORANGE)
                    refreshBtn.isEnabled = true
                    loadPipelines()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("✗ ${e.message?.take(80)}", JBColor.RED)
                    refreshBtn.isEnabled = true
                }
            }
        }
    }

    private fun onPipelineSelected(pipeline: JenkinsPipeline) {
        buildLog.setPipeline(pipeline)
        if (!isDemoMode.isSelected && pipeline.lastBuild?.log.isNullOrBlank() && pipeline.lastBuild != null) {
            // Load full log from MCP
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val log = fetcher.getBuildLog(pipeline.name, pipeline.lastBuild.number)
                    SwingUtilities.invokeLater { buildLog.setLog(log) }
                } catch (_: Exception) { /* keep existing log */ }
            }
        }
    }

    private fun startAnalysis(log: String) {
        buildLog.setAnalyzing(true)
        analysisPanel.setLoading(true)

        if (isDemoMode.isSelected) {
            SwingUtilities.invokeLater {
                analysisPanel.setAnalysis(JenkinsDemoData.demoAnalysis, log)
                buildLog.setAnalyzing(false)
            }
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val analysis = analyzer.analyze(log)
                SwingUtilities.invokeLater {
                    analysisPanel.setAnalysis(analysis, log)
                    buildLog.setAnalyzing(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("✗ Ошибка анализа: ${e.message?.take(60)}", JBColor.RED)
                    buildLog.setAnalyzing(false)
                    analysisPanel.setLoading(false)
                }
            }
        }
    }

    private fun triggerBuild(pipelineName: String) {
        if (isDemoMode.isSelected) {
            setStatus("Demo: запуск сборки $pipelineName (имитация)", JBColor.GRAY)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                fetcher.triggerBuild(pipelineName)
                SwingUtilities.invokeLater {
                    setStatus("✓ Сборка $pipelineName запущена", JBColor(Color(61, 214, 140), Color(61, 214, 140)))
                    loadPipelines()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("✗ ${e.message?.take(80)}", JBColor.RED)
                }
            }
        }
    }

    private fun showMcpSetupHint() {
        JOptionPane.showMessageDialog(
            panel,
            "Откройте GIGACOURME → MCP и добавьте Jenkins-сервер:\n" +
            "• Тип: HTTP или STDIO\n" +
            "• Preset: jenkins\n" +
            "• Токен Jenkins (API token)",
            "Настройка Jenkins MCP",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun setStatus(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }
}
```

- [ ] **Step 2: Проверить компиляцию**

```
./gradlew compileKotlin
```

Ожидается: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/changelogai/feature/jenkins/ui/JenkinsPanel.kt
git commit -m "feat(jenkins) root panel orchestrating all UI components"
```

---

## Task 8: Feature Registration

**Files:**
- Create: `src/main/changelogai/feature/jenkins/JenkinsFeature.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Создать JenkinsFeature**

```kotlin
// src/main/changelogai/feature/jenkins/JenkinsFeature.kt
package changelogai.feature.jenkins

import changelogai.core.feature.Feature
import changelogai.feature.jenkins.ui.JenkinsPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.JPanel

class JenkinsFeature : Feature {
    override val id = "jenkins"
    override val name = "Jenkins"
    override val description = "Jenkins Dashboard: мониторинг пайплайнов и AI-анализ упавших сборок"
    override val icon = AllIcons.Nodes.Plugin

    override fun isAvailable(project: Project): Boolean = project.basePath != null

    override fun createTab(project: Project): JPanel = JenkinsPanel(project).panel
}
```

- [ ] **Step 2: Зарегистрировать в plugin.xml**

В файле `src/main/resources/META-INF/plugin.xml`, в блоке `<extensions defaultExtensionNs="changelogai">`, после строки с `SprintFeature`, добавить:

```xml
<feature implementation="changelogai.feature.jenkins.JenkinsFeature"/>
```

Итоговый блок должен выглядеть так:

```xml
<extensions defaultExtensionNs="changelogai">
    <feature implementation="changelogai.feature.changelog.ChangelogFeature"/>
    <feature implementation="changelogai.feature.gigacodeae.GigaCodeAEFeature"/>
    <feature implementation="changelogai.feature.coverage.CoverageFeature"/>
    <feature implementation="changelogai.feature.gigacodeae.mcp.McpFeature"/>
    <feature implementation="changelogai.feature.spec.SpecFeature"/>
    <feature implementation="changelogai.feature.kb.KnowledgeBaseFeature"/>
    <feature implementation="changelogai.feature.sprint.SprintFeature"/>
    <feature implementation="changelogai.feature.jenkins.JenkinsFeature"/>
</extensions>
```

- [ ] **Step 3: Собрать плагин**

```
./gradlew buildPlugin
```

Ожидается: BUILD SUCCESSFUL, zip-файл в `build/distributions/`

- [ ] **Step 4: Запустить все тесты**

```
./gradlew test --rerun-tasks
```

Ожидается: ALL TESTS PASSED

- [ ] **Step 5: Запустить IDE и проверить вкладку Jenkins вручную**

```
./gradlew runIde
```

Проверить:
- Вкладка "Jenkins" появилась в боковой панели GIGACOURME
- Demo Mode включён по умолчанию (если MCP не настроен)
- 3 пайплайна в списке: my-service (красный), api-gateway (зелёный), frontend (жёлтый)
- При выборе my-service — отображается лог с ошибкой компиляции
- Кнопка "Анализировать" видна только для my-service (FAILURE)
- При нажатии "Анализировать" — показывается demo-анализ с причиной и рекомендациями
- Кнопка "Продолжить в GigaCodeAE" открывает чат с промптом

- [ ] **Step 6: Commit**

```bash
git add src/main/changelogai/feature/jenkins/JenkinsFeature.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(jenkins) feature registration — Jenkins Dashboard complete"
```
