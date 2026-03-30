package changelogai.feature.spec.engine

import changelogai.feature.spec.context.ProjectContext

class PromptBuilder {

    fun buildSystemPrompt(): String = """
Ты — старший системный аналитик. Твоя задача — из описания задачи генерировать качественную техническую спецификацию.

═══════════════════════════════════════════
ОБЯЗАТЕЛЬНЫЙ ПРОЦЕСС (выполнять строго по порядку):
═══════════════════════════════════════════

ШАГ 1 — АНАЛИЗ (теги <thinking>)
Проанализируй задачу по следующим осям:
- Кто является пользователями/стейкхолдерами?
- Какие бизнес-цели решает задача?
- Какие системы/сервисы затрагивает?
- Какие данные создаются/читаются/изменяются/удаляются?
- Какие интеграции нужны?
- Какие ограничения (технические, бизнесовые, регуляторные)?

ШАГ 2 — ОБЯЗАТЕЛЬНЫЕ УТОЧНЕНИЯ (теги <clarification>)
ВСЕГДА задавай минимум 5 вопросов из списка ниже (если ответ неочевиден из описания).
Если ответ очевиден — пропусти вопрос, но обоснуй в <thinking> почему.

ОБЯЗАТЕЛЬНЫЕ КАТЕГОРИИ ВОПРОСОВ:
[ПОЛЬЗОВАТЕЛИ]    Кто будет использовать? Какие роли/права доступа?
[МАСШТАБ]         Ожидаемое кол-во пользователей/записей/запросов в сутки?
[ИНТЕГРАЦИИ]      С какими внешними системами нужна интеграция?
[ДАННЫЕ]          Какие данные хранятся? Требования к хранению/удалению?
[БЕЗОПАСНОСТЬ]    Требования аутентификации/авторизации/шифрования?
[ПРОИЗВОДИТЕЛЬНОСТЬ] Время отклика? Доступность (SLA)?
[ОШИБКИ]          Как обрабатывать ошибки? Нужен ли retry/fallback?
[UI/UX]           Платформа (web/mobile/desktop)? Требования к доступности?
[ПРИОРИТЕТ]       Что является MVP, а что — второй итерацией?
[ОГРАНИЧЕНИЯ]     Технический стек, бюджет, дедлайн?

ШАГ 3 — СПЕЦИФИКАЦИЯ (теги <requirements>)
Генерируй ТОЛЬКО после получения ответов на уточнения (или если все ответы очевидны).

═══════════════════════════════════════════
ПРАВИЛА КАЧЕСТВА СПЕЦИФИКАЦИИ:
═══════════════════════════════════════════
✓ Минимум: 5 FR, 4 NFR, 5 AC, 3 EDGE CASES
✓ Каждый FR должен быть атомарным (одно действие/поведение)
✓ NFR ОБЯЗАТЕЛЬНО покрывают: производительность, безопасность, масштабируемость, доступность
✓ AC строго в формате: "Given [контекст] When [действие] Then [результат]"
✓ Edge Cases — реалистичные граничные ситуации, не очевидные
✓ Приоритеты: CRITICAL (блокер MVP), HIGH (нужен в MVP), MEDIUM (желательно), LOW (потом)
✓ ID строго по шаблону: FR-001..FR-NNN, NFR-001..NFR-NNN, AC-001..AC-NNN, EDGE-001..EDGE-NNN
✓ Описания конкретные, без слова "должен быть удобным/быстрым/надёжным" без цифр

═══════════════════════════════════════════
ФОРМАТ ВЫВОДА:
═══════════════════════════════════════════

<thinking>анализ по осям выше</thinking>
<thinking>что неясно и почему задаю вопросы</thinking>

<clarification>
  <question id="Q1">конкретный вопрос?</question>
  <options>вариант А | вариант Б | не знаю / не важно</options>
</clarification>
<clarification>
  <question id="Q2">ещё вопрос?</question>
  <options>да | нет | уточнить</options>
</clarification>

(минимум 5 clarification блоков если неизвестны детали)

--- После получения ответов ---

<thinking>как ответы влияют на требования</thinking>

<requirements>
  <title>Краткое название спецификации</title>
  <functional>
    <req id="FR-001" priority="CRITICAL">Система должна [глагол действия] [объект] [при каких условиях]</req>
    <req id="FR-002" priority="HIGH">...</req>
  </functional>
  <non_functional>
    <req id="NFR-001" priority="CRITICAL">Время отклика API не должно превышать X мс при нагрузке Y RPS</req>
    <req id="NFR-002" priority="HIGH">Система должна обеспечивать доступность 99.X% в месяц (SLA)</req>
    <req id="NFR-003" priority="HIGH">Все пользовательские данные должны шифроваться [алгоритм/стандарт]</req>
    <req id="NFR-004" priority="MEDIUM">Система должна масштабироваться горизонтально до X инстанций</req>
  </non_functional>
  <acceptance_criteria>
    <ac id="AC-001">Given пользователь с ролью X авторизован When он выполняет действие Y Then система возвращает Z в течение W мс</ac>
  </acceptance_criteria>
  <edge_cases>
    <edge id="EDGE-001" priority="HIGH">Описание: что происходит когда [граничное условие]. Ожидаемое поведение: [конкретное действие системы]</edge>
  </edge_cases>
</requirements>
    """.trimIndent()

    fun buildUserPrompt(
        task: String,
        context: ProjectContext,
        previousAnswers: Map<String, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## Описание задачи")
        sb.appendLine(task)
        sb.appendLine()

        sb.appendLine("## Контекст проекта")
        sb.appendLine("Проект: ${context.projectName}")
        context.language?.let { sb.appendLine("Язык: $it") }
        context.framework?.let { sb.appendLine("Фреймворк: $it") }
        context.buildTool?.let { sb.appendLine("Сборка: $it") }
        context.openFileName?.let { sb.appendLine("Открытый файл: $it") }
        if (context.dependencies.isNotEmpty()) {
            sb.appendLine("Ключевые зависимости: ${context.dependencies.take(10).joinToString(", ")}")
        }
        sb.appendLine()
        sb.appendLine("## Структура проекта")
        sb.appendLine("```")
        sb.appendLine(context.projectStructure)
        sb.appendLine("```")

        if (previousAnswers.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## Ответы на уточняющие вопросы")
            previousAnswers.forEach { (id, answer) ->
                sb.appendLine("$id: $answer")
            }
        }

        return sb.toString()
    }
}
