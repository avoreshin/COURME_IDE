package changelogai.feature.kb.model

/**
 * Результат семантического поиска по базе знаний.
 */
data class KBSearchResult(
    val chunkText: String,
    val score: Float,
    val pageTitle: String,
    val pageUrl: String,
    val heading: String = ""
)
