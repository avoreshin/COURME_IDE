package changelogai.core.llm.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.progress.ProcessCanceledException
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.util.EntityUtils
import org.jsoup.HttpStatusException
import changelogai.core.llm.cancellation.Cancelable
import changelogai.core.llm.debug.LLMCallTrace
import changelogai.core.llm.debug.LLMTraceStore
import changelogai.core.llm.model.ChatRequest
import changelogai.core.llm.model.ChatResponse
import changelogai.core.llm.model.ModelsResponse
import java.io.IOException
import java.time.Instant
import java.util.concurrent.*

abstract class LLMClient(
    protected val baseUrl: String,
    private val cancelable: Cancelable?
) : AutoCloseable {

    protected var httpClient: CloseableHttpClient? = null
    protected val mapper = jacksonObjectMapper()
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    abstract fun getModels(): ModelsResponse
    abstract fun postChatCompletions(request: ChatRequest): ChatResponse

    override fun close() {
        httpClient?.close()
        executor.shutdown()
    }

    protected fun <T> executeRequest(request: HttpRequestBase, responseClass: Class<T>): T {
        cancelable?.checkCanceled()
        val traceId = LLMTraceStore.nextId()
        val startTime = Instant.now()
        val requestJson = (request as? HttpEntityEnclosingRequestBase)
            ?.entity?.let { entity ->
                val bytes = entity.content.readBytes()
                entity.content.close()
                // Восстанавливаем entity чтобы Apache HttpClient мог его отправить
                org.apache.http.entity.ByteArrayEntity(bytes, org.apache.http.entity.ContentType.APPLICATION_JSON)
                    .also { (request as HttpEntityEnclosingRequestBase).entity = it }
                prettyPrint(String(bytes))
            } ?: ""

        val future = executor.submit<CloseableHttpResponse> { httpClient!!.execute(request) }
        return try {
            while (!future.isDone) {
                try { future.get(500, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
                if (cancelable?.isCanceled() == true) {
                    future.cancel(true); request.abort(); throw ProcessCanceledException()
                }
            }
            future.get().use { response ->
                val statusCode = response.statusLine.statusCode
                val body = EntityUtils.toString(response.entity)
                cancelable?.checkCanceled()
                LLMTraceStore.add(LLMCallTrace(
                    id = traceId,
                    timestamp = startTime,
                    url = request.uri.toString(),
                    requestJson = requestJson,
                    responseJson = prettyPrint(body),
                    statusCode = statusCode,
                    durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis()
                ))
                if (statusCode in 200..299) mapper.readValue(body, responseClass)
                else throw HttpStatusException(body, statusCode, request.uri.toString())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); future.cancel(true); request.abort()
            throw ProcessCanceledException()
        } catch (e: ExecutionException) {
            if (cancelable?.isCanceled() == true) throw ProcessCanceledException()
            LLMTraceStore.add(LLMCallTrace(
                id = traceId, timestamp = startTime,
                url = request.uri.toString(), requestJson = requestJson,
                responseJson = "", statusCode = 0,
                durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis(),
                error = e.cause?.message ?: e.message
            ))
            throw IOException("HTTP request failed", e.cause)
        }
    }

    private fun prettyPrint(json: String): String = try {
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json))
    } catch (_: Exception) { json }
}
