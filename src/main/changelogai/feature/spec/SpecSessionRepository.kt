package changelogai.feature.spec

import changelogai.feature.spec.model.SpecDocument
import changelogai.feature.spec.model.SpecSession
import changelogai.feature.spec.model.SpecState
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@State(name = "SpecGeneratorSessions", storages = [Storage("SpecGenerator.xml")])
class SpecSessionRepository : PersistentStateComponent<SpecSessionRepository.XmlState> {

    data class XmlState(var sessionsJson: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SessionDto(
        val id: String = "",
        val title: String = "Новая спецификация",
        val createdAt: Long = 0,
        val taskDescription: String = "",
        val spec: SpecDto? = null,
        val prdDocument: String? = null,
        val mermaidDiagrams: String? = null,
        val state: String = "IDLE"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SpecDto(
        val title: String = "",
        val functional: List<ReqDto> = emptyList(),
        val nonFunctional: List<ReqDto> = emptyList(),
        val acceptanceCriteria: List<ReqDto> = emptyList(),
        val edgeCases: List<ReqDto> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ReqDto(
        val id: String = "",
        val description: String = "",
        val priority: String = "MEDIUM"
    )

    private val mapper = jacksonObjectMapper()
    private var myState = XmlState()
    private var cachedSessions: MutableList<SpecSession>? = null

    val sessions: List<SpecSession>
        get() = loadSessions()

    fun add(session: SpecSession) {
        val list = loadSessions()
        list.add(0, session)
        while (list.size > 20) list.removeLast()
        saveSessions(list)
    }

    fun update(session: SpecSession) {
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
        cachedSessions?.let { saveSessions(it) }
        return myState
    }

    override fun loadState(state: XmlState) {
        myState = state
        cachedSessions = null
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun loadSessions(): MutableList<SpecSession> {
        cachedSessions?.let { return it }
        val json = myState.sessionsJson
        if (json.isBlank()) {
            val empty = mutableListOf<SpecSession>()
            cachedSessions = empty
            return empty
        }
        return try {
            val dtos = mapper.readValue<List<SessionDto>>(json)
            val result = dtos.map { it.toSession() }.toMutableList()
            cachedSessions = result
            result
        } catch (_: Exception) {
            val empty = mutableListOf<SpecSession>()
            cachedSessions = empty
            empty
        }
    }

    private fun saveSessions(list: MutableList<SpecSession>) {
        cachedSessions = list
        myState.sessionsJson = try {
            mapper.writeValueAsString(list.map { it.toDto() })
        } catch (_: Exception) { "" }
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    private fun SpecSession.toDto() = SessionDto(
        id = id,
        title = title,
        createdAt = createdAt,
        taskDescription = taskDescription,
        spec = spec?.toDto(),
        prdDocument = prdDocument,
        mermaidDiagrams = mermaidDiagrams,
        state = state.name
    )

    private fun SpecDocument.toDto() = SpecDto(
        title = title,
        functional = functional.map { it.toDto() },
        nonFunctional = nonFunctional.map { it.toDto() },
        acceptanceCriteria = acceptanceCriteria.map { it.toDto() },
        edgeCases = edgeCases.map { it.toDto() }
    )

    private fun SpecDocument.Requirement.toDto() = ReqDto(
        id = id, description = description, priority = priority.name
    )

    private fun SessionDto.toSession() = SpecSession(
        id = id,
        title = title,
        createdAt = createdAt,
        taskDescription = taskDescription,
        spec = spec?.toSpec(),
        prdDocument = prdDocument,
        mermaidDiagrams = mermaidDiagrams,
        state = try { SpecState.valueOf(state) } catch (_: Exception) { SpecState.IDLE }
    )

    private fun SpecDto.toSpec() = SpecDocument(
        title = title,
        functional = functional.map { it.toReq() },
        nonFunctional = nonFunctional.map { it.toReq() },
        acceptanceCriteria = acceptanceCriteria.map { it.toReq() },
        edgeCases = edgeCases.map { it.toReq() }
    )

    private fun ReqDto.toReq() = SpecDocument.Requirement(
        id = id,
        description = description,
        priority = try { SpecDocument.Priority.valueOf(priority) } catch (_: Exception) { SpecDocument.Priority.MEDIUM }
    )

    companion object {
        fun getInstance(project: Project): SpecSessionRepository =
            project.getService(SpecSessionRepository::class.java)
    }
}
