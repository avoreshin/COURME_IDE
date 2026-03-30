package changelogai.feature.changelog

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ChangelogWriter {
    fun prepend(project: Project, changelog: String) {
        val basePath = project.basePath ?: return
        val file = File(basePath, "CHANGELOG.md")
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val header = "## [$date]\n\n"
        val newContent = if (file.exists()) {
            header + changelog + "\n\n---\n\n" + file.readText()
        } else {
            "# Changelog\n\n" + header + changelog + "\n"
        }
        file.writeText(newContent)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
            ?.refresh(false, false)
    }
}
