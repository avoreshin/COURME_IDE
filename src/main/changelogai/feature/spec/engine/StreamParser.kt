package changelogai.feature.spec.engine

import changelogai.feature.spec.model.ClarificationQuestion
import changelogai.feature.spec.model.SpecDocument

/**
 * Инкрементальный парсер XML-тегов из стримингового текста LLM.
 * Текст приходит кусками — парсер накапливает буфер и выдаёт события по мере нахождения тегов.
 *
 * Поддерживаемые теги:
 *   <thinking>...</thinking>
 *   <clarification><question id="Q1">...</question><options>a | b</options></clarification>
 *   <requirements><functional>...</functional><non_functional>...</non_functional>
 *                 <acceptance_criteria>...</acceptance_criteria><edge_cases>...</edge_cases></requirements>
 */
class StreamParser {
    private val buf = StringBuilder()

    sealed class ParseEvent {
        data class ThinkingStep(val text: String) : ParseEvent()
        data class QuestionFound(val question: ClarificationQuestion) : ParseEvent()
        data class SpecReady(val spec: SpecDocument) : ParseEvent()
        data class RawText(val text: String) : ParseEvent()
    }

    fun feed(chunk: String): List<ParseEvent> {
        buf.append(chunk)
        return drain()
    }

    fun finalize(): List<ParseEvent> {
        val remaining = buf.toString().trim()
        buf.clear()
        return if (remaining.isNotEmpty()) listOf(ParseEvent.RawText(remaining)) else emptyList()
    }

    fun reset() {
        buf.clear()
    }

    // ------------------------------------------------------------------ //

    private fun drain(): List<ParseEvent> {
        val events = mutableListOf<ParseEvent>()
        var progress = true
        while (progress) {
            progress = false
            val text = buf.toString()

            // Ищем ближайший открывающий тег
            val openIdx = findFirstOpenTag(text) ?: break
            val tagName = extractTagName(text, openIdx) ?: break
            val closeTag = "</$tagName>"
            val closeIdx = text.indexOf(closeTag, openIdx)
            if (closeIdx < 0) break  // тег ещё не закрылся — ждём

            val before = text.substring(0, openIdx).trim()
            if (before.isNotEmpty()) events.add(ParseEvent.RawText(before))

            val inner = text.substring(openIdx + "<$tagName>".length, closeIdx)

            when (tagName) {
                "thinking" -> events.add(ParseEvent.ThinkingStep(inner.trim()))
                "clarification" -> parseClarification(inner)?.let { events.add(it) }
                "requirements" -> parseRequirements(inner)?.let { events.add(it) }
                else -> events.add(ParseEvent.RawText(inner.trim()))
            }

            val after = text.substring(closeIdx + closeTag.length)
            buf.clear()
            buf.append(after)
            progress = true
        }
        return events
    }

    private fun findFirstOpenTag(text: String): Int? {
        val tags = listOf("thinking", "clarification", "requirements")
        return tags.mapNotNull { t ->
            val idx = text.indexOf("<$t>")
            if (idx >= 0) idx else null
        }.minOrNull()
    }

    private fun extractTagName(text: String, openIdx: Int): String? {
        if (openIdx < 0 || openIdx >= text.length) return null
        val sub = text.substring(openIdx + 1)
        val end = sub.indexOf('>')
        if (end < 0) return null
        return sub.substring(0, end).trim()
    }

    // ---- <clarification> -----------------------------------------------

    private fun parseClarification(inner: String): ParseEvent.QuestionFound? {
        val qMatch = Regex("""<question\s+id="([^"]+)">([\s\S]*?)</question>""").find(inner)
            ?: return null
        val id = qMatch.groupValues[1]
        val questionText = qMatch.groupValues[2].trim()
        val optText = Regex("""<options>([\s\S]*?)</options>""").find(inner)?.groupValues?.get(1)
        val options = optText?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return ParseEvent.QuestionFound(ClarificationQuestion(id = id, text = questionText, options = options))
    }

    // ---- <requirements> ------------------------------------------------

    private fun parseRequirements(inner: String): ParseEvent.SpecReady? {
        val title = Regex("""<title>([\s\S]*?)</title>""").find(inner)?.groupValues?.get(1)?.trim() ?: "Спецификация"
        val functional = parseReqs(inner, "functional", "req")
        val nonFunctional = parseReqs(inner, "non_functional", "req")
        val ac = parseAC(inner)
        val edge = parseReqs(inner, "edge_cases", "edge")
        if (functional.isEmpty() && nonFunctional.isEmpty() && ac.isEmpty() && edge.isEmpty()) return null
        return ParseEvent.SpecReady(SpecDocument(title, functional, nonFunctional, ac, edge))
    }

    private fun parseReqs(xml: String, section: String, tag: String): List<SpecDocument.Requirement> {
        val sectionMatch = Regex("""<$section>([\s\S]*?)</$section>""").find(xml) ?: return emptyList()
        val sectionXml = sectionMatch.groupValues[1]
        return Regex("""<$tag\s+id="([^"]+)"(?:\s+priority="([^"]+)")?\s*>([\s\S]*?)</$tag>""")
            .findAll(sectionXml)
            .map { m ->
                SpecDocument.Requirement(
                    id = m.groupValues[1],
                    description = m.groupValues[3].trim(),
                    priority = parsePriority(m.groupValues[2])
                )
            }.toList()
    }

    private fun parseAC(xml: String): List<SpecDocument.Requirement> {
        val sectionMatch = Regex("""<acceptance_criteria>([\s\S]*?)</acceptance_criteria>""").find(xml)
            ?: return emptyList()
        val sectionXml = sectionMatch.groupValues[1]
        return Regex("""<ac\s+id="([^"]+)">([\s\S]*?)</ac>""")
            .findAll(sectionXml)
            .map { m ->
                SpecDocument.Requirement(
                    id = m.groupValues[1],
                    description = m.groupValues[2].trim()
                )
            }.toList()
    }

    private fun parsePriority(s: String): SpecDocument.Priority = when (s.uppercase()) {
        "CRITICAL" -> SpecDocument.Priority.CRITICAL
        "HIGH"     -> SpecDocument.Priority.HIGH
        "LOW"      -> SpecDocument.Priority.LOW
        else       -> SpecDocument.Priority.MEDIUM
    }
}
