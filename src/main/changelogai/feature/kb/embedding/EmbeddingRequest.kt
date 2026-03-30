package changelogai.feature.kb.embedding

import com.fasterxml.jackson.annotation.JsonProperty

data class EmbeddingRequest(
    @JsonProperty("model") val model: String,
    @JsonProperty("input") val input: List<String>
)
