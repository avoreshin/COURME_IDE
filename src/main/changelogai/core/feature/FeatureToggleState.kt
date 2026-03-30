package changelogai.core.feature

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.ConcurrentHashMap

@State(name = "FeatureToggles", storages = [Storage("ChangelogAI.xml")])
class FeatureToggleState : PersistentStateComponent<FeatureToggleState> {

    // ConcurrentHashMap — thread-safe: читается из BGT, пишется из EDT
    var toggles: MutableMap<String, Boolean> = ConcurrentHashMap()

    fun isEnabled(feature: Feature): Boolean =
        toggles.getOrDefault(feature.id, feature.enabledByDefault)

    fun setEnabled(featureId: String, enabled: Boolean) {
        toggles[featureId] = enabled
    }

    companion object {
        fun getInstance(): FeatureToggleState =
            ApplicationManager.getApplication().getService(FeatureToggleState::class.java)
    }

    override fun getState(): FeatureToggleState = this

    override fun loadState(state: FeatureToggleState) {
        toggles = ConcurrentHashMap(state.toggles)
    }
}
