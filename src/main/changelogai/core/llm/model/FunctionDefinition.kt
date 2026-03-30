package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonProperty

data class FunctionDefinition(
    @JsonProperty("name") val name: String,
    @JsonProperty("description") val description: String,
    @JsonProperty("parameters") val parameters: FunctionParameters
)

data class FunctionParameters(
    @JsonProperty("type") val type: String = "object",
    @JsonProperty("properties") val properties: Map<String, PropertySchema>,
    @JsonProperty("required") val required: List<String> = emptyList()
)

data class PropertySchema(
    @JsonProperty("type") val type: String,
    @JsonProperty("description") val description: String
)
