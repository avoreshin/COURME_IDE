package changelogai.feature.kb.chunking

/**
 * Heading-aware разбиение текста на чанки с overlap.
 *
 * Алгоритм:
 * 1. Разбивает текст по заголовкам (##, ===, ---)
 * 2. Секция 500-1000 токенов → один чанк
 * 3. Секция > 1000 токенов → делится на ~750 с overlap 100
 * 4. Секция < 200 токенов → мержится с соседней
 */
object TextChunker {

    data class ChunkResult(
        val text: String,
        val headingPath: String,
        val charOffset: Int,
        val chunkIndex: Int
    )

    private val HEADING_PATTERN = Regex("""^(#{1,6}\s+.+|.+\n[=\-]{3,})""", RegexOption.MULTILINE)

    fun chunk(
        plainText: String,
        maxTokens: Int = 750,
        overlapTokens: Int = 100,
        minTokens: Int = 200
    ): List<ChunkResult> {
        if (plainText.isBlank()) return emptyList()

        val sections = splitBySections(plainText)
        val merged = mergeSections(sections, minTokens)
        val chunks = mutableListOf<ChunkResult>()
        var chunkIndex = 0

        for (section in merged) {
            val tokenEstimate = estimateTokens(section.text)
            if (tokenEstimate <= maxTokens) {
                chunks.add(ChunkResult(
                    text = section.text.trim(),
                    headingPath = section.heading,
                    charOffset = section.charOffset,
                    chunkIndex = chunkIndex++
                ))
            } else {
                // Разбиваем большую секцию на части с overlap
                val subChunks = splitWithOverlap(section.text, maxTokens, overlapTokens)
                for (sub in subChunks) {
                    chunks.add(ChunkResult(
                        text = sub.trim(),
                        headingPath = section.heading,
                        charOffset = section.charOffset,
                        chunkIndex = chunkIndex++
                    ))
                }
            }
        }

        return chunks.filter { it.text.isNotBlank() }
    }

    private data class Section(
        val heading: String,
        val text: String,
        val charOffset: Int
    )

    private fun splitBySections(text: String): List<Section> {
        val lines = text.lines()
        val sections = mutableListOf<Section>()
        var currentHeading = ""
        val currentText = StringBuilder()
        var sectionStart = 0
        var charPos = 0

        for (line in lines) {
            if (isHeading(line)) {
                if (currentText.isNotEmpty()) {
                    sections.add(Section(currentHeading, currentText.toString(), sectionStart))
                }
                currentHeading = line.trim().removePrefix("#").trim()
                currentText.clear()
                sectionStart = charPos
            }
            currentText.appendLine(line)
            charPos += line.length + 1 // +1 for newline
        }

        if (currentText.isNotEmpty()) {
            sections.add(Section(currentHeading, currentText.toString(), sectionStart))
        }

        return sections
    }

    private fun isHeading(line: String): Boolean {
        return line.matches(Regex("^#{1,6}\\s+.+")) ||
                line.matches(Regex("^[=]{3,}$")) ||
                line.matches(Regex("^[-]{3,}$"))
    }

    private fun mergeSections(sections: List<Section>, minTokens: Int): List<Section> {
        if (sections.isEmpty()) return emptyList()

        val merged = mutableListOf<Section>()
        var pending: Section? = null

        for (section in sections) {
            if (pending == null) {
                pending = section
                continue
            }

            if (estimateTokens(pending.text) < minTokens) {
                // Мержим с текущей секцией
                val combinedHeading = if (pending.heading.isNotBlank() && section.heading.isNotBlank())
                    "${pending.heading} > ${section.heading}" else section.heading.ifBlank { pending.heading }
                pending = Section(
                    heading = combinedHeading,
                    text = pending.text + "\n" + section.text,
                    charOffset = pending.charOffset
                )
            } else {
                merged.add(pending)
                pending = section
            }
        }
        if (pending != null) merged.add(pending)

        return merged
    }

    private fun splitWithOverlap(text: String, maxTokens: Int, overlapTokens: Int): List<String> {
        val maxChars = maxTokens * 4
        val overlapChars = overlapTokens * 4
        val chunks = mutableListOf<String>()

        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)

            // Пытаемся разрезать по границе предложения
            if (end < text.length) {
                val sentenceBreak = text.lastIndexOf(". ", end)
                if (sentenceBreak > start + maxChars / 2) {
                    end = sentenceBreak + 1
                }
            }

            chunks.add(text.substring(start, end))
            start = (end - overlapChars).coerceAtLeast(start + 1)
            if (start >= text.length) break
        }

        return chunks
    }

    internal fun estimateTokens(text: String): Int = text.length / 4
}
