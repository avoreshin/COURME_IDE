package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Usage(
    @JsonProperty("total_tokens") val totalTokens: Int = 0,
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0
)
