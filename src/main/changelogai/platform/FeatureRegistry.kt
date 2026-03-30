package changelogai.platform

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.ExtensionPointName
import changelogai.core.feature.Feature

@Service
class FeatureRegistry {

    fun getAll(): List<Feature> = EP_NAME.extensionList

    companion object {
        val EP_NAME: ExtensionPointName<Feature> =
            ExtensionPointName.create("changelogai.feature")

        fun getInstance(): FeatureRegistry =
            ApplicationManager.getApplication().getService(FeatureRegistry::class.java)
    }
}
