package changelogai.core.llm.debug

import java.time.Instant

data class LLMCallTrace(
    val id: Int,
    val timestamp: Instant,
    val url: String,
    val requestJson: String,
    val responseJson: String,
    val statusCode: Int,
    val durationMs: Long,
    val error: String? = null
) {
    val isSuccess get() = error == null && statusCode in 200..299
    val label get() = "${timestamp.toString().substring(11, 19)}  ${url.substringAfterLast("/")}"
}
