package changelogai.platform.services

import changelogai.core.settings.PluginState
import changelogai.platform.LLMClientFactory

class AIModelsService {
    var lastError: String? = null
        private set

    fun getModelNames(): Array<String> = fetchModels(PluginState.getInstance())

    fun getModelNames(url: String, token: String, certPath: String, keyPath: String): Array<String> {
        val tempState = PluginState().also {
            it.aiUrl = url; it.aiToken = token; it.aiCertPath = certPath; it.aiKeyPath = keyPath
        }
        return fetchModels(tempState)
    }

    private fun fetchModels(state: PluginState): Array<String> {
        lastError = null
        return try {
            LLMClientFactory.create(state, null).use { client ->
                client.getModels().models.mapNotNull { it.id }.toTypedArray()
            }
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            emptyArray()
        }
    }
}
