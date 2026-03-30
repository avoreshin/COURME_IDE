package changelogai.core.llm.cancellation

import com.intellij.openapi.progress.ProgressIndicator

class ProgressIndicatorWrapper(private val indicator: ProgressIndicator) : Cancelable {
    override fun isCanceled() = indicator.isCanceled
    override fun checkCanceled() = indicator.checkCanceled()
}
