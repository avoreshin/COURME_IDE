package changelogai.feature.gigacodeae.orchestrator

/**
 * Приблизительная оценка токенов и контроль бюджета.
 * EN: ~4 chars/token, RU/CJK: ~2 chars/token.
 */
class TokenBudget(private val maxTokens: Int = 8000) {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var latinChars = 0
        var nonLatinChars = 0
        for (ch in text) {
            if (ch.code <= 0x7F) latinChars++ else nonLatinChars++
        }
        return (latinChars / 4) + (nonLatinChars / 2) + 1
    }

    fun fits(text: String): Boolean = estimate(text) <= maxTokens

    fun truncateToFit(text: String, reserveTokens: Int = 500): String {
        val limit = maxTokens - reserveTokens
        if (limit <= 0) return ""
        if (estimate(text) <= limit) return text
        // Бинарный поиск по длине символов
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (estimate(text.substring(0, mid)) <= limit) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + "\n…[обрезано]"
    }
}
