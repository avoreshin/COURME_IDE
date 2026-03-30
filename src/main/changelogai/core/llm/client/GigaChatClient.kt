package changelogai.core.llm.client

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContexts
import changelogai.core.llm.cancellation.Cancelable
import changelogai.core.llm.model.ChatRequest
import changelogai.core.llm.model.ChatResponse
import changelogai.core.llm.model.ModelsResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.CertificateFactory

class GigaChatClient(
    baseUrl: String,
    private val certPath: String?,
    private val keyPath: String?,
    cancelable: Cancelable?
) : LLMClient(baseUrl, cancelable) {

    init { httpClient = createHttpClient() }

    private fun createHttpClient(): CloseableHttpClient {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        if (!certPath.isNullOrEmpty() && !keyPath.isNullOrEmpty()) {
            Files.newInputStream(Paths.get(certPath)).use { stream ->
                keyStore.setCertificateEntry("client-cert",
                    CertificateFactory.getInstance("X.509").generateCertificate(stream))
            }
        }
        return HttpClients.custom()
            .setSSLContext(SSLContexts.custom()
                .loadKeyMaterial(keyStore, charArrayOf())
                .loadTrustMaterial(TrustAllStrategy())
                .build())
            .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
            .setDefaultRequestConfig(
                RequestConfig.custom().setConnectTimeout(30000).setSocketTimeout(60000).build()
            )
            .build()
    }

    override fun getModels(): ModelsResponse =
        executeRequest(HttpGet(baseUrl + "models").apply {
            addHeader("Accept", "application/json")
        }, ModelsResponse::class.java)

    override fun postChatCompletions(request: ChatRequest): ChatResponse =
        executeRequest(HttpPost(baseUrl + "chat/completions").apply {
            entity = StringEntity(mapper.writeValueAsString(request), ContentType.APPLICATION_JSON)
            addHeader("Accept", "application/json")
            addHeader("Content-Type", "application/json")
        }, ChatResponse::class.java)
}
