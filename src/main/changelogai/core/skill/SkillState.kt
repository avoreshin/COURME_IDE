package changelogai.core.skill

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "GigaCodeAESkills", storages = [Storage("ChangelogAI.xml")])
class SkillState : PersistentStateComponent<SkillState> {

    // Хранится как JSON-строка — избегает проблем с XML-сериализацией data class
    var skillsJson: String = ""

    private val mapper = jacksonObjectMapper()

    fun allSkills(): List<SkillDefinition> {
        if (skillsJson.isBlank()) return SkillDefinition.defaults()
        return try {
            mapper.readValue<List<SkillDefinition>>(skillsJson)
                .ifEmpty { SkillDefinition.defaults() }
        } catch (_: Exception) {
            SkillDefinition.defaults()
        }
    }

    fun saveSkills(skills: List<SkillDefinition>) {
        skillsJson = mapper.writeValueAsString(skills)
    }

    fun resetToDefaults() {
        val current = allSkills().toMutableList()
        val defaults = SkillDefinition.defaults()
        // Заменяем built-in на дефолтные, custom оставляем
        defaults.forEach { default ->
            val idx = current.indexOfFirst { it.id == default.id }
            if (idx >= 0) current[idx] = default else current.add(0, default)
        }
        saveSkills(current)
    }

    companion object {
        fun getInstance(): SkillState =
            ApplicationManager.getApplication().getService(SkillState::class.java)
    }

    override fun getState(): SkillState = this

    override fun loadState(state: SkillState) {
        skillsJson = state.skillsJson
    }
}
