package changelogai.feature.gigacodeae

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Хранилище чат-сессий.
 *
 * Сессии сериализуются в JSON-строку (как в SkillState) —
 * IntelliJ XML-сериализатор не справляется с val-полями ChatMessage
 * и Jackson-аннотациями (@JsonProperty, @JsonSerialize).
 */
@State(name = "GigaCodeAESessions", storages = [Storage("GigaCodeAE.xml")])
class ChatSessionRepository : PersistentStateComponent<ChatSessionRepository.XmlState> {

    /** XML-обёртка: единственное поле — JSON-строка со всеми сессиями */
    data class XmlState(var sessionsJson: String = "")

    /**
     * Облегчённая модель сессии для сериализации.
     * ChatMessage уже корректно сериализуется Jackson'ом.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SessionDto(
        val id: String = "",
        val title: String = "Новый чат",
        val createdAt: Long = 0,
        val messages: List<MessageDto> = emptyList(),
        val conversationSummary: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MessageDto(
        val role: String = "",
        val content: String? = null,
        val functionCallName: String? = null,
        val functionCallArgs: String? = null,
        val name: String? = null
    )

    private val mapper = jacksonObjectMapper()
    private var myState = XmlState()
    private var cachedSessions: MutableList<ChatSession>? = null

    val sessions: List<ChatSession>
        get() = loadSessions()

    fun add(session: ChatSession) {
        val list = loadSessions()
        list.add(0, session)
        while (list.size > 20) list.removeLast()
        saveSessions(list)
    }

    fun update(session: ChatSession) {
        val list = loadSessions()
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session
        saveSessions(list)
    }

    fun delete(sessionId: String) {
        val list = loadSessions()
        list.removeAll { it.id == sessionId }
        saveSessions(list)
    }

    // ── PersistentStateComponent ─────────────────────────────────────────

    override fun getState(): XmlState {
        // Перед сохранением — актуализируем JSON из кэша
        cachedSessions?.let { saveSessions(it) }
        return myState
    }

    override fun loadState(state: XmlState) {
        myState = state
        cachedSessions = null // сбрасываем кэш, чтобы перечитать из JSON
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun loadSessions(): MutableList<ChatSession> {
        cachedSessions?.let { return it }
        val json = myState.sessionsJson
        if (json.isBlank()) {
            val empty = mutableListOf<ChatSession>()
            cachedSessions = empty
            return empty
        }
        return try {
            val dtos = mapper.readValue<List<SessionDto>>(json)
            val result = dtos.map { it.toSession() }.toMutableList()
            cachedSessions = result
            result
        } catch (_: Exception) {
            val empty = mutableListOf<ChatSession>()
            cachedSessions = empty
            empty
        }
    }

    private fun saveSessions(list: MutableList<ChatSession>) {
        cachedSessions = list
        myState.sessionsJson = try {
            mapper.writeValueAsString(list.map { it.toDto() })
        } catch (_: Exception) {
            ""
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    private fun ChatSession.toDto() = SessionDto(
        id = id,
        title = title,
        createdAt = createdAt,
        messages = messages.map { msg ->
            MessageDto(
                role = msg.role,
                content = msg.content,
                functionCallName = msg.functionCall?.name,
                functionCallArgs = msg.functionCall?.arguments,
                name = msg.name
            )
        },
        conversationSummary = conversationSummary
    )

    private fun SessionDto.toSession() = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        messages = messages.map { dto ->
            val fc = if (dto.functionCallName != null) {
                changelogai.core.llm.model.FunctionCall(
                    name = dto.functionCallName,
                    arguments = dto.functionCallArgs ?: ""
                )
            } else null
            changelogai.core.llm.model.ChatMessage(
                role = dto.role,
                content = dto.content,
                functionCall = fc,
                name = dto.name
            )
        }.toMutableList(),
        conversationSummary = conversationSummary
    )

    companion object {
        fun getInstance(project: Project): ChatSessionRepository =
            project.getService(ChatSessionRepository::class.java)
    }
}
