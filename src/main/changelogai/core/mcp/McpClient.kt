package changelogai.core.mcp

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Минимальный MCP-клиент, реализующий JSON-RPC 2.0 поверх STDIO или HTTP.
 *
 * Поддерживаемые вызовы:
 *   - initialize   — хэндшейк с MCP-сервером
 *   - tools/list   — получить список инструментов
 *   - tools/call   — вызвать инструмент
 */
class McpClient(private val config: McpServerConfig) : AutoCloseable {

    /** Имя сервера — используется для поиска клиента по имени из McpEntry */
    val name: String get() = config.name


    private val mapper = jacksonObjectMapper()
    private val idGen = AtomicLong(1)

    // STDIO поля
    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    // Отдельный поток непрерывно читает stdout и кладёт строки в очередь.
    // callStdio читает из очереди с таймаутом — не блокирует навсегда.
    private val lineQueue = LinkedBlockingQueue<String>(2000)
    private var readerThread: Thread? = null

    // Lazy HTTP client c поддержкой TLS и Bearer-токена
    private val httpClient: CloseableHttpClient by lazy { buildHttpClient() }

    fun connect() {
        when (config.type) {
            McpTransportType.STDIO -> connectStdio()
            McpTransportType.HTTP -> { /* HTTP stateless — соединение не нужно */ }
        }
    }

