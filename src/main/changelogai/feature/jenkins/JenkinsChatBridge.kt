package changelogai.feature.jenkins

/**
 * Мост между Jenkins Feature и GigaCodeAE.
 * Аналог SpecCodeBridge — позволяет передать контекст упавшей сборки в чат.
 */
object JenkinsChatBridge {
    private val listeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** Отправить текст в GigaCodeAE чат. Вызывать из EDT. */
    fun sendToChat(text: String) {
        listeners.forEach { it(text) }
    }

    /** Только для тестов. */
    internal fun clearListeners() {
        listeners.clear()
    }
}
