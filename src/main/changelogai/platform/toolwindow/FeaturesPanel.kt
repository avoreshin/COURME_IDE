package changelogai.platform.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import changelogai.core.feature.FeatureToggleState
import changelogai.platform.FeatureRegistry
import changelogai.platform.PluginConstants
import java.awt.Font
import javax.swing.*
import javax.swing.border.TitledBorder

class FeaturesPanel(private val project: Project) {

    val panel: JPanel = buildPanel()

    private fun buildPanel(): JPanel {
        val toggleState = FeatureToggleState.getInstance()
        val features = FeatureRegistry.getInstance().getAll()

        val formBuilder = FormBuilder.createFormBuilder()

        if (features.isEmpty()) {
            formBuilder.addComponent(JLabel("No features registered."))
        } else {
            features.forEach { feature ->
                val checkbox = JCheckBox(feature.name, toggleState.isEnabled(feature))
                checkbox.toolTipText = feature.description
                checkbox.addActionListener {
                    toggleState.setEnabled(feature.id, checkbox.isSelected)
                    rebuildToolWindow()
                }
                val descLabel = JLabel(feature.description).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = font.deriveFont(Font.PLAIN, font.size - 1f)
                }
                formBuilder.addComponent(checkbox)
                formBuilder.addComponent(descLabel)
                formBuilder.addVerticalGap(4)
            }
        }

        val inner = formBuilder.panel
        val titledBorder = BorderFactory.createTitledBorder(
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 1, 3, 1),
                JBUI.Borders.empty(8)
            ),
            "Features"
        ) as TitledBorder
        titledBorder.titleFont = titledBorder.titleFont.deriveFont(Font.BOLD)
        titledBorder.titleColor = UIUtil.getLabelForeground()
        inner.border = titledBorder
        return inner
    }

    private fun rebuildToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(PluginConstants.TOOL_WINDOW_ID) ?: return
        val contentFactory = ContentFactory.getInstance()
        val contentManager = toolWindow.contentManager

        // Сохраняем Settings таб (всегда последний)
        val settingsContent = contentManager.contents.lastOrNull()

        contentManager.removeAllContents(false)

        FeatureRegistry.getInstance().getAll()
            .filter { FeatureToggleState.getInstance().isEnabled(it) }
            .forEach { feature ->
                val content = contentFactory.createContent(feature.createTab(project), feature.name, false)
                contentManager.addContent(content)
            }

        if (settingsContent != null) {
            settingsContent.icon = AllIcons.General.GearPlain
            contentManager.addContent(settingsContent)
            contentManager.setSelectedContent(settingsContent)
        }
    }
}