    private fun connectStdio() {
        val cmd = buildList {
            add(config.command ?: error("STDIO MCP требует command"))
            addAll(config.args)
        }
        val pb = ProcessBuilder(cmd).apply {
            environment().putAll(config.env)
            redirectErrorStream(false)
        }
        val proc = pb.start()
        process = proc

        Thread.sleep(300) // дадим время упасть, если команда кривая
        if (!proc.isAlive) {
            val err = proc.errorStream.bufferedReader().readText()
            error("Упал при запуске (код ${proc.exitValue()}): $err")
        }

        writer = PrintWriter(proc.outputStream, true)
        reader = BufferedReader(InputStreamReader(proc.inputStream))
        startReaderThread()

        // initialize (request — ждём ответ)
        call("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "GigaCodeAE", "version" to "1.0")
        ))
        // notifications/initialized — уведомление без id, ответа нет
        notify("notifications/initialized", emptyMap<String, Any>())
    }

    fun listTools(): List<McpToolInfo> {
        val result = call("tools/list", emptyMap<String, Any>())
            ?: error("Не получен ответ на tools/list")
        val tools = (result["tools"] as? List<*>) ?: return emptyList()
        return tools.mapNotNull { item ->
            if (item is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val schema = item["inputSchema"] as? Map<String, Any> ?: emptyMap()
                McpToolInfo(
                    name = "${config.name}__${item["name"]}",
                    description = item["description"]?.toString() ?: "",
                    inputSchema = schema
                )
            } else null
        }
    }

    fun callTool(toolName: String, arguments: Map<String, Any>): String {
        val actualName = toolName.removePrefix("${config.name}__")
        val result = call("tools/call", mapOf(
            "name" to actualName,
            "arguments" to arguments
        )) ?: return "MCP tool call вернул null"

        return try {
            val content = result["content"] as? List<*>
            content?.joinToString("\n") { item ->
                if (item is Map<*, *>) item["text"]?.toString() ?: "" else ""
            } ?: result.toString()
        } catch (_: Exception) { result.toString() }
    }

    private fun call(method: String, params: Any): Map<String, Any>? {
        val id = idGen.getAndIncrement()
        val request = mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to params
        )
        val json = mapper.writeValueAsString(request)
        return when (config.type) {
            McpTransportType.STDIO -> callStdio(json, id)
            McpTransportType.HTTP -> callHttp(json)
        }
    }

    /** Запускает фоновый поток, непрерывно читающий stdout MCP-процесса. */
    private fun startReaderThread() {
        lineQueue.clear()
        readerThread?.interrupt()
        readerThread = Thread {
            try {
                val r = reader ?: return@Thread
                while (!Thread.currentThread().isInterrupted) {
                    val line = r.readLine() ?: break   // null = EOF, процесс завершился
                    if (line.isNotBlank()) lineQueue.offer(line)
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun callStdio(json: String, id: Long): Map<String, Any>? {
        val p = process ?: error("Процесс не запущен")
        if (!p.isAlive) {
            val err = p.errorStream.bufferedReader().readText()
            error("MCP процесс мертв (код ${p.exitValue()}). Ошибка:\n$err")
        }
        val w = writer ?: error("Writer is null")
        w.println(json)

        repeat(200) {
            val line = lineQueue.poll(15, TimeUnit.SECONDS)
            if (line == null) {
                if (!p.isAlive) {
                    val err = p.errorStream.bufferedReader().readText()
                    error("Процесс умер при ожидании. Ошибка:\n$err")
                }
                error("Таймаут чтения MCP. Ошибка stderr:\n${p.errorStream.bufferedReader().readText()}")
            }
            try {
                val resp: Map<String, Any> = mapper.readValue(line)
                if (resp.containsKey("error")) {
                    error("Ошибка сервера: ${resp["error"]}")
                }
                if (resp["id"]?.toString() == id.toString()) {
                    return resp["result"] as? Map<String, Any>
                }
            } catch (_: Exception) {}
        }
        error("Превышено время ожидания ответа")
    }

    /** Отправляет JSON-RPC уведомление (без id, ответа нет) */
    private fun notify(method: String, params: Any) {
        val notification = mapOf("jsonrpc" to "2.0", "method" to method, "params" to params)
        val json = mapper.writeValueAsString(notification)
        when (config.type) {
            McpTransportType.STDIO -> writer?.println(json)
            McpTransportType.HTTP  -> runCatching { callHttp(json) }
        }
    }

    // ── HTTP с авторизацией и TLS ──────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun callHttp(json: String): Map<String, Any>? {
        val url = config.url ?: return null
        val token = config.accessToken
        return try {
            val post = HttpPost(url).apply {
                entity = StringEntity(json, ContentType.APPLICATION_JSON)
                if (!token.isNullOrBlank()) {
                    setHeader("Authorization", "Bearer $token")
                }
            }
            httpClient.execute(post).use { response ->
                val body = response.entity.content.bufferedReader().readText()
                @Suppress("UNCHECKED_CAST")
                val resp: Map<String, Any> = mapper.readValue(body)
                resp["result"] as? Map<String, Any>
            }
        } catch (_: Exception) { null }
    }

    private fun buildHttpClient(): CloseableHttpClient {
        val builder = HttpClients.custom()
        if (config.skipCertVerify || config.certificate != null) {
            builder.setSSLContext(buildSslContext())
            if (config.skipCertVerify) {
                builder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
            }
        }
        return builder.build()
    }

    /**
     * SSL-контекст: либо «доверять всем» (skipCertVerify),
     * либо кастомный CA-сертификат (.pem-файл или base64-строка).
     */
    private fun buildSslContext(): SSLContext {
        if (config.skipCertVerify) {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            })
            return SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        }

        // Пользовательский CA-сертификат
        val certData = config.certificate!!
        val certInput = if (File(certData).exists()) {
            File(certData).inputStream()
        } else {
            ByteArrayInputStream(Base64.getDecoder().decode(certData))
        }
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(certInput)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("mcp-ca", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    override fun close() {
        readerThread?.interrupt()
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        process?.let { proc ->
            proc.destroy()
            // Если не завершился за 2 сек — принудительно (SIGKILL)
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        try { httpClient.close() } catch (_: Exception) {}
    }
}

data class McpToolInfo(
    val name: String,          // "${serverName}__${toolName}" — уникальное имя
    val description: String,
    val inputSchema: Map<String, Any>
)
