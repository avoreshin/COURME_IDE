package changelogai.feature.kb

import changelogai.feature.kb.model.KBSource
import changelogai.feature.kb.model.KBSourceType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Персистентное хранилище конфигурации базы знаний (тип источника, spaceKey, URL и т.д.).
 * Хранится в KnowledgeBase.xml на уровне проекта.
 */
@State(name = "KnowledgeBaseConfig", storages = [Storage("KnowledgeBase.xml")])
class KnowledgeBaseRepository : PersistentStateComponent<KnowledgeBaseRepository.XmlState> {

    data class XmlState(var configJson: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ConfigDto(
        val sourceType: String = "MANUAL",
        val spaceKey: String = "",
        val rootPageUrl: String = "",
        val manualUrls: List<String> = emptyList()
    )

    private val mapper = jacksonObjectMapper()
    private var myState = XmlState()
    private var cachedSource: KBSource? = null

    var source: KBSource?
        get() = loadSource()
        set(value) {
            cachedSource = value
            myState.configJson = if (value != null) {
                try {
                    mapper.writeValueAsString(ConfigDto(
                        sourceType = value.type.name,
                        spaceKey = value.spaceKey,
                        rootPageUrl = value.rootPageUrl,
                        manualUrls = value.manualUrls
                    ))
                } catch (_: Exception) { "" }
            } else ""
        }

    override fun getState(): XmlState = myState

    override fun loadState(state: XmlState) {
        myState = state
        cachedSource = null
    }

    private fun loadSource(): KBSource? {
        cachedSource?.let { return it }
        val json = myState.configJson
        if (json.isBlank()) return null
        return try {
            val dto = mapper.readValue<ConfigDto>(json)
            val source = KBSource(
                type = try { KBSourceType.valueOf(dto.sourceType) } catch (_: Exception) { KBSourceType.MANUAL },
                spaceKey = dto.spaceKey,
                rootPageUrl = dto.rootPageUrl,
                manualUrls = dto.manualUrls
            )
            cachedSource = source
            source
        } catch (_: Exception) { null }
    }

    companion object {
        fun getInstance(project: Project): KnowledgeBaseRepository =
            project.getService(KnowledgeBaseRepository::class.java)
    }
}
