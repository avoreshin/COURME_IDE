package changelogai.core.llm.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.StdSerializer

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonSerialize(using = ChatMessageSerializer::class)
data class ChatMessage(
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String? = null,
    @JsonProperty("function_call") val functionCall: FunctionCall? = null,
    @JsonProperty("name") val name: String? = null
)

/**
 * Явный сериализатор ChatMessage для GigaChat:
 * - "content": null пишется всегда (GigaChat требует это поле в assistant+FC сообщении)
 * - "function_call" пишется только если не null
 * - "name" пишется только если не null
 */
internal class ChatMessageSerializer : StdSerializer<ChatMessage>(ChatMessage::class.java) {
    override fun serialize(msg: ChatMessage, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        gen.writeStringField("role", msg.role)
        // content: всегда пишем (null → "content":null), GigaChat требует явное поле
        if (msg.content != null) gen.writeStringField("content", msg.content)
        else gen.writeNullField("content")
        // function_call: только если есть
        if (msg.functionCall != null) {
            gen.writeFieldName("function_call")
            provider.defaultSerializeValue(msg.functionCall, gen)
        }
        // name: только если есть
        if (msg.name != null) gen.writeStringField("name", msg.name)
        gen.writeEndObject()
    }
}
