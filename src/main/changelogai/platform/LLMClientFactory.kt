package changelogai.platform

import changelogai.core.llm.cancellation.Cancelable
import changelogai.core.llm.client.ChemodanClient
import changelogai.core.llm.client.ExternalGigaChatClient
import changelogai.core.llm.client.GigaChatClient
import changelogai.core.llm.client.LLMClient
import changelogai.core.settings.PluginState

object LLMClientFactory {
    private const val EXTERNAL_GIGACHAT_URL = "https://gigachat.devices.sberbank.ru/api/v1/"

    fun create(state: PluginState, cancelable: Cancelable?): LLMClient {
        val isTokenSet = !state.aiToken.isNullOrEmpty()
        return when {
            isTokenSet && state.aiUrl == EXTERNAL_GIGACHAT_URL ->
                ExternalGigaChatClient(state.aiUrl, state.aiToken, cancelable)
            isTokenSet ->
                ChemodanClient(state.aiUrl, state.aiToken, cancelable)
            else ->
                GigaChatClient(state.aiUrl, state.aiCertPath, state.aiKeyPath, cancelable)
        }
    }
}
