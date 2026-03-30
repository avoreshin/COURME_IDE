package changelogai.core.confluence

/**
 * Контекст страницы Confluence, загруженной и распарсенной.
 * Передаётся от UI-слоя в оркестраторы.
 */
data class ConfluenceContext(
    val originalUrl: String,
    val pageId: String,
    val pageTitle: String,
    val plainText: String,
    val keyTerms: List<String>,      // топ-30 ключевых терминов для кросс-проверки
    val canonicalWebUrl: String
) {
    /** Блок для вставки в промпт агента (режим генерации). */
    fun toPromptBlock(): String = buildString {
        appendLine("## Требования из Confluence: $pageTitle")
        appendLine("Ссылка: $canonicalWebUrl")
        appendLine()
        val truncated = plainText.take(8000)
        appendLine(truncated)
        if (plainText.length > 8000) appendLine("...[содержимое обрезано до 8000 символов]")
    }

    /**
     * Эвристика: похоже ли содержимое на готовое ТЗ/спецификацию.
     * Признаки: наличие FR-/NFR-/AC-/EDGE-/Given/When/Then паттернов
     * или нумерованных требований.
     */
    fun looksLikeExistingSpec(): Boolean {
        val text = plainText.take(3000)
        val specPatterns = listOf(
            Regex("FR-\\d{3}", RegexOption.IGNORE_CASE),
            Regex("NFR-\\d{3}", RegexOption.IGNORE_CASE),
            Regex("AC-\\d{3}", RegexOption.IGNORE_CASE),
            Regex("EDGE-\\d{3}", RegexOption.IGNORE_CASE),
            Regex("\\bGiven\\b.*\\bWhen\\b.*\\bThen\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("Функциональные требования", RegexOption.IGNORE_CASE),
            Regex("Нефункциональные требования", RegexOption.IGNORE_CASE),
            Regex("Критерии приёмки", RegexOption.IGNORE_CASE),
            Regex("Acceptance Criteria", RegexOption.IGNORE_CASE),
            Regex("Functional Requirements", RegexOption.IGNORE_CASE)
        )
        return specPatterns.count { it.containsMatchIn(text) } >= 2
    }
}
