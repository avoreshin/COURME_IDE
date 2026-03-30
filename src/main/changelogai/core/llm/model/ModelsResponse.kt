package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ModelsResponse(
    @JsonProperty("data") val models: List<ModelInfo> = emptyList()
)
