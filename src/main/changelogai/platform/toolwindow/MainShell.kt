package changelogai.platform.toolwindow

import changelogai.core.feature.FeatureToggleState
import changelogai.platform.FeatureRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import java.awt.*
import javax.swing.*

/**
 * Главная оболочка плагина: вертикальный NavBar (слева) + CardLayout область контента (справа).
 * Заменяет стандартные IntelliJ content-вкладки — в ToolWindow регистрируется один content.
 */
class MainShell(private val project: Project) : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val contentArea = JPanel(cardLayout)

    init {
        isOpaque = false

        // ── Collect enabled features ───────────────────────────────────────────
        val features = FeatureRegistry.getInstance().getAll()
            .filter { FeatureToggleState.getInstance().isEnabled(it) }

        // ── Register panels in CardLayout ──────────────────────────────────────
        contentArea.add(HomePanel().panel, HOME_ID)

        features.forEach { feature ->
            contentArea.add(feature.createTab(project), feature.id)
        }

        contentArea.add(SettingsTab(project).panel, SETTINGS_ID)

        // ── Build nav items ────────────────────────────────────────────────────
        val featureItems = features.mapNotNull { f ->
            val icon = f.icon ?: return@mapNotNull null
            NavItem(id = f.id, icon = icon, tooltip = f.name)
        }

        val homeItem     = NavItem(HOME_ID,     AllIcons.Nodes.HomeFolder,   "Home")
        val settingsItem = NavItem(SETTINGS_ID, AllIcons.General.GearPlain,  "Settings")

        val allItems = listOf(homeItem) + featureItems

        // ── NavBar ─────────────────────────────────────────────────────────────
        val navBar = NavBar(allItems, settingsItem) { id ->
            cardLayout.show(contentArea, id)
        }

        // Show home by default
        navBar.select(HOME_ID)
        cardLayout.show(contentArea, HOME_ID)

        add(navBar,      BorderLayout.WEST)
        add(contentArea, BorderLayout.CENTER)
    }

    companion object {
        const val HOME_ID     = "home"
        const val SETTINGS_ID = "settings"
    }
}
