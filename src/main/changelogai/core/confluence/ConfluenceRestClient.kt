package changelogai.core.confluence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.MultipartEntityBuilder
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
 * HTTP-клиент для Confluence REST API.
 * TLS-логика скопирована из McpClient (NoopHostnameVerifier + TrustAll / custom CA).
 *
 * @param baseUrl    Базовый URL: "https://company.atlassian.net/wiki" или "https://confluence.company.ru"
 * @param token      Personal Access Token для Bearer-авторизации
 * @param skipTls    Пропустить проверку сертификата (для корпоративных окружений)
 * @param cert       Путь к .pem-файлу или base64-строка с CA-сертификатом
 */
class ConfluenceRestClient(
    private val baseUrl: String,
    private val token: String,
    private val skipTls: Boolean = false,
    private val cert: String? = null
) {

    data class PageData(
        val id: String,
        val title: String,
        val storageBody: String,    // Confluence Storage Format XML
        val webUrl: String
    )

    private val mapper = jacksonObjectMapper()
    private val httpClient: CloseableHttpClient by lazy { buildHttpClient() }

    private val requestConfig = RequestConfig.custom()
        .setConnectTimeout(15_000)
        .setSocketTimeout(30_000)
        .build()

    /**
     * Загружает содержимое страницы по pageId.
     * Пробует два базовых пути (с /wiki и без) для совместимости с on-prem.
     */
    fun fetchPage(pageId: String): PageData {
        val paths = listOf(
            "$baseUrl/rest/api/content/$pageId?expand=body.storage",
            "${baseUrl.removeSuffix("/wiki")}/wiki/rest/api/content/$pageId?expand=body.storage"
        )
        var lastError: Exception? = null
        for (url in paths) {
            try {
                val json = getJson(url) ?: continue
                return parsePageData(json)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("Не удалось загрузить страницу $pageId")
    }

    /**
     * Резолвит short link, следуя redirect и извлекая pageId из Location-заголовка.
     * Возвращает числовой pageId или null если резолвинг не удался.
     */
    fun resolveShortLink(shortKey: String): String? {
        val shortUrl = "$baseUrl/x/$shortKey"
        val noRedirectClient = HttpClients.custom()
            .apply {
                if (skipTls || cert != null) {
                    setSSLContext(buildSslContext())
                    if (skipTls) setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                }
                setDefaultRequestConfig(RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setConnectTimeout(10_000)
                    .setSocketTimeout(10_000)
                    .build())
            }.build()

        return try {
            noRedirectClient.execute(HttpGet(shortUrl)).use { response ->
                val location = response.getFirstHeader("Location")?.value ?: return null
                // Cloud URL: /wiki/spaces/SPACE/pages/12345/...
                Regex("/pages/(\\d+)").find(location)?.groupValues?.get(1)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { noRedirectClient.close() }
        }
    }

    /**
     * Загружает файл как вложение (attachment) к странице Confluence.
     * POST /rest/api/content/{pageId}/child/attachment
     */
    fun uploadAttachment(pageId: String, filename: String, content: ByteArray): Boolean {
        val url = "$baseUrl/rest/api/content/$pageId/child/attachment"
        val entity = MultipartEntityBuilder.create()
            .addBinaryBody("file", content, ContentType.TEXT_PLAIN, filename)
            .addTextBody("comment", "Сгенерировано AI-OAssist")
            .build()
        val post = HttpPost(url).apply {
            this.entity = entity
            if (token.isNotBlank()) setHeader("Authorization", "Bearer $token")
            setHeader("X-Atlassian-Token", "no-check")
        }
        return try {
            httpClient.execute(post).use { response ->
                response.statusLine.statusCode in 200..299
            }
        } catch (_: Exception) { false }
    }

    private fun getJson(url: String): Map<String, Any>? {
        val get = HttpGet(url).apply {
            config = requestConfig
            if (token.isNotBlank()) setHeader("Authorization", "Bearer $token")
            setHeader("Accept", "application/json")
        }
        return httpClient.execute(get).use { response ->
            val status = response.statusLine.statusCode
            if (status == 404) return null
            if (status !in 200..299) {
                val body = response.entity?.content?.bufferedReader()?.readText() ?: ""
                error("Confluence вернул $status: $body")
            }
            val body = response.entity.content.bufferedReader().readText()
            if (body.trimStart().startsWith("<")) {
                error("Confluence вернул HTML вместо JSON — проверьте токен авторизации и URL")
            }
            mapper.readValue<Map<String, Any>>(body)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePageData(json: Map<String, Any>): PageData {
        val id = json["id"]?.toString() ?: ""
        val title = json["title"]?.toString() ?: "Без заголовка"
        val body = json["body"] as? Map<*, *>
        val storage = body?.get("storage") as? Map<*, *>
        val storageValue = storage?.get("value")?.toString() ?: ""
        val links = json["_links"] as? Map<*, *>
        val webui = links?.get("webui")?.toString() ?: ""
        val self = links?.get("self")?.toString() ?: ""
        val webUrl = when {
            webui.isNotBlank() -> {
                if (webui.startsWith("http")) webui
                else baseUrl.substringBefore("/wiki") + webui
            }
            else -> self
        }
        return PageData(id = id, title = title, storageBody = storageValue, webUrl = webUrl)
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
            setCertificateEntry("confluence-ca", x509)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
