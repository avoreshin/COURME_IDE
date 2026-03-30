package changelogai.core.llm.model

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * GigaChat отправляет/принимает `arguments` как JSON-объект: {"key":"val"}
 * OpenAI отправляет/принимает `arguments` как JSON-строку:  "{\"key\":\"val\"}"
 *
 * ArgumentsDeserializer нормализует входящее в строку.
 * ArgumentsSerializer пишет строку обратно как raw JSON-объект (для GigaChat).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FunctionCall(
    @JsonProperty("name") val name: String,
    @JsonProperty("arguments")
    @JsonDeserialize(using = ArgumentsDeserializer::class)
    @JsonSerialize(using = ArgumentsSerializer::class)
    val arguments: String = "{}"
)

private class ArgumentsDeserializer : StdDeserializer<String>(String::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String =
        when (p.currentToken) {
            JsonToken.VALUE_STRING -> p.text                         // OpenAI: строка
            JsonToken.START_OBJECT -> p.readValueAsTree<JsonNode>()  // GigaChat: объект
                .toString()
            else -> "{}"
        }
}

private class ArgumentsSerializer : StdSerializer<String>(String::class.java) {
    override fun serialize(value: String, gen: JsonGenerator, provider: SerializerProvider) {
        // Пишем как raw JSON (объект/массив) — GigaChat ожидает объект, не строку
        try {
            gen.writeRawValue(value)
        } catch (_: Exception) {
            gen.writeString(value)
        }
    }
}
