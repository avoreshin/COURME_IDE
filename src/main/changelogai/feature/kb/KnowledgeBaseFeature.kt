package changelogai.feature.kb

import changelogai.core.feature.Feature
import changelogai.feature.kb.ui.KnowledgeBasePanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.JPanel

class KnowledgeBaseFeature : Feature {
    override val id = "kb"
    override val name = "Knowledge Base"
    override val description = "База знаний из Confluence с векторным поиском для генерации требований"
    override val icon = AllIcons.Nodes.DataSchema

    override fun isAvailable(project: Project): Boolean = true

    override fun createTab(project: Project): JPanel = KnowledgeBasePanel(project).panel
}
