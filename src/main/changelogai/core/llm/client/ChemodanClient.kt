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

class ChemodanClient(
    baseUrl: String,
    private val token: String,
    cancelable: Cancelable?
) : LLMClient(baseUrl, cancelable) {

    init { httpClient = createHttpClient() }

    private fun createHttpClient(): CloseableHttpClient =
        HttpClients.custom()
            .setSSLContext(SSLContexts.custom().loadTrustMaterial(TrustAllStrategy()).build())
            .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
            .setDefaultRequestConfig(
                RequestConfig.custom().setConnectTimeout(30000).setSocketTimeout(60000).build()
            )
            .build()

    override fun getModels(): ModelsResponse =
        executeRequest(HttpGet(baseUrl + "models").apply {
            addHeader("Accept", "application/json")
            addHeader("Authorization", "Basic $token")
        }, ModelsResponse::class.java)

    override fun postChatCompletions(request: ChatRequest): ChatResponse =
        executeRequest(HttpPost(baseUrl + "chat/completions").apply {
            entity = StringEntity(mapper.writeValueAsString(request), ContentType.APPLICATION_JSON)
            addHeader("Accept", "application/json")
            addHeader("Content-Type", "application/json")
            addHeader("Authorization", "Basic $token")
        }, ChatResponse::class.java)
}
