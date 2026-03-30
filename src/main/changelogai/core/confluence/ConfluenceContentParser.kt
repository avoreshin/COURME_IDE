package changelogai.core.confluence

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Конвертирует Confluence Storage Format (XML) в читаемый plain text.
 * Поддерживает таблицы, code-блоки, заголовки. Fallback — regex-зачистка.
 */
object ConfluenceContentParser {

    data class ParsedContent(
        val plainText: String,
        val wordCount: Int,
        val keyTerms: List<String>   // топ-30 терминов для кросс-проверки
    )

    fun parse(storageXml: String): ParsedContent {
        val text = try {
            parseXml(storageXml)
        } catch (_: Exception) {
            stripTags(storageXml)
        }
        val normalized = normalizeWhitespace(text)
        return ParsedContent(
            plainText = normalized,
            wordCount = normalized.split("\\s+".toRegex()).count { it.isNotBlank() },
            keyTerms = extractKeyTerms(normalized)
        )
    }

    // ── XML парсинг ────────────────────────────────────────────────────────

    private fun parseXml(xml: String): String {
        val wrapped = "<root>$xml</root>"
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(wrapped)))
        val sb = StringBuilder()
        walkNode(doc.documentElement, sb)
        return sb.toString()
    }

    private fun walkNode(node: Node, sb: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE -> {
                val text = node.nodeValue?.trim() ?: ""
                if (text.isNotBlank()) {
                    sb.append(text).append(" ")
                }
            }
            Node.ELEMENT_NODE -> {
                val el = node as Element
                val tag = el.localName?.lowercase() ?: el.nodeName.lowercase()
                when {
                    tag in SKIP_TAGS -> return
                    tag == "table"   -> renderTable(el, sb)
                    isCodeMacro(el)  -> renderCodeBlock(el, sb)
                    tag in HEADING_TAGS -> {
                        sb.appendLine()
                        walkChildren(el, sb)
                        sb.appendLine()
                    }
                    tag in BLOCK_TAGS -> {
                        sb.appendLine()
                        walkChildren(el, sb)
                        sb.appendLine()
                    }
                    tag == "li" -> {
                        sb.append("• ")
                        walkChildren(el, sb)
                        sb.appendLine()
                    }
                    else -> walkChildren(el, sb)
                }
            }
        }
    }

    private fun walkChildren(node: Node, sb: StringBuilder) {
        val children: NodeList = node.childNodes
        for (i in 0 until children.length) walkNode(children.item(i), sb)
    }

    private fun renderTable(table: Element, sb: StringBuilder) {
        sb.appendLine()
        val rows = table.getElementsByTagName("tr")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? Element ?: continue
            val cells = mutableListOf<String>()
            val childNodes = row.childNodes
            for (j in 0 until childNodes.length) {
                val cell = childNodes.item(j) as? Element ?: continue
                val cellTag = cell.localName?.lowercase() ?: cell.nodeName.lowercase()
                if (cellTag in listOf("td", "th")) {
                    val cellSb = StringBuilder()
                    walkChildren(cell, cellSb)
                    cells += cellSb.toString().replace("\n", " ").trim()
                }
            }
            if (cells.isNotEmpty()) sb.appendLine(cells.joinToString(" | "))
        }
        sb.appendLine()
    }

    private fun isCodeMacro(el: Element): Boolean {
        val name = el.localName?.lowercase() ?: ""
        if (name == "structured-macro" || name == "ac:structured-macro") {
            val macroName = el.getAttribute("ac:name").lowercase()
            return macroName in listOf("code", "noformat")
        }
        return false
    }

    private fun renderCodeBlock(el: Element, sb: StringBuilder) {
        sb.appendLine()
        sb.appendLine("```")
        // Ищем ac:plain-text-body
        val children = el.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            val childTag = child.localName?.lowercase() ?: child.nodeName.lowercase()
            if ("plain-text-body" in childTag) {
                sb.appendLine(child.textContent?.trim())
                break
            }
        }
        sb.appendLine("```")
        sb.appendLine()
    }

    // ── Fallback: regex зачистка ────────────────────────────────────────────

    private fun stripTags(xml: String): String =
        xml.replace(Regex("<[^>]+>"), " ")
           .replace("&nbsp;", " ")
           .replace("&lt;", "<")
           .replace("&gt;", ">")
           .replace("&amp;", "&")
           .replace("&quot;", "\"")

    // ── Нормализация ────────────────────────────────────────────────────────

    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // ── Извлечение ключевых терминов ─────────────────────────────────────────

    private fun extractKeyTerms(text: String): List<String> {
        val stopWords = setOf(
            "что", "как", "для", "при", "или", "если", "когда", "после",
            "до", "над", "под", "чтобы", "это", "этот", "эта", "эти",
            "the", "and", "for", "with", "that", "this", "from", "are",
            "have", "will", "must", "should", "shall", "may", "can"
        )
        return text.split(Regex("[\\s,;:.|!?()\\[\\]{}\"']+"))
            .filter { token ->
                token.length >= 4 &&
                token.any { it.isLetter() } &&
                token.lowercase() !in stopWords &&
                !token.all { it.isDigit() }
            }
            .map { it.lowercase() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(30)
            .map { it.key }
    }

    // ── Константы ───────────────────────────────────────────────────────────

    private val SKIP_TAGS = setOf(
        "style", "script", "parameter", "ac:parameter",
        "default-parameter", "ac:default-parameter"
    )

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "ul", "ol", "blockquote",
        "ac:task-list", "ac:task", "ac:layout", "ac:layout-section", "ac:layout-cell"
    )
}
