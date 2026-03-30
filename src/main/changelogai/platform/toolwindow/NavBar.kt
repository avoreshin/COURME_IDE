package changelogai.platform.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

data class NavItem(val id: String, val icon: Icon, val tooltip: String)

/**
 * Вертикальный навбар с иконками — аналог VS Code Activity Bar.
 * Settings-кнопка прижата к низу через Box.createVerticalGlue().
 */
class NavBar(
    private val items: List<NavItem>,
    private val settingsItem: NavItem,
    private val onSelect: (id: String) -> Unit
) : JPanel() {

    private val buttons = mutableMapOf<String, JButton>()
    private var activeId: String? = null

    private val barWidth = JBUI.scale(36)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLineRight(JBColor.border())
        preferredSize = Dimension(barWidth, 0)
        minimumSize = Dimension(barWidth, 0)
        maximumSize = Dimension(barWidth, Int.MAX_VALUE)

        items.forEach { addButton(it) }

        add(Box.createVerticalGlue())

        addButton(settingsItem)
    }

    private fun addButton(item: NavItem) {
        val btn = createNavButton(item)
        buttons[item.id] = btn
        add(btn)
    }

    private fun createNavButton(item: NavItem): JButton {
        return object : JButton(item.icon) {
            private var hovered = false

            init {
                toolTipText = item.tooltip
                isFocusPainted = false
                isBorderPainted = false
                isContentAreaFilled = false
                isOpaque = false
                preferredSize = Dimension(barWidth, barWidth)
                minimumSize = Dimension(barWidth, barWidth)
                maximumSize = Dimension(barWidth, barWidth)
                alignmentX = CENTER_ALIGNMENT
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent)  { hovered = false; repaint() }
                })

                addActionListener {
                    select(item.id)
                    onSelect(item.id)
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val isActive = activeId == item.id

                when {
                    isActive -> g2.color = ACTIVE_BG
                    hovered  -> g2.color = HOVER_BG
                    else     -> g2.color = UIUtil.getPanelBackground()
                }
                g2.fillRect(0, 0, width, height)

                // Active indicator — thin left bar
                if (isActive) {
                    g2.color = ACCENT_COLOR
                    g2.fillRect(0, 0, JBUI.scale(2), height)
                }

                super.paintComponent(g)
            }
        }
    }

    fun select(id: String) {
        activeId = id
        buttons.values.forEach { it.repaint() }
    }

    companion object {
        private val ACTIVE_BG    = JBColor(Color(0x1E2D3D), Color(0x2A3A4A))
        private val HOVER_BG     = JBColor(Color(0x1A252F), Color(0x243040))
        private val ACCENT_COLOR = JBColor(Color(0x4EC9B0), Color(0x4EC9B0))
    }
}
