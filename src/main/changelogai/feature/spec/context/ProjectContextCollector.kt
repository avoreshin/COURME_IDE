package changelogai.feature.spec.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File

data class ProjectContext(
    val projectName: String,
    val language: String?,
    val framework: String?,
    val buildTool: String?,
    val dependencies: List<String>,
    val openFileName: String?,
    val projectStructure: String
)

class ProjectContextCollector(private val project: Project) {

    fun collect(): ProjectContext {
        val basePath = project.basePath ?: return empty()
        val base = File(basePath)

        val buildTool = detectBuildTool(base)
        val deps = extractDependencies(base, buildTool)
        val language = detectLanguage(base)
        val framework = detectFramework(deps, base)
        val structure = buildStructureTree(base)
        val openFile = FileEditorManager.getInstance(project)
            .selectedFiles.firstOrNull()?.name

        return ProjectContext(
            projectName = project.name,
            language = language,
            framework = framework,
            buildTool = buildTool,
            dependencies = deps.take(15),
            openFileName = openFile,
            projectStructure = structure
        )
    }

    private fun empty() = ProjectContext(project.name, null, null, null, emptyList(), null, "")

    private fun detectBuildTool(base: File): String? = when {
        File(base, "build.gradle.kts").exists() || File(base, "build.gradle").exists() -> "Gradle"
        File(base, "pom.xml").exists() -> "Maven"
        File(base, "package.json").exists() -> "npm"
        else -> null
    }

    private fun detectLanguage(base: File): String? {
        val counts = mutableMapOf<String, Int>()
        base.walkTopDown()
            .filter { it.isFile && !it.path.contains("/build/") && !it.path.contains("/.gradle/") }
            .take(500)
            .forEach { f ->
                when (f.extension.lowercase()) {
                    "kt"   -> counts["Kotlin"] = (counts["Kotlin"] ?: 0) + 1
                    "java" -> counts["Java"] = (counts["Java"] ?: 0) + 1
                    "py"   -> counts["Python"] = (counts["Python"] ?: 0) + 1
                    "ts"   -> counts["TypeScript"] = (counts["TypeScript"] ?: 0) + 1
                    "js"   -> counts["JavaScript"] = (counts["JavaScript"] ?: 0) + 1
                    "go"   -> counts["Go"] = (counts["Go"] ?: 0) + 1
                }
            }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun detectFramework(deps: List<String>, base: File): String? {
        val all = deps.joinToString(" ").lowercase()
        return when {
            "spring-boot" in all || "spring" in all -> "Spring Boot"
            "ktor" in all -> "Ktor"
            "micronaut" in all -> "Micronaut"
            "quarkus" in all -> "Quarkus"
            "django" in all -> "Django"
            "fastapi" in all -> "FastAPI"
            "react" in all -> "React"
            "vue" in all -> "Vue"
            "angular" in all -> "Angular"
            File(base, "src/main/resources/application.yml").exists() ||
                    File(base, "src/main/resources/application.properties").exists() -> "Spring"
            else -> null
        }
    }

    private fun extractDependencies(base: File, buildTool: String?): List<String> {
        return when (buildTool) {
            "Gradle" -> {
                val f = File(base, "build.gradle.kts").takeIf { it.exists() }
                    ?: File(base, "build.gradle").takeIf { it.exists() }
                    ?: return emptyList()
                Regex("""["']([a-zA-Z0-9.\-]+:[a-zA-Z0-9.\-]+):[^"']+["']""")
                    .findAll(f.readText())
                    .map { it.groupValues[1] }
                    .distinct().toList()
            }
            "Maven" -> {
                val f = File(base, "pom.xml").takeIf { it.exists() } ?: return emptyList()
                val text = f.readText()
                val groups = Regex("""<groupId>(.*?)</groupId>""").findAll(text).map { it.groupValues[1] }.toList()
                val artifacts = Regex("""<artifactId>(.*?)</artifactId>""").findAll(text).map { it.groupValues[1] }.toList()
                groups.zip(artifacts).map { (g, a) -> "$g:$a" }.drop(1) // skip project's own GAV
            }
            "npm" -> {
                val f = File(base, "package.json").takeIf { it.exists() } ?: return emptyList()
                Regex(""""([a-zA-Z@][a-zA-Z0-9/@\-]*)"\s*:\s*"[^"]*"""")
                    .findAll(f.readText())
                    .map { it.groupValues[1] }
                    .filter { !it.startsWith("\"") }
                    .distinct().toList()
            }
            else -> emptyList()
        }
    }

    private fun buildStructureTree(base: File): String {
        val sb = StringBuilder()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > 4) return
            val children = dir.listFiles()
                ?.filter { !it.name.startsWith(".") && it.name !in setOf("build", "target", "node_modules", ".gradle") }
                ?.sortedWith(compareBy({ it.isFile }, { it.name }))
                ?: return
            children.forEachIndexed { i, f ->
                val connector = if (i == children.lastIndex) "└── " else "├── "
                sb.appendLine("$prefix$connector${f.name}${if (f.isDirectory) "/" else ""}")
                if (f.isDirectory) {
                    val child = if (i == children.lastIndex) "    " else "│   "
                    walk(f, "$prefix$child", depth + 1)
                }
            }
        }
        sb.appendLine(base.name + "/")
        walk(base, "", 0)
        return sb.lines().take(60).joinToString("\n")
    }
}
