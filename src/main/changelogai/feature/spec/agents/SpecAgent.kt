package changelogai.feature.spec.agents

import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.platform.LLMClientFactory
import changelogai.core.llm.cancellation.AtomicCancelable

/**
 * Базовый класс специализированного агента.
 * Каждый агент имеет свой системный промпт и выполняет один узкий шаг.
 */
abstract class SpecAgent(val name: String, val icon: String) {

    protected abstract val systemPrompt: String

    /**
     * Выполняет задачу агента и возвращает текстовый ответ.
     * Вызывается из фонового потока.
     */
    fun run(userMessage: String, cancelable: AtomicCancelable): String {
        val state = PluginState.getInstance()
        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userMessage)
        )
        val request = ChatRequest(
            model = state.aiModel,
            temperature = temperature(),
            maxTokens = maxTokens(),
            messages = messages
        )
        LLMClientFactory.create(state, cancelable).use { client ->
            val response = client.postChatCompletions(request)
            return response.choices.firstOrNull()?.message?.content
                ?: error("[$name] LLM вернул пустой ответ")
        }
    }

    protected open fun temperature() = 0.3
    protected open fun maxTokens(): Int = PluginState.getInstance().commitSize.value.coerceAtLeast(3000)
}
