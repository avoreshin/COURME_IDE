package changelogai.feature.kb.confluence

import changelogai.core.settings.PluginState
import changelogai.core.mcp.McpEntry
import changelogai.core.mcp.McpState
import changelogai.core.confluence.ConfluenceContentParser
import changelogai.core.confluence.ConfluenceFetcher
import changelogai.core.confluence.ConfluenceUrlParser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Пакетная загрузка страниц из Confluence.
 * Поддерживает три режима: Space, Page Tree, Manual URLs.
 */
class ConfluenceBulkFetcher {

    data class PageWithContent(
        val id: String,
        val title: String,
        val plainText: String,
        val version: Int,
        val webUrl: String
    )

    sealed class BulkResult {
        data class Success(val pages: List<PageWithContent>) : BulkResult()
        data class NoCredentials(val detectedBaseUrl: String) : BulkResult()
        data class Error(val message: String) : BulkResult()
    }

    companion object {
        private const val PAGE_LIMIT = 25
        private const val MAX_PAGES = 500
    }

    private val mapper = jacksonObjectMapper()

    /**
     * Загружает все страницы из Confluence Space.
     */
    fun fetchSpace(
        spaceKey: String,
        baseUrl: String = "",
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): BulkResult {
        val (client, resolvedBaseUrl) = createClient(baseUrl) ?: return BulkResult.NoCredentials(baseUrl)
        return try {
            val pages = mutableListOf<PageWithContent>()
            var start = 0
            var total = Int.MAX_VALUE

            while (start < total && pages.size < MAX_PAGES) {
                val url = buildSpaceUrl(resolvedBaseUrl, spaceKey, start)
                val json = client.getJson(url) ?: break

                @Suppress("UNCHECKED_CAST")
                val results = json["results"] as? List<Map<String, Any>> ?: break
                total = (json["size"] as? Number)?.toInt()?.let {
                    // size — количество на текущей странице; total из _links
                    extractTotalFromLinks(json) ?: (start + results.size + 1)
                } ?: results.size

                for (pageMap in results) {
                    val page = parsePageWithContent(pageMap, resolvedBaseUrl) ?: continue
                    pages.add(page)
                    if (pages.size >= MAX_PAGES) break
                }

                onProgress?.invoke(pages.size, total.coerceAtMost(MAX_PAGES))
                start += PAGE_LIMIT
            }

            if (pages.isEmpty()) BulkResult.Error("Space $spaceKey не содержит страниц")
            else BulkResult.Success(pages)
        } catch (e: Exception) {
            BulkResult.Error("Ошибка загрузки Space $spaceKey: ${e.message}")
        }
    }

    /**
     * Загружает дерево страниц от корневой рекурсивно (BFS).
     */
    fun fetchPageTree(
        rootPageUrl: String,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): BulkResult {
        val parsed = ConfluenceUrlParser.parse(rootPageUrl)
            ?: return BulkResult.Error("Не удалось распознать URL: $rootPageUrl")

        val (client, resolvedBaseUrl) = createClient(parsed.baseUrl)
            ?: return BulkResult.NoCredentials(parsed.baseUrl)

        return try {
            val pages = mutableListOf<PageWithContent>()
            val queue = ArrayDeque<String>()
            queue.add(parsed.pageId)
            val visited = mutableSetOf<String>()

            // Загружаем корневую страницу
            val rootPage = fetchSinglePage(client, resolvedBaseUrl, parsed.pageId)
            if (rootPage != null) pages.add(rootPage)
            visited.add(parsed.pageId)

            while (queue.isNotEmpty() && pages.size < MAX_PAGES) {
                val parentId = queue.removeFirst()
                val children = fetchChildPages(client, resolvedBaseUrl, parentId)

                for (child in children) {
                    if (child.id in visited || pages.size >= MAX_PAGES) continue
                    visited.add(child.id)
                    pages.add(child)
                    queue.add(child.id)
                    onProgress?.invoke(pages.size, pages.size + queue.size)
                }
            }

            if (pages.isEmpty()) BulkResult.Error("Страница не найдена, пуста или недоступна (pageId=${parsed.pageId})")
            else BulkResult.Success(pages)
        } catch (e: Exception) {
            BulkResult.Error("Ошибка загрузки дерева страниц: ${e.message}")
        }
    }

