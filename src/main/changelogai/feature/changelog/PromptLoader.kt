package changelogai.feature.changelog

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

private const val PROMPTS_DIR = ".changelogai"

object PromptLoader {

    fun load(project: Project, filename: String): String {
        val promptFile = promptFile(project, filename)
        if (!promptFile.exists()) {
            extractDefault(filename, promptFile)
        }
        LocalFileSystem.getInstance().refreshAndFindFileByPath(promptFile.absolutePath)
        return promptFile.readText().trim()
    }

    private fun promptFile(project: Project, filename: String): File =
        File(project.basePath, "$PROMPTS_DIR/$filename")

    private fun extractDefault(filename: String, target: File) {
        val resource = PromptLoader::class.java.getResourceAsStream("/changelogai/prompts/$filename")
            ?: error("Default prompt resource not found: $filename")
        target.parentFile.mkdirs()
        target.writeBytes(resource.readBytes())
    }
}
