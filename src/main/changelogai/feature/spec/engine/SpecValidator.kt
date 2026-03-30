package changelogai.feature.spec.engine

import changelogai.feature.spec.model.SpecDocument

/**
 * Валидирует качество сгенерированной спецификации.
 * Если есть нарушения — возвращает список проблем для повторного раунда с LLM.
 */
object SpecValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val hasErrors get() = errors.isNotEmpty()
        fun summary() = (errors + warnings).joinToString("\n") { "- $it" }
    }

    fun validate(spec: SpecDocument): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ---- Минимальное количество требований -------------------------
        if (spec.functional.size < 3)
            errors += "Слишком мало функциональных требований: ${spec.functional.size} (минимум 3). Добавь больше FR."
        if (spec.nonFunctional.size < 3)
            errors += "Слишком мало нефункциональных требований: ${spec.nonFunctional.size} (минимум 3). Добавь NFR по производительности, безопасности и масштабируемости."
        if (spec.acceptanceCriteria.size < 3)
            errors += "Мало критериев приёмки: ${spec.acceptanceCriteria.size} (минимум 3). Добавь AC для основных сценариев."
        if (spec.edgeCases.isEmpty())
            errors += "Edge Cases отсутствуют. Добавь минимум 2 граничных случая."

        // ---- Уникальность ID -------------------------------------------
        val allIds = (spec.functional + spec.nonFunctional + spec.acceptanceCriteria + spec.edgeCases)
            .map { it.id }
        val duplicates = allIds.groupBy { it }.filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty())
            errors += "Дублирующиеся ID: ${duplicates.joinToString(", ")}. Все ID должны быть уникальными."

        // ---- Формат ID -------------------------------------------------
        spec.functional.forEach { r ->
            if (!r.id.matches(Regex("FR-\\d{3}")))
                errors += "Неверный формат ID '${r.id}'. Ожидается FR-001, FR-002, ..."
        }
        spec.nonFunctional.forEach { r ->
            if (!r.id.matches(Regex("NFR-\\d{3}")))
                errors += "Неверный формат ID '${r.id}'. Ожидается NFR-001, NFR-002, ..."
        }
        spec.acceptanceCriteria.forEach { r ->
            if (!r.id.matches(Regex("AC-\\d{3}")))
                errors += "Неверный формат ID '${r.id}'. Ожидается AC-001, AC-002, ..."
        }
        spec.edgeCases.forEach { r ->
            if (!r.id.matches(Regex("EDGE-\\d{3}")))
                errors += "Неверный формат ID '${r.id}'. Ожидается EDGE-001, EDGE-002, ..."
        }

        // ---- AC: Given/When/Then ---------------------------------------
        spec.acceptanceCriteria.forEach { ac ->
            val lower = ac.description.lowercase()
            val hasGiven = "given" in lower || "дано" in lower || "при условии" in lower
            val hasWhen  = "when" in lower  || "когда" in lower || "если" in lower
            val hasThen  = "then" in lower  || "тогда" in lower || "то " in lower
            if (!hasGiven || !hasWhen || !hasThen)
                errors += "${ac.id}: критерий приёмки должен быть в формате Given/When/Then. Текущий: '${ac.description.take(60)}...'"
        }

        // ---- NFR: покрытие обязательных категорий ----------------------
        val nfrText = spec.nonFunctional.joinToString(" ") { it.description }.lowercase()
        if (!coversPerformance(nfrText))
            errors += "NFR не покрывает производительность. Добавь требование с конкретными цифрами (время отклика, RPS, latency)."
        if (!coversSecurity(nfrText))
            errors += "NFR не покрывает безопасность. Добавь требования по аутентификации, авторизации или шифрованию."
        if (!coversAvailability(nfrText))
            warnings += "NFR не упоминает доступность/SLA. Рекомендуется добавить NFR с процентом uptime."
        if (!coversScalability(nfrText) && spec.functional.size > 5)
            warnings += "NFR не упоминает масштабируемость. Для нетривиальной системы рекомендуется добавить."

        // ---- Расплывчатые описания -------------------------------------
        val vagueWords = listOf("удобный", "быстрый", "надёжный", "хороший", "простой",
            "интуитивный", "современный", "гибкий", "удобно", "быстро", "хорошо")
        val allReqs = spec.functional + spec.nonFunctional + spec.edgeCases
        allReqs.forEach { r ->
            val descLower = r.description.lowercase()
            val found = vagueWords.filter { it in descLower }
            if (found.isNotEmpty())
                warnings += "${r.id}: расплывчатые слова '${found.joinToString(", ")}'. Замени на конкретные измеримые критерии."
        }

        // ---- Слишком короткие описания ---------------------------------
        val allAll = spec.functional + spec.nonFunctional + spec.acceptanceCriteria + spec.edgeCases
        allAll.forEach { r ->
            if (r.description.length < 15)
                errors += "${r.id}: описание слишком короткое ('${r.description}'). Требования должны быть конкретными."
        }

        // ---- Распределение приоритетов ---------------------------------
        val frPriorities = spec.functional.map { it.priority }
        if (frPriorities.all { it == SpecDocument.Priority.HIGH || it == SpecDocument.Priority.CRITICAL })
            warnings += "Все FR имеют высокий приоритет — это подозрительно. Пересмотри приоритеты, разбив на MVP и backlog."
        if (spec.functional.none { it.priority == SpecDocument.Priority.CRITICAL || it.priority == SpecDocument.Priority.HIGH })
            warnings += "Ни одно функциональное требование не помечено как HIGH/CRITICAL."

        // ---- Заголовок -------------------------------------------------
        if (spec.title.isBlank() || spec.title == "Спецификация")
            warnings += "Заголовок спецификации слишком общий. Дай конкретное название."

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Генерирует промпт для повторного раунда LLM с описанием нарушений.
     */
    fun buildFixPrompt(result: ValidationResult): String {
        val sb = StringBuilder()
        sb.appendLine("Сгенерированная спецификация не прошла валидацию. Исправь следующие проблемы и перегенерируй ПОЛНУЮ спецификацию:")
        sb.appendLine()
        if (result.errors.isNotEmpty()) {
            sb.appendLine("КРИТИЧЕСКИЕ ОШИБКИ (обязательно исправить):")
            result.errors.forEach { sb.appendLine("  ✗ $it") }
            sb.appendLine()
        }
        if (result.warnings.isNotEmpty()) {
            sb.appendLine("ПРЕДУПРЕЖДЕНИЯ (настоятельно рекомендуется исправить):")
            result.warnings.forEach { sb.appendLine("  ⚠ $it") }
            sb.appendLine()
        }
        sb.appendLine("Верни исправленную полную спецификацию в тегах <requirements>. Не объясняй изменения — сразу выдавай результат.")
        return sb.toString()
    }

    /**
     * Кросс-проверка: все ли ключевые термины из Confluence присутствуют в спецификации.
     * Возвращает список предупреждений (пустой если покрытие ≥ 50%).
     */
    fun crossCheckConfluence(
        spec: SpecDocument,
        ctx: changelogai.feature.spec.confluence.ConfluenceContext
    ): List<String> {
        if (ctx.keyTerms.isEmpty()) return emptyList()
        val allText = (spec.functional + spec.nonFunctional + spec.acceptanceCriteria + spec.edgeCases)
            .joinToString(" ") { it.description }
            .lowercase()
        val notFound = ctx.keyTerms.filter { term -> term !in allText }
        val coverage = 1.0 - notFound.size.toDouble() / ctx.keyTerms.size
        return if (coverage < 0.5) {
            listOf("Возможно, не все требования из Confluence учтены. " +
                    "Не найдено в спецификации: ${notFound.take(10).joinToString(", ")}")
        } else emptyList()
    }

    // ---- Helpers -------------------------------------------------------

    private fun coversPerformance(text: String) =
        listOf("мс", "ms", "rps", "latency", "время отклика", "response time",
            "секунд", "миллисекунд", "throughput", "tps", "производительност").any { it in text }

    private fun coversSecurity(text: String) =
        listOf("аутентификац", "авторизац", "шифрован", "ssl", "tls", "oauth",
            "jwt", "пароль", "token", "токен", "rbac", "безопасност", "encrypt").any { it in text }

    private fun coversAvailability(text: String) =
        listOf("доступност", "uptime", "sla", "99.", "availability", "отказоустойчив").any { it in text }

    private fun coversScalability(text: String) =
        listOf("масштаб", "scale", "горизонтальн", "vertical", "нагрузк", "инстанц").any { it in text }
}
