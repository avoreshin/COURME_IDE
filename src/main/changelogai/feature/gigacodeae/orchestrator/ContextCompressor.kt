package changelogai.feature.gigacodeae.orchestrator

import changelogai.core.llm.model.ChatMessage

/**
 * Сжимает историю разговора и файловый контент для уменьшения нагрузки на контекст LLM.
 */
class ContextCompressor(private val budget: TokenBudget = TokenBudget()) {

    companion object {
        /** Количество последних пар сообщений, которые сохраняются verbatim */
        const val KEEP_RECENT_PAIRS = 4

        /** Максимум символов при инъекции @file */
        const val MAX_FILE_CHUNK_CHARS = 3000

        /** Максимум символов в результате tool call */
        const val MAX_TOOL_RESULT_CHARS = 2000
    }

    /**
     * Сжимает историю.
     * @return [CompressedHistory] с summary старых сообщений + последние N сообщений
     */
    fun compressHistory(
        allMessages: List<ChatMessage>,
        existingSummary: String?
    ): CompressedHistory {
        // Фильтруем только user/assistant сообщения для подсчёта пар
        val totalPairs = allMessages.count { it.role == "user" }

        if (totalPairs <= KEEP_RECENT_PAIRS) {
            return CompressedHistory(
                summary = existingSummary,
                recentMessages = allMessages,
                needsSummarization = false
            )
        }

        // Находим точку разделения: оставляем последние KEEP_RECENT_PAIRS пар user+assistant
        val recentMessages = takeLastPairs(allMessages, KEEP_RECENT_PAIRS)
        val oldMessages = allMessages.dropLast(recentMessages.size)

        return CompressedHistory(
            summary = existingSummary,
            recentMessages = recentMessages,
            oldMessages = if (existingSummary == null) oldMessages else emptyList(),
            needsSummarization = existingSummary == null && oldMessages.isNotEmpty()
        )
    }

    /**
     * Обрезает содержимое файла до релевантных частей.
     * Ищет ключевые слова из запроса пользователя и берёт окна вокруг них.
     */
    fun chunkFileContent(content: String, query: String): String {
        if (content.length <= MAX_FILE_CHUNK_CHARS) return content

        val lines = content.lines()
        val keywords = extractKeywords(query)

        if (keywords.isEmpty()) {
            // Без ключевых слов — берём начало файла
            return truncateLines(lines, MAX_FILE_CHUNK_CHARS)
        }

        // Ищем строки с ключевыми словами
        val matchedLineIndices = mutableSetOf<Int>()
        for ((idx, line) in lines.withIndex()) {
            val lower = line.lowercase()
            if (keywords.any { it in lower }) {
                matchedLineIndices.add(idx)
            }
        }

        if (matchedLineIndices.isEmpty()) {
            return truncateLines(lines, MAX_FILE_CHUNK_CHARS)
        }

        // Берём окна ±10 строк вокруг совпадений
        val windowSize = 10
        val includedLines = mutableSetOf<Int>()
        for (idx in matchedLineIndices) {
            for (i in (idx - windowSize).coerceAtLeast(0)..(idx + windowSize).coerceAtMost(lines.lastIndex)) {
                includedLines.add(i)
            }
        }

        val result = StringBuilder()
        var prevIdx = -2
        for (idx in includedLines.sorted()) {
            if (idx != prevIdx + 1 && prevIdx >= 0) {
                result.appendLine("  …[пропущено]…")
            }
            result.appendLine(lines[idx])
            prevIdx = idx
            if (result.length >= MAX_FILE_CHUNK_CHARS) break
        }
        return result.toString()
    }

    /**
     * Обрезает результат tool call до разумного размера.
     */
    fun compressToolResult(result: String): String {
        if (result.length <= MAX_TOOL_RESULT_CHARS) return result
        return result.take(MAX_TOOL_RESULT_CHARS) + "\n…[обрезано, показано ${MAX_TOOL_RESULT_CHARS} из ${result.length} символов]"
    }

    /**
     * Форматирует старые сообщения для отправки в SummarizerAgent.
     */
    fun formatMessagesForSummarization(messages: List<ChatMessage>): String {
        return messages
            .filter { it.role in setOf("user", "assistant") }
            .joinToString("\n") { msg ->
                val prefix = if (msg.role == "user") "User" else "Assistant"
                val text = msg.content?.take(300) ?: "[tool call]"
                "$prefix: $text"
            }
    }

    private fun takeLastPairs(messages: List<ChatMessage>, pairCount: Int): List<ChatMessage> {
        // Считаем user-сообщения с конца
        var usersFound = 0
        var splitIdx = messages.size
        for (i in messages.indices.reversed()) {
            if (messages[i].role == "user") {
                usersFound++
                if (usersFound >= pairCount) {
                    splitIdx = i
                    break
                }
            }
        }
        return messages.subList(splitIdx, messages.size)
    }

    private fun extractKeywords(query: String): List<String> {
        val stopWords = setOf(
            "и", "в", "на", "с", "по", "из", "к", "за", "от", "для", "не", "что", "как",
            "это", "the", "a", "an", "is", "in", "on", "at", "to", "for", "of", "with"
        )
        return query.lowercase()
            .split(Regex("[\\s@`\"'.,;:!?(){}\\[\\]]+"))
            .filter { it.length > 2 && it !in stopWords }
    }

    private fun truncateLines(lines: List<String>, maxChars: Int): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length + line.length > maxChars) {
                sb.appendLine("…[обрезано]")
                break
            }
            sb.appendLine(line)
        }
        return sb.toString()
    }
}

data class CompressedHistory(
    val summary: String?,
    val recentMessages: List<ChatMessage>,
    val oldMessages: List<ChatMessage> = emptyList(),
    val needsSummarization: Boolean = false
)
