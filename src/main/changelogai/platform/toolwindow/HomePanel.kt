package changelogai.platform.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.font.TextAttribute
import javax.swing.*

/**
 * Домашняя страница плагина — стиль Gemini CLI: "> GIGACOURME" крупным шрифтом на тёмном фоне.
 */
class HomePanel {

    val panel: JPanel = build()

    private fun build(): JPanel {
        return object : JPanel() {
            init {
                isOpaque = true
                background = BG
                layout = GridBagLayout()
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

                val baseFontSize = (height * 0.13f).coerceIn(32f, 80f)

                // "> GIGACOURME" — одной строкой, разные цвета
                val boldFont = deriveFont(baseFontSize)

                // Рассчитываем ширину строки целиком для центрирования
                g2.font = boldFont
                val fm = g2.fontMetrics
                val prompt    = "> "
                val title     = "GIGACOURME"
                val totalW    = fm.stringWidth(prompt + title)
                val textX     = (width - totalW) / 2
                val textY     = height / 2 - (fm.height * 0.6).toInt()

                // "> " — тусклый цвет
                g2.color = PROMPT_COLOR
                g2.drawString(prompt, textX, textY)

                // "GIGACOURME" — яркий циановый
                g2.color = TITLE_COLOR
                g2.drawString(title, textX + fm.stringWidth(prompt), textY)

                // Subtitle
                val subFont = deriveFont(baseFontSize * 0.22f)
                g2.font = subFont
                g2.color = SUBTITLE_COLOR
                val sub = "AI-powered development assistant"
                val subW = g2.fontMetrics.stringWidth(sub)
                g2.drawString(sub, (width - subW) / 2, textY + fm.height * 1)

                // Tips
                val tipFont = deriveFont(baseFontSize * 0.18f)
                g2.font = tipFont
                g2.color = TIP_COLOR
                val tips = listOf(
                    "Ask questions, edit files, or run commands.",
                    "Be specific for the best results.",
                    "Use /help for more information."
                )
                val tipFm = g2.fontMetrics
                val tipStartY = textY + fm.height * 1 + tipFm.height * 2
                tips.forEachIndexed { i, tip ->
                    val tw = tipFm.stringWidth(tip)
                    g2.drawString(tip, (width - tw) / 2, tipStartY + i * (tipFm.height + JBUI.scale(3)))
                }
            }

            private fun deriveFont(size: Float): Font {
                val base = Font(Font.SANS_SERIF, Font.BOLD, size.toInt())
                @Suppress("UNCHECKED_CAST")
                val attrs = base.attributes as MutableMap<TextAttribute, Any?>
                attrs[TextAttribute.WEIGHT] = TextAttribute.WEIGHT_EXTRABOLD
                attrs[TextAttribute.SIZE]   = size
                return Font.getFont(attrs) ?: base
            }
        }
    }

    companion object {
        private val BG             = JBColor(Color(0x0C0D10), Color(0x0C0D10))
        private val TITLE_COLOR    = JBColor(Color(0x4EC9B0), Color(0x4EC9B0))  // cyan
        private val PROMPT_COLOR   = JBColor(Color(0x2A8C7A), Color(0x2A8C7A))  // dim cyan
        private val SUBTITLE_COLOR = JBColor(Color(0x6A8A99), Color(0x6A8A99))
        private val TIP_COLOR      = JBColor(Color(0x3A5060), Color(0x3A5060))
    }
}
