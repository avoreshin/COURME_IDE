package changelogai.core.llm.cancellation

interface Cancelable {
    fun isCanceled(): Boolean
    fun checkCanceled()
}
