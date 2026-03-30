package changelogai.feature.kb.embedding

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
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
 * HTTP-клиент для OpenAI-совместимого Embeddings API.
 * По паттерну ConfluenceRestClient — отдельный HTTP-клиент с TLS-поддержкой.
 *
 * @param baseUrl  Базовый URL API (например, "https://api.openai.com/v1")
 * @param token    API key для Bearer-авторизации
 * @param model    Имя модели (например, "text-embedding-ada-002")
 * @param certPath Путь к CA-сертификату или base64-строка (для корпоративных сред)
 */
class EmbeddingClient(
    private val baseUrl: String,
    private val token: String,
    private val model: String,
    private val certPath: String? = null,
    private val skipTls: Boolean = false
) : AutoCloseable {

    companion object {
        private const val BATCH_SIZE = 10
    }

    private val mapper = jacksonObjectMapper()
    private val httpClient: CloseableHttpClient by lazy { buildHttpClient() }

    private val requestConfig = RequestConfig.custom()
        .setConnectTimeout(15_000)
        .setSocketTimeout(60_000)
        .build()

    /**
     * Генерирует эмбеддинги для списка текстов.
     * Автоматически разбивает на батчи по BATCH_SIZE.
     *
     * @param texts Список текстов для эмбеддинга
     * @param onProgress Callback: (processed, total)
     * @return Список FloatArray в том же порядке, что и входные тексты
     */
    fun embed(
        texts: List<String>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val allEmbeddings = mutableListOf<FloatArray>()
        val batches = texts.chunked(BATCH_SIZE)
        var processed = 0

        for (batch in batches) {
            val response = postEmbeddings(batch)
            val sorted = response.data.sortedBy { it.index }
            for (item in sorted) {
                allEmbeddings.add(item.embedding.toFloatArray())
            }
            processed += batch.size
            onProgress?.invoke(processed, texts.size)
        }

        return allEmbeddings
    }

    /**
     * Генерирует эмбеддинг для одного текста.
     */
    fun embedSingle(text: String): FloatArray {
        return embed(listOf(text)).first()
    }

    private fun postEmbeddings(texts: List<String>): EmbeddingResponse {
        val url = "${baseUrl.trimEnd('/')}/embeddings"
        val request = EmbeddingRequest(model = model, input = texts)
        val json = mapper.writeValueAsString(request)

        return try {
            executePost(httpClient, url, json)
        } catch (e: javax.net.ssl.SSLException) {
            if (!skipTls && e.message?.contains("PKIX", ignoreCase = true) == true) {
                // PKIX: retry с отключённой проверкой TLS
                executePost(buildSkipTlsClient(), url, json)
            } else throw e
        }
    }

    private fun executePost(client: CloseableHttpClient, url: String, json: String): EmbeddingResponse {
        val post = HttpPost(url).apply {
            config = requestConfig
            if (token.isNotBlank()) setHeader("Authorization", "Bearer $token")
            setHeader("Accept", "application/json")
            entity = StringEntity(json, ContentType.APPLICATION_JSON)
        }
        return client.execute(post).use { response ->
            val status = response.statusLine.statusCode
            val body = response.entity.content.bufferedReader().readText()
            if (status !in 200..299) error("Embedding API вернул $status: $body")
            mapper.readValue<EmbeddingResponse>(body)
        }
    }

    private fun buildHttpClient(): CloseableHttpClient =
        HttpClients.custom().apply {
            if (skipTls) setSSLContext(buildSkipTlsSslContext()).also { setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE) }
            else if (certPath != null) setSSLContext(buildCertSslContext())
            setDefaultRequestConfig(requestConfig)
        }.build()

    private fun buildSkipTlsClient(): CloseableHttpClient =
        HttpClients.custom().apply {
            setSSLContext(buildSkipTlsSslContext())
            setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
            setDefaultRequestConfig(requestConfig)
        }.build()

    private fun buildSkipTlsSslContext(): SSLContext {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
    }

    private fun buildCertSslContext(): SSLContext {
        val certData = certPath ?: error("certPath не задан")
        val certInput = if (File(certData).exists()) File(certData).inputStream()
                        else ByteArrayInputStream(Base64.getDecoder().decode(certData))
        val cf = CertificateFactory.getInstance("X.509")
        val x509 = cf.generateCertificate(certInput)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("embedding-ca", x509)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    override fun close() {
        runCatching { httpClient.close() }
    }
}
