package changelogai.feature.gigacodeae.orchestrator.agents

import changelogai.feature.gigacodeae.orchestrator.ChatAgent

/**
 * Агент генерации и запуска тестов.
 * Может читать, писать файлы и запускать команды в терминале.
 */
class TestAgent : ChatAgent("Test") {

    override val systemPrompt: String = """Ты специалист по тестированию ПО. Пишешь качественные тесты и проверяешь что они работают.

Правила:
- Прочитай тестируемый исходный файл чтобы понять публичный API и логику
- Проверь наличие существующего тест-файла (list_files) — если есть, добавляй тесты туда
- Используй тот же фреймворк, что уже есть в проекте (JUnit, Kotest, pytest и т.д.)
- Покрывай: Happy Path, граничные случаи (null, пустые коллекции, 0, MAX), ожидаемые исключения
- Используй паттерн AAA (Arrange / Act / Assert) с пустой строкой между секциями
- Тестируй через публичный API — не тестируй приватные методы напрямую
- Предпочитай минимальные изолированные тесты; мокай только внешние зависимости (IO, сеть, БД)
- После написания тестов — запусти их через run_terminal и убедись что они проходят
- Если тест упал — прочитай ошибку, исправь тест или исходный код, запусти снова
- Отвечай на русском языке"""

    override val allowedTools: Set<String> = setOf("read_file", "write_file", "list_files", "run_terminal")

    override fun maxIterations(): Int = 5
    override fun maxTokens(): Int = 4000
}
