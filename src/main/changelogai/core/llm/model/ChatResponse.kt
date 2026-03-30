package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatResponse(
    @JsonProperty("choices") val choices: List<ResponseChoice> = emptyList(),
    @JsonProperty("model") val model: String? = null,
    @JsonProperty("usage") val usage: Usage? = null
)
