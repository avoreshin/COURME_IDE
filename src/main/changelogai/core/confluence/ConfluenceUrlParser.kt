package changelogai.core.confluence

/**
 * Парсит URL страницы Confluence и извлекает pageId и baseUrl.
 * Поддерживает три формата:
 *  - Cloud: https://COMPANY.atlassian.net/wiki/spaces/SPACE/pages/{pageId}/...
 *  - On-prem: https://host/pages/viewpage.action?pageId={pageId}
 *  - Short link: https://COMPANY.atlassian.net/wiki/x/{shortKey}
 */
object ConfluenceUrlParser {

    data class ParsedConfluenceUrl(
        val baseUrl: String,       // например "https://company.atlassian.net/wiki" или "https://confluence.company.ru"
        val pageId: String,        // числовой ID страницы или ключ short link
        val isShortLink: Boolean   // true → нужно резолвить через redirect
    )

    // Cloud canonical: https://company.atlassian.net/wiki/spaces/SPACE/pages/12345/...
    private val cloudPattern = Regex(
        """https?://([^/]+\.atlassian\.net)/wiki/spaces/[^/]+/pages/(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // On-prem viewpage: https://confluence.company.ru/pages/viewpage.action?pageId=12345
    private val onPremPattern = Regex(
        """https?://([^/?]+)(?:/[^?]*)?/pages/viewpage\.action[^?]*\?.*pageId=(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // On-prem display: https://confluence.company.ru/display/SPACE/Page+Title (no pageId visible)
    private val onPremDisplayPattern = Regex(
        """https?://([^/?]+)/display/([^/]+)/""",
        RegexOption.IGNORE_CASE
    )

    // Short link: https://company.atlassian.net/wiki/x/AbCd
    private val shortPattern = Regex(
        """https?://([^/]+\.atlassian\.net)/wiki/x/([A-Za-z0-9_\-]+)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(url: String): ParsedConfluenceUrl? {
        val trimmed = url.trim()

        // Cloud canonical
        cloudPattern.find(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val pageId = m.groupValues[2]
            return ParsedConfluenceUrl(
                baseUrl = "https://$host/wiki",
                pageId = pageId,
                isShortLink = false
            )
        }

        // On-prem viewpage
        onPremPattern.find(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val pageId = m.groupValues[2]
            return ParsedConfluenceUrl(
                baseUrl = "https://$host",
                pageId = pageId,
                isShortLink = false
            )
        }

        // Short link
        shortPattern.find(trimmed)?.let { m ->
            val host = m.groupValues[1]
            val shortKey = m.groupValues[2]
            return ParsedConfluenceUrl(
                baseUrl = "https://$host/wiki",
                pageId = shortKey,
                isShortLink = true
            )
        }

        return null
    }
}
