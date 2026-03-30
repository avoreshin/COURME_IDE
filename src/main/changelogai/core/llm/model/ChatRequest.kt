package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatRequest(
    @get:JsonProperty("model") val model: String,
    @get:JsonProperty("messages") val messages: List<ChatMessage>,
    @get:JsonProperty("temperature") val temperature: Double,
    @get:JsonProperty("max_tokens") val maxTokens: Int,
    // null → не сериализуются (не ломают changelog-запросы без function calling)
    @get:JsonProperty("functions") val functions: List<FunctionDefinition>? = null,
    @get:JsonProperty("function_call") val functionCall: String? = null  // "auto" | "none"
)
