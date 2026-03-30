package changelogai.core.settings

import changelogai.core.settings.model.CommitLanguage
import changelogai.core.settings.model.CommitSize
import changelogai.core.settings.model.LLMTemperature

object PluginDefaults {
    const val isDebugMode = false
    val commitLanguage = CommitLanguage.English
    val temperature = LLMTemperature.Standard
    val commitSize = CommitSize.Standard
    const val aiUrl = "https://gigachat.devices.sberbank.ru/api/v1/"
    const val aiModel = "GigaChat-2-Max"
    const val aiToken = ""
    const val aiCertPath = ""
    const val aiKeyPath = ""
    const val embeddingModel = "EmbeddingsGigaR"
    const val chunkSize = 750
    const val chunkOverlap = 100
}
