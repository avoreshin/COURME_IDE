package changelogai.feature.gigacodeae.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import changelogai.core.llm.model.FunctionDefinition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Project-level сервис для управления MCP-клиентами.
 * Единый кеш клиентов, шарится между GigaCode и Spec Generator.
 */
@Service(Service.Level.PROJECT)
class McpService(private val project: Project) {

    @Volatile private var clientsCache: List<McpClient>? = null
    @Volatile private var functionsCache: List<FunctionDefinition>? = null
    private val lock = Any()

    val clients: List<McpClient>
        get() = clientsCache ?: synchronized(lock) {
            clientsCache ?: buildClients().also { clientsCache = it }
        }

    val functions: List<FunctionDefinition>
        get() = functionsCache ?: synchronized(lock) {
            functionsCache ?: loadFunctions().also { functionsCache = it }
        }

    /** Переподключить все MCP-серверы (сбросить кеш и переинициализировать). */
    fun reconnect(onStatus: ((String) -> Unit)? = null) {
        onStatus?.invoke("Переподключение MCP…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "MCP: переподключение…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                synchronized(lock) {
                    clientsCache?.forEach { try { it.close() } catch (_: Exception) {} }
                    clientsCache = null
                    functionsCache = null
                }
                if (indicator.isCanceled) return
                val servers = McpConfigReader.readServers(project)
                val connected = mutableListOf<McpClient>()
                servers.forEachIndexed { i, cfg ->
                    if (indicator.isCanceled) return
                    indicator.text = "MCP: подключение ${cfg.name} (${i + 1}/${servers.size})…"
                    val client = connectWithTimeout(cfg, timeoutSec = 8)
                    if (client != null) connected.add(client)
                }
                synchronized(lock) { clientsCache = connected }
                if (indicator.isCanceled) return
                val f = functions
                val msg = when {
                    f.isNotEmpty()       -> "MCP: ${f.size} инструментов из ${connected.size} серверов"
                    connected.isNotEmpty() -> "MCP: серверы подключены, нет инструментов"
                    else                 -> "MCP: серверы не найдены"
                }
                onStatus?.invoke(msg)
            }
        })
    }

    private fun connectWithTimeout(cfg: McpServerConfig, timeoutSec: Long): McpClient? {
        var client: McpClient? = null
        val future = CompletableFuture.supplyAsync {
            McpClient(cfg).also { client = it; it.connect() }
        }
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            try { client?.close() } catch (_: Exception) {}  // убиваем дочерний процесс
            null
        } catch (_: Exception) {
            try { client?.close() } catch (_: Exception) {}
            null
        }
    }

    private fun buildClients(): List<McpClient> =
        McpConfigReader.readServers(project).mapNotNull { cfg ->
            connectWithTimeout(cfg, timeoutSec = 8)
        }

    private fun loadFunctions(): List<FunctionDefinition> =
        clients.flatMap { client ->
            try { client.listTools().map { McpToolAdapter.toFunctionDefinition(it) } }
            catch (_: Exception) { emptyList() }
        }

    companion object {
        fun getInstance(project: Project): McpService = project.service()
    }
}
