package changelogai.core.llm.cancellation

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Простой флаг отмены без привязки к ProgressIndicator.
 * Используется в фоновых задачах без IDE progress bar (напр. чат).
 */
class AtomicCancelable : Cancelable {
    private val canceled = AtomicBoolean(false)

    fun cancel() { canceled.set(true) }

    override fun isCanceled(): Boolean = canceled.get()

    override fun checkCanceled() {
        if (canceled.get()) throw ProcessCanceledException()
    }
}
