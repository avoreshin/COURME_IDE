package changelogai.core.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import changelogai.core.settings.model.CommitLanguage
import changelogai.core.settings.model.CommitSize
import changelogai.core.settings.model.LLMTemperature

@State(name = "ChangelogAISettings", storages = [Storage("ChangelogAI.xml")])
class PluginState : PersistentStateComponent<PluginState> {

    var isDebugMode: Boolean = PluginDefaults.isDebugMode
    var commitLanguage: CommitLanguage = PluginDefaults.commitLanguage
    var temperature: LLMTemperature = PluginDefaults.temperature
    var commitSize: CommitSize = PluginDefaults.commitSize
    var aiUrl: String = PluginDefaults.aiUrl
    var aiModel: String = PluginDefaults.aiModel
    var aiToken: String = PluginDefaults.aiToken
    var aiCertPath: String = PluginDefaults.aiCertPath
    var aiKeyPath: String = PluginDefaults.aiKeyPath

    // Knowledge Base — Embedding API settings
    var embeddingUrl: String = ""
    var embeddingToken: String = ""
    var embeddingModel: String = PluginDefaults.embeddingModel
    var chunkSize: Int = PluginDefaults.chunkSize
    var chunkOverlap: Int = PluginDefaults.chunkOverlap

    companion object {
        fun getInstance(): PluginState =
            ApplicationManager.getApplication().getService(PluginState::class.java)
    }

    override fun getState(): PluginState = this

    override fun loadState(state: PluginState) {
        isDebugMode = state.isDebugMode
        commitLanguage = state.commitLanguage
        temperature = state.temperature
        commitSize = state.commitSize
        aiUrl = state.aiUrl
        aiModel = state.aiModel
        aiToken = state.aiToken
        aiCertPath = state.aiCertPath
        aiKeyPath = state.aiKeyPath
        embeddingUrl = state.embeddingUrl
        embeddingToken = state.embeddingToken
        embeddingModel = state.embeddingModel
        chunkSize = state.chunkSize
        chunkOverlap = state.chunkOverlap
    }
}
