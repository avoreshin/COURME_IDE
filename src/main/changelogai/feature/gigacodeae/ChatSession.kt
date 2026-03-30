package changelogai.feature.gigacodeae

import changelogai.core.llm.model.ChatMessage
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Новый чат",
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf(),
    /** Кэш сжатой истории (summary старых сообщений) для экономии контекста LLM */
    var conversationSummary: String? = null
) {
    /** Автоматически назначает заголовок по первому сообщению пользователя */
    fun autoTitle() {
        val first = messages.firstOrNull { it.role == "user" }?.content ?: return
        title = first.take(50).trimEnd().let { if (first.length > 50) "$it…" else it }
    }
}
