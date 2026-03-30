package changelogai.feature.kb.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbeddingResponse(
    @JsonProperty("data") val data: List<EmbeddingData> = emptyList(),
    @JsonProperty("model") val model: String? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbeddingData(
        @JsonProperty("embedding") val embedding: List<Float> = emptyList(),
        @JsonProperty("index") val index: Int = 0
    )
}