    /**
     * Загружает отдельные страницы по списку URL.
     */
    fun fetchPages(
        urls: List<String>,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): BulkResult {
        val pages = mutableListOf<PageWithContent>()
        val errors = mutableListOf<String>()

        for ((index, url) in urls.withIndex()) {
            val result = ConfluenceFetcher.fetch(url)
            when (result) {
                is ConfluenceFetcher.FetchResult.Success -> {
                    pages.add(PageWithContent(
                        id = result.ctx.pageId,
                        title = result.ctx.pageTitle,
                        plainText = result.ctx.plainText,
                        version = 0,
                        webUrl = result.ctx.canonicalWebUrl
                    ))
                }
                is ConfluenceFetcher.FetchResult.NoCredentials ->
                    return BulkResult.NoCredentials(result.detectedBaseUrl)
                is ConfluenceFetcher.FetchResult.Error ->
                    errors.add("${url}: ${result.message}")
                is ConfluenceFetcher.FetchResult.EmptyPage ->
                    errors.add("${url}: пустая страница")
            }
            onProgress?.invoke(index + 1, urls.size)
        }

        return if (pages.isEmpty() && errors.isNotEmpty()) {
            BulkResult.Error("Не удалось загрузить страницы:\n${errors.joinToString("\n")}")
        } else {
            BulkResult.Success(pages)
        }
    }

    // ---- Internal helpers ----

    private data class ClientWithUrl(val client: BulkHttpClient, val baseUrl: String)

    private fun createClient(fallbackBaseUrl: String): ClientWithUrl? {
        val entry = findEntry(fallbackBaseUrl) ?: return null
        val (baseUrl, token) = resolveCredentials(entry, fallbackBaseUrl)
        if (baseUrl.isBlank()) return null

        val pluginCert = PluginState.getInstance().aiCertPath.takeIf { it.isNotBlank() }
        val client = BulkHttpClient(
            baseUrl = baseUrl,
            token = token,
            skipTls = entry.skipCertVerify,
            cert = entry.certificate.takeIf { it.isNotBlank() } ?: pluginCert
        )
        return ClientWithUrl(client, baseUrl)
    }

    /**
     * Ищет подходящий McpEntry — та же логика что в ConfluenceFetcher.findEntry():
     * 1. Atlassian preset + совпадение хоста
     * 2. Atlassian preset (любой)
     * 3. Любой enabled сервер с совпадением хоста
     * 4. Любой enabled сервер
     */
    private fun findEntry(baseUrl: String): McpEntry? {
        val servers = McpState.getInstance().servers.filter { it.enabled }
        if (servers.isEmpty()) return null

        val baseHost = baseUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore("/").lowercase()

        fun entryHost(e: McpEntry): String =
            (if (e.type == "HTTP") e.url else extractHostFromEnv(e))
                .removePrefix("https://").removePrefix("http://")
                .substringBefore("/").lowercase()

        servers.firstOrNull { (it.preset == "confluence" || it.preset == "jira") && entryHost(it) == baseHost }
            ?.let { return it }
        servers.firstOrNull { it.preset == "confluence" || it.preset == "jira" }
            ?.let { return it }
        servers.firstOrNull { entryHost(it) == baseHost }?.let { return it }
        return servers.first()
    }

    private fun resolveCredentials(entry: McpEntry, fallbackBaseUrl: String): Pair<String, String> {
        return if (entry.type == "HTTP") {
            val url = entry.url.ifBlank { fallbackBaseUrl }
            url to entry.accessToken
        } else {
            val env = parseEnvJson(entry.envJson)
            val url = (env["CONFLUENCE_URL"] ?: env["ATLASSIAN_URL"])
                ?.ifBlank { fallbackBaseUrl } ?: fallbackBaseUrl
            val token = env["CONFLUENCE_PERSONAL_TOKEN"]
                ?: env["ATLASSIAN_API_TOKEN"]
                ?: env["ATLASSIAN_TOKEN"]
                ?: entry.accessToken.ifBlank { "" }
            url to token
        }
    }

