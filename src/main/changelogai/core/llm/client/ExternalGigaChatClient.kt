package changelogai.core.llm.client

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.http.ssl.SSLContexts
import changelogai.core.llm.cancellation.Cancelable
import changelogai.core.llm.model.ChatRequest
import changelogai.core.llm.model.ChatResponse
import changelogai.core.llm.model.ModelsResponse
import changelogai.core.llm.model.TokenResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

class ExternalGigaChatClient(
    baseUrl: String,
    private val basicToken: String,
    cancelable: Cancelable?
) : LLMClient(baseUrl, cancelable) {

    @Volatile private var bearerToken: String = ""
    @Volatile private var bearerExpiresAt: Long = 0

    init {
        httpClient = createHttpClient()
        refreshBearerToken()
    }

    private fun createHttpClient(): CloseableHttpClient =
        HttpClients.custom()
            .setSSLContext(SSLContexts.custom().loadTrustMaterial(TrustAllStrategy()).build())
            .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
            .setDefaultRequestConfig(
                RequestConfig.custom().setConnectTimeout(30000).setSocketTimeout(60000).build()
            )
            .build()

    @Synchronized private fun refreshBearerToken() {
        if (bearerExpiresAt > System.currentTimeMillis()) return
        val token = executeRequest(HttpPost("https://ngw.devices.sberbank.ru:9443/api/v2/oauth").apply {
            addHeader("Content-Type", "application/x-www-form-urlencoded")
            addHeader("Accept", "application/json")
            addHeader("RqUID", UUID.randomUUID().toString())
            addHeader("Authorization", "Basic $basicToken")
            entity = UrlEncodedFormEntity(
                listOf(BasicNameValuePair("scope", "GIGACHAT_API_PERS")), StandardCharsets.UTF_8
            )
        }, TokenResponse::class.java)
        bearerToken = token.accessToken ?: ""
        bearerExpiresAt = token.expiresAt
    }

    override fun getModels(): ModelsResponse {
        refreshBearerToken()
        return executeRequest(HttpGet(baseUrl + "models").apply {
            addHeader("Accept", "application/json")
            addHeader("Authorization", "Bearer $bearerToken")
        }, ModelsResponse::class.java)
    }

    override fun postChatCompletions(request: ChatRequest): ChatResponse {
        refreshBearerToken()
        return executeRequest(HttpPost(baseUrl + "chat/completions").apply {
            entity = StringEntity(mapper.writeValueAsString(request), ContentType.APPLICATION_JSON)
            addHeader("Accept", "application/json")
            addHeader("Authorization", "Bearer $bearerToken")
        }, ChatResponse::class.java)
    }
}
