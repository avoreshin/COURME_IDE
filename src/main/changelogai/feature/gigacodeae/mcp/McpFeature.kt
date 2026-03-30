package changelogai.feature.gigacodeae.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import changelogai.core.feature.Feature
import javax.swing.JPanel

class McpFeature : Feature {
    override val id = "mcp-manager"
    override val name = "MCP Servers"
    override val description = "Просмотр и управление MCP-серверами из IDE (Cursor, Claude Desktop, mcp.json)"
    override val icon = AllIcons.General.ExternalTools

    override fun isAvailable(project: Project): Boolean = true

    override fun createTab(project: Project): JPanel = McpManagerPanel(project).panel
}