    private fun extractHostFromEnv(entry: McpEntry): String {
        val env = parseEnvJson(entry.envJson)
        return env["CONFLUENCE_URL"] ?: env["ATLASSIAN_URL"] ?: env["JIRA_URL"] ?: ""
    }

    private fun parseEnvJson(envJson: String): Map<String, String> {
        if (envJson.isBlank() || envJson == "{}") return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(envJson, Map::class.java)
                .entries.associate { it.key.toString() to it.value.toString() }
        } catch (_: Exception) { emptyMap() }
    }

    private fun buildSpaceUrl(baseUrl: String, spaceKey: String, start: Int): String {
        val base = baseUrl.trimEnd('/')
        return "$base/rest/api/content?spaceKey=$spaceKey&type=page&limit=$PAGE_LIMIT&start=$start&expand=body.storage,version"
    }

    private fun fetchSinglePage(client: BulkHttpClient, baseUrl: String, pageId: String): PageWithContent? {
        val url = "${baseUrl.trimEnd('/')}/rest/api/content/$pageId?expand=body.storage,version"
        val json = client.getJson(url) ?: return null
        return parsePageWithContent(json, baseUrl)
    }

    private fun fetchChildPages(client: BulkHttpClient, baseUrl: String, parentId: String): List<PageWithContent> {
        val pages = mutableListOf<PageWithContent>()
        var start = 0

        while (true) {
            val url = "${baseUrl.trimEnd('/')}/rest/api/content/$parentId/child/page?limit=$PAGE_LIMIT&start=$start&expand=body.storage,version"
            val json = client.getJson(url) ?: break

            @Suppress("UNCHECKED_CAST")
            val results = json["results"] as? List<Map<String, Any>> ?: break
            if (results.isEmpty()) break

            for (pageMap in results) {
                val page = parsePageWithContent(pageMap, baseUrl) ?: continue
                pages.add(page)
            }

            start += PAGE_LIMIT
            if (results.size < PAGE_LIMIT) break
        }

        return pages
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePageWithContent(json: Map<String, Any>, baseUrl: String): PageWithContent? {
        val id = json["id"]?.toString() ?: return null
        val title = json["title"]?.toString() ?: "Без заголовка"
        val body = json["body"] as? Map<*, *>
        val storage = body?.get("storage") as? Map<*, *>
        val storageValue = storage?.get("value")?.toString() ?: ""

        val versionMap = json["version"] as? Map<*, *>
        val version = (versionMap?.get("number") as? Number)?.toInt() ?: 0

        val links = json["_links"] as? Map<*, *>
        val webui = links?.get("webui")?.toString() ?: ""
        val webUrl = if (webui.startsWith("http")) webui
        else if (webui.isNotBlank()) baseUrl.substringBefore("/wiki") + webui
        else ""

        val plainText = try {
            ConfluenceContentParser.parse(storageValue).plainText
        } catch (_: Exception) { "" }

        if (plainText.isBlank()) return null

        return PageWithContent(
            id = id,
            title = title,
            plainText = plainText,
            version = version,
            webUrl = webUrl
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractTotalFromLinks(json: Map<String, Any>): Int? {
        // Confluence v1 API: totalSize for /rest/api/content
        return (json["totalSize"] as? Number)?.toInt()
    }

    // ---- HTTP Client (lightweight, reuses TLS pattern from ConfluenceRestClient) ----

    class BulkHttpClient(
        private val baseUrl: String,
        private val token: String,
        private val skipTls: Boolean = false,
        private val cert: String? = null
    ) {
        private val mapper = jacksonObjectMapper()
        private val httpClient: CloseableHttpClient by lazy { buildHttpClient() }
        // Fallback-клиент с отключённой проверкой TLS — создаётся только при PKIX-ошибке
        private val skipTlsClient: CloseableHttpClient by lazy {
            HttpClients.custom().apply {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                    override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                    override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                })
                val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
                setSSLContext(ctx)
                setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                setDefaultRequestConfig(requestConfig)
            }.build()
        }

        private val requestConfig = RequestConfig.custom()
            .setConnectTimeout(15_000)
            .setSocketTimeout(30_000)
            .build()

        fun getJson(url: String): Map<String, Any>? {
            // Пробуем с /wiki и без
            val urls = if ("/wiki/" in url) listOf(url)
            else listOf(url, url.replace("/rest/", "/wiki/rest/"))

            var lastHttpError: String? = null

            for (u in urls) {
                try {
                    val get = HttpGet(u).apply {
                        config = requestConfig
                        if (token.isNotBlank()) setHeader("Authorization", "Bearer $token")
                        setHeader("Accept", "application/json")
                    }
                    val result = httpClient.execute(get).use { response ->
                        val status = response.statusLine.statusCode
                        if (status == 404) return@use null
                        if (status !in 200..299) {
                            // HTTP-ошибки пробрасываем — не пробуем следующий URL
                            val body = response.entity?.content?.bufferedReader()?.readText() ?: ""
                            val hint = when (status) {
                                401 -> " (проверьте токен в настройках MCP)"
                                403 -> " (нет доступа к странице)"
                                else -> ""
                            }
                            error("Confluence вернул HTTP $status$hint")
                        }
                        val body = response.entity.content.bufferedReader().readText()
                        mapper.readValue<Map<String, Any>>(body)
                    }
                    if (result != null) return result
                } catch (e: java.net.ConnectException) {
                    lastHttpError = e.message  // сетевая ошибка — пробуем следующий URL
                } catch (e: javax.net.ssl.SSLException) {
                    if (!skipTls && e.message?.contains("PKIX", ignoreCase = true) == true) {
                        // PKIX: retry с отключённой проверкой TLS
                        return getJsonWithClient(skipTlsClient, u)
                    }
                    lastHttpError = e.message
                } catch (e: Exception) {
                    throw e  // HTTP-ошибки и прочие — пробрасываем
                }
            }
            if (lastHttpError != null) throw java.net.ConnectException(lastHttpError)
            return null
        }

        private fun getJsonWithClient(client: CloseableHttpClient, url: String): Map<String, Any>? {
            val get = HttpGet(url).apply {
                config = requestConfig
                if (token.isNotBlank()) setHeader("Authorization", "Bearer $token")
                setHeader("Accept", "application/json")
            }
            return client.execute(get).use { response ->
                val status = response.statusLine.statusCode
                if (status == 404) return null
                if (status !in 200..299) {
                    val hint = when (status) {
                        401 -> " (проверьте токен в настройках MCP)"
                        403 -> " (нет доступа к странице)"
                        else -> ""
                    }
                    error("Confluence вернул HTTP $status$hint")
                }
                val body = response.entity.content.bufferedReader().readText()
                mapper.readValue<Map<String, Any>>(body)
            }
        }

        private fun buildHttpClient(): CloseableHttpClient =
            HttpClients.custom().apply {
                if (skipTls || cert != null) {
                    setSSLContext(buildSslContext())
                    if (skipTls) setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                }
                setDefaultRequestConfig(requestConfig)
            }.build()

        private fun buildSslContext(): SSLContext {
            if (skipTls) {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                })
                return SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
            }
            val certData = cert ?: error("cert не задан")
            val certInput = if (File(certData).exists()) {
                File(certData).inputStream()
            } else {
                ByteArrayInputStream(Base64.getDecoder().decode(certData))
            }
            val cf = CertificateFactory.getInstance("X.509")
            val x509 = cf.generateCertificate(certInput)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("confluence-bulk-ca", x509)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        }
    }
}
