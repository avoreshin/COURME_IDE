package changelogai.feature.spec

/**
 * Мост между Spec Generator и GigaCodeAE.
 * Позволяет отправить сгенерированный PRD в чат для написания кода.
 */
object SpecCodeBridge {
    private val listeners = mutableListOf<(String) -> Unit>()

    /** Регистрируем слушателя из GigaCodeAETab */
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** Отправляем PRD в GigaCodeAE */
    fun sendToChat(prd: String) {
        listeners.forEach { it(prd) }
    }
}
