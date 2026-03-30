package changelogai.feature.coverage

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.platform.LLMClientFactory
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class MethodCoverage(val name: String, val covered: Boolean)

data class ClassCoverage(
    val name: String,
    val packageName: String,
    val methods: List<MethodCoverage>,
    val instructionsCovered: Int,
    val instructionsTotal: Int
) {
    val coveredMethods: Int get() = methods.count { it.covered }
    val totalMethods: Int get() = methods.size
    val methodCoverage: Double get() = if (totalMethods == 0) 1.0 else coveredMethods.toDouble() / totalMethods
    val instructionCoverage: Double get() = if (instructionsTotal == 0) 1.0 else instructionsCovered.toDouble() / instructionsTotal
}

data class CoverageReport(
    val classes: List<ClassCoverage>,
    val source: String   // "jacoco" | "llm" | "llm-error:..." | "jacoco-error:..."
) {
    val totalMethods: Int get() = classes.sumOf { it.totalMethods }
    val coveredMethods: Int get() = classes.sumOf { it.coveredMethods }
    val totalInstructions: Int get() = classes.sumOf { it.instructionsTotal }
    val coveredInstructions: Int get() = classes.sumOf { it.instructionsCovered }
    val methodCoverage: Double get() = if (totalMethods == 0) 0.0 else coveredMethods.toDouble() / totalMethods
    val instructionCoverage: Double get() = if (totalInstructions == 0) 0.0 else coveredInstructions.toDouble() / totalInstructions
    val classCoverage: Double get() = if (classes.isEmpty()) 0.0
        else classes.count { it.coveredMethods > 0 }.toDouble() / classes.size
}

/** Определяет тип системы сборки и путь к JaCoCo-отчёту */
enum class BuildSystem { GRADLE, MAVEN, UNKNOWN }

object CoverageAnalyzer {
    private val log = Logger.getInstance(CoverageAnalyzer::class.java)

    fun analyze(project: Project): CoverageReport {
        val basePath = project.basePath ?: run {
            log.warn("Coverage analysis skipped: project.basePath is null")
            return CoverageReport(emptyList(), "error: no basePath")
        }
        val jacocoXml = findJacocoXml(basePath)
        return if (jacocoXml != null) {
            log.info("Coverage analysis using JaCoCo report: ${jacocoXml.path}")
            parseJacoco(jacocoXml)
        } else {
            log.info("Coverage analysis using LLM (no JaCoCo XML found under $basePath)")
            analyzeWithLLM(project, basePath)
        }
    }

    fun detectBuildSystem(basePath: String): BuildSystem = when {
        File(basePath, "pom.xml").exists()                                    -> BuildSystem.MAVEN
        File(basePath, "build.gradle").exists() ||
        File(basePath, "build.gradle.kts").exists()                           -> BuildSystem.GRADLE
        else                                                                   -> BuildSystem.UNKNOWN
    }

    /** Возвращает команду и аргументы для запуска тестов + генерации JaCoCo-отчёта */
    fun buildTestCommand(basePath: String): List<String> = when (detectBuildSystem(basePath)) {
        BuildSystem.MAVEN -> listOf(
            if (File(basePath, "mvnw").exists()) "./mvnw" else "mvn",
            "test", "jacoco:report"
        )
        BuildSystem.GRADLE -> listOf(
            if (File(basePath, "gradlew").exists()) "./gradlew" else "gradle",
            "test", "jacocoTestReport"
        )
        BuildSystem.UNKNOWN -> listOf(
            if (File(basePath, "gradlew").exists()) "./gradlew"
            else if (File(basePath, "mvnw").exists()) "./mvnw"
            else "gradle",
            "test"
        )
    }

    // ── JaCoCo XML ────────────────────────────────────────────────────────

    private fun findJacocoXml(basePath: String): File? {
        val candidates = listOf(
            // Gradle (стандарт)
            "build/reports/jacoco/test/jacocoTestReport.xml",
            // Maven (стандарт)
            "target/site/jacoco/jacoco.xml",
            // Maven (альтернатива)
            "target/jacoco-ut/jacoco.xml",
            // Gradle multi-project / кастомный путь
            "build/reports/jacoco/jacocoTestReport.xml",
        )
        return candidates.map { File(basePath, it) }.firstOrNull { it.exists() }
    }

