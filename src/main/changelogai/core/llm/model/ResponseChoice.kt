package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResponseChoice(
    @JsonProperty("message") val message: ChatMessage? = null,
    @JsonProperty("index") val index: Int = 0,
    @JsonProperty("finish_reason") val finishReason: String? = null
)
