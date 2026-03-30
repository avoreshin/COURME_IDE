package changelogai.feature.spec.ui

import changelogai.core.confluence.ConfluenceContext
import changelogai.core.confluence.ConfluenceFetcher
import changelogai.feature.gigacodeae.mcp.McpService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal class ConfluenceLoadingController(private val project: Project, private val rootPanel: () -> JPanel) {

    val urlField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "URL страницы Confluence с готовым ТЗ"
        toolTipText = "Вставьте ссылку на страницу Confluence с готовым ТЗ для валидации"
    }
    val statusLabel = JBLabel("").apply {
        font = Font(Font.SANS_SERIF, Font.ITALIC, JBUI.scale(11))
        foreground = JBColor.GRAY
    }
    val reconnectBtn = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Переподключить MCP-серверы"
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
    }

    val confluenceRow: JPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
        isOpaque = false
        isVisible = false
        add(urlField, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    /** Nullable context — last successfully loaded page, or null */
    var currentCtx: ConfluenceContext? = null
        private set

    /** Fired when user clicks "detect existing spec → switch to validation" */
    var onSwitchToValidation: ((ConfluenceContext) -> Unit)? = null

    init {
        reconnectBtn.addActionListener {
            reconnectBtn.isEnabled = false
            McpService.getInstance(project).reconnect { msg ->
                SwingUtilities.invokeLater {
                    setStatus(msg, JBColor.GRAY)
                    reconnectBtn.isEnabled = true
                }
            }
        }
    }

    fun setStatus(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }

    fun reset() {
        urlField.text = ""
        currentCtx = null
        setStatus("", JBColor.GRAY)
    }

    /** Fetch Confluence asynchronously; calls [onResult] on EDT with the result. */
    fun fetchAsync(urlText: String, onResult: (ConfluenceFetcher.FetchResult) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = ConfluenceFetcher.fetch(urlText)
            SwingUtilities.invokeLater { onResult(result) }
        }
    }

    /**
     * Handles a [ConfluenceFetcher.FetchResult]:
     * - On success: stores ctx, updates status, optionally shows "switch to validation?" dialog.
     * - On no-credentials: calls [onSuccess] with null.
     * - On error / empty: shows error, nulls context.
     *
     * [isGenerateMode] controls whether the "looks like existing spec" dialog is shown.
     */
    fun handleFetchResult(
        result: ConfluenceFetcher.FetchResult,
        isGenerateMode: Boolean,
        onSuccess: (ConfluenceContext?) -> Unit
    ) {
        when (result) {
            is ConfluenceFetcher.FetchResult.Success -> {
                currentCtx = result.ctx
                setStatus("✓ ${result.ctx.pageTitle}", JBColor(Color(34, 139, 68), Color(72, 199, 116)))
                if (isGenerateMode && result.ctx.looksLikeExistingSpec()) {
                    val choice = JOptionPane.showOptionDialog(
                        rootPanel(),
                        "Страница \"${result.ctx.pageTitle}\" похожа на готовое ТЗ.\nПереключиться в режим «Валидация»?",
                        "Detected existing spec", JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE, null,
                        arrayOf("Валидировать ТЗ", "Продолжить генерацию"), "Валидировать ТЗ"
                    )
                    if (choice == 0) {
                        onSwitchToValidation?.invoke(result.ctx)
                        return
                    }
                }
                onSuccess(result.ctx)
            }
            is ConfluenceFetcher.FetchResult.NoCredentials -> {
                setStatus("⚠ Confluence не настроен в MCP Settings", JBColor(Color(180, 120, 0), Color(220, 180, 50)))
                currentCtx = null
                onSuccess(null)
            }
            is ConfluenceFetcher.FetchResult.Error -> {
                setStatus("✗ ${result.message}", JBColor.RED)
                JOptionPane.showMessageDialog(rootPanel(), result.message, "Ошибка Confluence", JOptionPane.ERROR_MESSAGE)
                currentCtx = null
            }
            ConfluenceFetcher.FetchResult.EmptyPage -> {
                setStatus("⚠ Страница пустая", JBColor.GRAY)
                currentCtx = null
            }
        }
    }
}