    private fun parseJacoco(file: File): CoverageReport {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isValidating = false
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            val doc = factory.newDocumentBuilder().parse(file)
            val classNodes = doc.getElementsByTagName("class")
            val classes = mutableListOf<ClassCoverage>()

            for (i in 0 until classNodes.length) {
                val classEl = classNodes.item(i) as? org.w3c.dom.Element ?: continue
                val fullName = classEl.getAttribute("name").replace('/', '.')
                // Пропускаем анонимные классы (Foo$1) но оставляем inner-классы (Foo$Bar)
                if (fullName.contains(Regex("\\$\\d+"))) continue
                val lastDot = fullName.lastIndexOf('.')
                val simpleName = if (lastDot >= 0) fullName.substring(lastDot + 1) else fullName
                val pkg = if (lastDot >= 0) fullName.substring(0, lastDot) else ""

                val methods = mutableListOf<MethodCoverage>()
                val methodNodes = classEl.getElementsByTagName("method")
                for (j in 0 until methodNodes.length) {
                    val methodEl = methodNodes.item(j) as? org.w3c.dom.Element ?: continue
                    val methodName = methodEl.getAttribute("name")
                    if (methodName == "<init>" || methodName == "<clinit>" || methodName.startsWith("lambda$")) continue
                    val covered = getCounter(methodEl, "METHOD")
                        ?.getAttribute("covered")?.toIntOrNull() ?: 0
                    methods.add(MethodCoverage(methodName, covered > 0))
                }

                val instrCovered = getCounter(classEl, "INSTRUCTION")?.getAttribute("covered")?.toIntOrNull() ?: 0
                val instrMissed  = getCounter(classEl, "INSTRUCTION")?.getAttribute("missed")?.toIntOrNull() ?: 0
                classes.add(ClassCoverage(simpleName, pkg, methods, instrCovered, instrCovered + instrMissed))
            }

            CoverageReport(classes.sortedWith(compareBy({ it.packageName }, { it.name })), "jacoco")
        } catch (e: Exception) {
            log.warn("Failed to parse JaCoCo report: ${file.path}", e)
            CoverageReport(emptyList(), "jacoco-error: ${e.message}")
        }
    }

    private fun getCounter(el: org.w3c.dom.Element, type: String): org.w3c.dom.Element? {
        val counters = el.getElementsByTagName("counter")
        for (i in 0 until counters.length) {
            val c = counters.item(i) as? org.w3c.dom.Element ?: continue
            if (c.getAttribute("type") == type && c.parentNode == el) return c
        }
        return null
    }

    // ── LLM-based analysis ────────────────────────────────────────────────

    private fun analyzeWithLLM(project: Project, basePath: String): CoverageReport {
        val (srcDirs, testDirs) = sourceDirs(basePath)

        val sourceFiles = srcDirs.flatMap { collectSourceFiles(it) }
        val testFiles   = testDirs.flatMap { collectSourceFiles(it) }

        log.info(
            "LLM coverage analysis: basePath=$basePath, srcDirs=${srcDirs.size}, testDirs=${testDirs.size}, " +
                "sourceFiles=${sourceFiles.size}, testFiles=${testFiles.size}"
        )
        if (sourceFiles.isEmpty()) return CoverageReport(emptyList(), "llm")

        val sourceStructure = buildString {
            sourceFiles.forEach { f ->
                val rel = srcDirs.firstNotNullOfOrNull { d ->
                    runCatching { f.relativeTo(d).path }.getOrNull()
                } ?: f.name
                appendLine("=== $rel ===")
                appendLine(extractStructure(f))
            }
        }.take(20_000)

        val testContent = buildString {
            testFiles.forEach { f ->
                val rel = testDirs.firstNotNullOfOrNull { d ->
                    runCatching { f.relativeTo(d).path }.getOrNull()
                } ?: f.name
                appendLine("=== $rel ===")
                appendLine(f.readText().take(3_000))
                appendLine()
            }
        }.take(30_000)

        val prompt = """
Проанализируй покрытие тестами.

## Структура исходного кода:
$sourceStructure

## Тесты:
${if (testContent.isBlank()) "Тестов нет." else testContent}

Верни JSON-массив без markdown-оберток. Для каждого класса из исходников:
- "class": простое имя класса (без пакета)
- "package": пакет
- "covered": список методов, которые явно тестируются в тестах
- "uncovered": список методов, для которых тестов нет

Пример: [{"class":"Foo","package":"com.example","covered":["bar"],"uncovered":["baz"]}]

Отвечай ТОЛЬКО JSON, без пояснений.
""".trimIndent()

        return try {
            val state = PluginState.getInstance()
            val request = ChatRequest(
                model = state.aiModel,
                temperature = 0.0,
                maxTokens = 4000,
                messages = listOf(
                    ChatMessage(role = "system", content = "Ты инструмент анализа покрытия тестами. Отвечай только валидным JSON."),
                    ChatMessage(role = "user", content = prompt)
                )
            )
        log.info(
            "Sending LLM coverage request: model=${state.aiModel}, temperature=0.0, maxTokens=4000, " +
                "promptChars=${prompt.length}, sourceStructureChars=${sourceStructure.length}, testContentChars=${testContent.length}"
        )
            val rawJson = LLMClientFactory.create(state, null).use { client ->
                client.postChatCompletions(request)
                    .choices.firstOrNull()?.message?.content?.trim()
                    ?: return CoverageReport(emptyList(), "llm-error: empty response")
            }
        log.info("LLM coverage raw response length=${rawJson.length}, snippet=${rawJson.take(400)}")
            parseLLMResponse(rawJson)
        } catch (e: Exception) {
        log.warn("LLM-based coverage analysis failed", e)
        CoverageReport(emptyList(), "llm-error: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun parseLLMResponse(json: String): CoverageReport {
        val cleaned = json.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val mapper = jacksonObjectMapper()
            val nodes = mapper.readTree(cleaned)
            val classes = nodes.mapNotNull { node ->
                val className = node.get("class")?.asText() ?: return@mapNotNull null
                val pkg       = node.get("package")?.asText() ?: ""
                val covered   = node.get("covered")?.map { it.asText() } ?: emptyList()
                val uncovered = node.get("uncovered")?.map { it.asText() } ?: emptyList()
                val methods   = (covered.map { MethodCoverage(it, true) } +
                                 uncovered.map { MethodCoverage(it, false) }).distinctBy { it.name }
                ClassCoverage(className, pkg, methods, covered.size, (covered.size + uncovered.size).coerceAtLeast(1))
            }
            CoverageReport(classes.sortedWith(compareBy({ it.packageName }, { it.name })), "llm")
        } catch (e: Exception) {
            log.warn("Failed to parse LLM JSON response", e)
            log.warn("LLM JSON cleaned snippet=${cleaned.take(500)}")
            throw e
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Возвращает пары (srcDirs, testDirs) с учётом Gradle и Maven раскладки.
     * Ищет наиболее конкретные каталоги, содержащие исходные файлы, чтобы
     * избежать дублирования (когда родительский dir включает дочерние).
     */
    private fun sourceDirs(basePath: String): Pair<List<File>, List<File>> {
        fun dirsWithSources(candidates: List<String>): List<File> {
            // Возвращаем кандидатов в порядке специфичности (более глубокие сначала)
            val found = candidates
                .map { File(basePath, it) }
                .filter { it.exists() && it.walkTopDown().any { f -> f.isFile && (f.extension == "kt" || f.extension == "java") } }
            if (found.isEmpty()) return emptyList()
            // Убираем каталоги, которые являются предками других найденных каталогов
            return found.filter { candidate -> found.none { other -> other != candidate && other.startsWith(candidate) } }
        }

        val src  = dirsWithSources(listOf("src/main/java", "src/main/kotlin", "src/main/changelogai", "src/main"))
        val test = dirsWithSources(listOf("src/test/java", "src/test/kotlin", "src/test/changelogai", "src/test"))
        return src to test
    }

    /** Собирает .java и .kt файлы рекурсивно */
    private fun collectSourceFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { !it.path.contains("/build/") && !it.path.contains("/target/") }
            .toList()
    }

    /** Извлекает только сигнатуры классов и методов (поддерживает Java и Kotlin) */
    private fun extractStructure(file: File): String {
        return try {
            val isJava = file.extension == "java"
            file.readLines().filter { line ->
                val t = line.trim()
                if (isJava) {
                    // Java: class/interface/enum + методы (не конструкторы, не аннотации)
                    t.contains(Regex("\\b(class|interface|enum)\\s+\\w+")) ||
                    t.contains(Regex("(public|protected|private|static|void|\\w+)\\s+\\w+\\s*\\(")) &&
                    !t.startsWith("//") && !t.startsWith("*") ||
                    t.startsWith("package ")
                } else {
                    // Kotlin
                    t.startsWith("class ") || t.startsWith("object ") ||
                    t.startsWith("interface ") || t.startsWith("data class ") ||
                    t.contains(Regex("(?:override\\s+)?(?:public\\s+|private\\s+|protected\\s+|internal\\s+)?fun\\s+\\w+")) ||
                    t.startsWith("package ")
                }
            }.joinToString("\n")
        } catch (_: Exception) { "" }
    }
}
