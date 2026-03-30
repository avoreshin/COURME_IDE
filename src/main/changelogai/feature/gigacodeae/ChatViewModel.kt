package changelogai.feature.gigacodeae

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import changelogai.core.llm.cancellation.AtomicCancelable
import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.FunctionCall
import changelogai.core.llm.model.FunctionDefinition
import changelogai.feature.gigacodeae.mcp.McpClient
import changelogai.feature.gigacodeae.mcp.McpConfigReader
import changelogai.feature.gigacodeae.mcp.McpToolAdapter
import changelogai.feature.gigacodeae.orchestrator.AgentLogEvent
import changelogai.feature.gigacodeae.orchestrator.ContextCompressor
import changelogai.feature.gigacodeae.orchestrator.MainOrchestrator
import changelogai.feature.gigacodeae.orchestrator.OrchestratorMode
import changelogai.feature.gigacodeae.skill.SkillDefinition
import changelogai.feature.gigacodeae.skill.SkillState
import changelogai.feature.gigacodeae.tools.ToolDispatcher
import changelogai.feature.gigacodeae.tools.ToolResult
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

class ChatViewModel(private val project: Project) {

    private val orchestrator = MainOrchestrator(project, ToolDispatcher(project, ::confirmTool))
    private val contextCompressor = ContextCompressor()
    private val repository = ChatSessionRepository.getInstance(project)
    // MCP — делегируем в McpService (единый project-level кеш)
    private val mcpService get() = changelogai.feature.gigacodeae.mcp.McpService.getInstance(project)
    private val mcpClients get() = mcpService.clients
    private val mcpFunctions get() = mcpService.functions

    @Volatile var onMcpStatusChanged: ((String) -> Unit)? = null

    var currentSkill: SkillDefinition = SkillState.getInstance().allSkills().firstOrNull()
        ?: SkillDefinition.defaults().first()

    var orchestratorMode: OrchestratorMode = OrchestratorMode.AUTO

    private var currentSession: ChatSession = newSession()
    private var cancelable: AtomicCancelable? = null

    // UI callbacks (читаются из BGT → EDT через invokeLater, пишутся из EDT)
    @Volatile var onMessageAdded: ((UiMessage) -> Unit)? = null
    @Volatile var onToolCallCard: ((UiMessage) -> Unit)? = null
    @Volatile var onTypingChanged: ((Boolean) -> Unit)? = null
    @Volatile var onStatusUpdate: ((String) -> Unit)? = null  // живой статус "вызываю X…"
    @Volatile var onError: ((String) -> Unit)? = null
    @Volatile var onSessionsChanged: (() -> Unit)? = null
    @Volatile var onAgentLog: ((AgentLogEvent) -> Unit)? = null

    val sessions: List<ChatSession> get() = repository.sessions
    val activeSession: ChatSession get() = currentSession

