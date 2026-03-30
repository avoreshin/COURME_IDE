package changelogai.feature.gigacodeae.orchestrator.agents

import changelogai.feature.gigacodeae.orchestrator.ChatAgent

/**
 * Агент сжатия истории.
 * Принимает старые сообщения и возвращает краткое резюме.
 * Без инструментов, один вызов LLM.
 */
class SummarizerAgent : ChatAgent("Summarizer") {

    override val systemPrompt: String = """Ты — агент-суммаризатор. Сжимаешь историю разговора в компактное резюме для сохранения контекста.

Правила:
- Сохрани ключевые факты: точные имена файлов и путей, какие классы/методы изменены, какие решения приняты
- Сохрани контекст: язык и фреймворк проекта, текущую задачу пользователя
- Если есть незавершённые задачи — явно перечисли их в конце как "Незавершённые задачи: ..."
- Если возникали ошибки — кратко упомяни их и как были решены (или нет)
- Убери промежуточные рассуждения, дублирование и неудавшиеся попытки
- Объём: 3-8 предложений
- Формат: простой текст, без markdown
- Пиши на русском языке"""

    override val allowedTools: Set<String> = emptySet()

    override fun temperature(): Double = 0.1
    override fun maxTokens(): Int = 500
    override fun maxIterations(): Int = 1

    override fun buildSummary(fullOutput: String): String = fullOutput
}