    fun send(text: String) {
        if (text.isBlank()) return
        cancelable?.cancel()
        val cancel = AtomicCancelable().also { cancelable = it }

        val userMsg = UiMessage(role = "user", content = text)
        SwingUtilities.invokeLater { onMessageAdded?.invoke(userMsg) }

        val llmText = resolveAtRefs(text)
        val history = currentSession.messages.toList()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "GigaCodeAE…", true) {
            override fun run(indicator: ProgressIndicator) {
                SwingUtilities.invokeLater {
                    onTypingChanged?.invoke(true)
                    onStatusUpdate?.invoke("думаю…")
                }

                val toolCallStartTimes = mutableMapOf<String, Long>()

                orchestrator.sendMessage(
                    history = history,
                    userText = llmText,
                    extraFunctions = mcpFunctions,
                    cancelable = cancel,
                    systemPrompt = currentSkill.systemPrompt,
                    currentSkill = currentSkill,
                    sessionSummary = currentSession.conversationSummary,
                    onSummaryUpdated = { summary -> currentSession.conversationSummary = summary },
                    onToolCallStarted = { call ->
                        toolCallStartTimes[call.name] = System.currentTimeMillis()
                        SwingUtilities.invokeLater { onStatusUpdate?.invoke("⚙ вызываю ${call.name}…") }
                    },
                    onToolCallResult = { call, result ->
                        val ms = System.currentTimeMillis() - (toolCallStartTimes[call.name] ?: 0)
                        val msg = UiMessage(
                            role = "tool_call",
                            content = call.name,
                            functionCall = call,
                            toolResult = result,
                            durationMs = ms
                        )
                        SwingUtilities.invokeLater {
                            onStatusUpdate?.invoke("думаю…")
                            onToolCallCard?.invoke(msg)
                        }
                    },
                    onAssistantMessage = { text ->
                        val msg = UiMessage(role = "assistant", content = text)
                        SwingUtilities.invokeLater { onMessageAdded?.invoke(msg) }
                    },
                    mcpDispatch = { name, args -> dispatchMcpTool(name, args) },
                    onDone = { updatedMessages ->
                        currentSession.messages.clear()
                        currentSession.messages.addAll(updatedMessages)
                        currentSession.autoTitle()
                        repository.update(currentSession)
                        SwingUtilities.invokeLater {
                            onTypingChanged?.invoke(false)
                            onStatusUpdate?.invoke("")
                            onSessionsChanged?.invoke()
                        }
                    },
                    onError = { msg ->
                        SwingUtilities.invokeLater {
                            onTypingChanged?.invoke(false)
                            onStatusUpdate?.invoke("")
                            onError?.invoke(msg)
                        }
                    },
                    mode = orchestratorMode,
                    onAgentLog = { event ->
                        SwingUtilities.invokeLater { onAgentLog?.invoke(event) }
                    }
                )
            }

            override fun onCancel() {
                cancel.cancel()
                SwingUtilities.invokeLater {
                    onTypingChanged?.invoke(false)
                    onStatusUpdate?.invoke("")
                }
            }
        })
    }

    fun stop() {
        cancelable?.cancel()
    }

    fun reconnectMcp() {
        mcpService.reconnect { msg -> SwingUtilities.invokeLater { onMcpStatusChanged?.invoke(msg) } }
    }

    fun newSession(): ChatSession {
        val session = ChatSession()
        currentSession = session
        repository.add(session)
        SwingUtilities.invokeLater { onSessionsChanged?.invoke() }
        return session
    }

    fun loadSession(id: String) {
        val session = repository.sessions.firstOrNull { it.id == id } ?: return
        currentSession = session
    }

    fun deleteSession(id: String) {
        repository.delete(id)
        if (currentSession.id == id) newSession()
        SwingUtilities.invokeLater { onSessionsChanged?.invoke() }
    }

    /**
     * Показывает inline-карточку подтверждения в чате и блокирует фоновый поток до ответа.
     * Вызывается из ToolDispatcher вместо JOptionPane.
     */
    fun confirmTool(toolName: String, args: Map<String, Any>): Boolean {
        val future = CompletableFuture<Boolean>()
        val argsText = args.entries.joinToString("\n") { (k, v) ->
            val s = v.toString()
            "  $k: ${if (s.length > 120) s.take(117) + "…" else s}"
        }
        val msg = UiMessage(
            role = "confirm",
            content = "**$toolName**\n$argsText",
            onApprove = { future.complete(true) },
            onDeny = { future.complete(false) }
        )
        SwingUtilities.invokeLater { onToolCallCard?.invoke(msg) }
        return future.get()
    }

    /**
     * Вызывает MCP-инструмент по имени (санитизированному).
     * Используется из ChatOrchestrator когда имя функции соответствует MCP-инструменту.
     */
    fun dispatchMcpTool(functionName: String, arguments: Map<String, Any>): String {
        for (client in mcpClients) {
            val tools = client.listTools()
            val match = tools.firstOrNull {
                McpToolAdapter.toFunctionDefinition(it).name == functionName
            } ?: continue
            return client.callTool(match.name, arguments)
        }
        return "MCP tool не найден: $functionName"
    }

    /**
     * Заменяет @path/to/File.kt в тексте на содержимое файла, добавляя его как контекст перед запросом.
     */
    private fun resolveAtRefs(text: String): String {
        val basePath = project.basePath ?: return text
        val injections = mutableListOf<String>()
        Regex("@([\\w./\\-]+)").findAll(text).forEach { mr ->
            val relPath = mr.groupValues[1]
            val file = File(basePath, relPath)
            if (file.exists() && file.isFile) {
                val content = contextCompressor.chunkFileContent(file.readText(), text)
                injections.add("Содержимое файла `$relPath`:\n```\n$content\n```")
            }
        }
        return if (injections.isEmpty()) text
        else injections.joinToString("\n\n") + "\n\n" + text
    }

}

data class UiMessage(
    val role: String,            // "user" | "assistant" | "tool_call" | "confirm"
    val content: String,
    val functionCall: FunctionCall? = null,
    val toolResult: ToolResult? = null,
    val durationMs: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val onApprove: (() -> Unit)? = null,  // для role="confirm"
    val onDeny: (() -> Unit)? = null      // для role="confirm"
)

fun ChatMessage.toUiMessage() = UiMessage(
    role = role,
    content = content ?: functionCall?.let { "→ ${it.name}" } ?: "",
    functionCall = functionCall
)
