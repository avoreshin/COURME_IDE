package changelogai.core.confluence

import changelogai.core.mcp.McpEntry
import changelogai.core.mcp.McpState
import changelogai.core.settings.PluginState
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Точка входа для загрузки содержимого страницы Confluence.
 * Находит credentials из McpState, парсит URL, загружает и конвертирует контент.
 */
object ConfluenceFetcher {

    sealed class FetchResult {
        data class Success(val ctx: ConfluenceContext) : FetchResult()
        /** Нет настроенного Confluence MCP — нужно предложить настройку. */
        data class NoCredentials(val detectedBaseUrl: String) : FetchResult()
        data class Error(val message: String) : FetchResult()
        object EmptyPage : FetchResult()
    }

    /**
     * Возвращает пару (baseUrl, token) для страницы, или null если credentials не найдены.
     * Используется внешними компонентами (например, для загрузки вложений).
     */
    fun resolveForPage(rawUrl: String): Pair<String, String>? {
        val parsed = ConfluenceUrlParser.parse(rawUrl) ?: return null
        val entry = findEntry(parsed.baseUrl) ?: return null
        return resolveCredentials(entry, parsed.baseUrl)
    }

    fun fetch(rawUrl: String): FetchResult {
        // 1. Парсим URL
        val parsed = ConfluenceUrlParser.parse(rawUrl)
            ?: return FetchResult.Error("Не удалось распознать URL Confluence. " +
                    "Поддерживаются форматы: cloud (atlassian.net/wiki), on-prem (?pageId=...), short link (/wiki/x/...)")

        // 2. Ищем credentials
        val entry = findEntry(parsed.baseUrl)
            ?: return FetchResult.NoCredentials(parsed.baseUrl)

        val (baseUrl, token) = resolveCredentials(entry, parsed.baseUrl)

        // 3. Создаём REST-клиент (с fallback на skipTls при SSL-ошибке)
        val pluginCert = PluginState.getInstance().aiCertPath.takeIf { it.isNotBlank() }
        fun makeClient(skipTls: Boolean, cert: String?) = ConfluenceRestClient(
            baseUrl = baseUrl,
            token = token,
            skipTls = skipTls,
            cert = cert
        )
        val clientSkipTls = entry.skipCertVerify
        val clientCert = entry.certificate.takeIf { it.isNotBlank() } ?: pluginCert
        val client = makeClient(clientSkipTls, clientCert)

        // 4. Резолвим short link если нужно
        val pageId = if (parsed.isShortLink) {
            client.resolveShortLink(parsed.pageId)
                ?: return FetchResult.Error("Не удалось резолвить short link: ${parsed.pageId}")
        } else {
            parsed.pageId
        }

        // 5. Загружаем страницу (при SSL-ошибке — retry с skipTls)
        val pageData = try {
            client.fetchPage(pageId)
        } catch (e: Exception) {
            if (!clientSkipTls && e.message?.contains("PKIX", ignoreCase = true) == true) {
                try {
                    makeClient(skipTls = true, cert = null).fetchPage(pageId)
                } catch (e2: Exception) {
                    return FetchResult.Error("Ошибка загрузки страницы: ${e2.message}")
                }
            } else {
                return FetchResult.Error("Ошибка загрузки страницы: ${e.message}")
            }
        }

        // 6. Парсим контент
        val parsed2 = try {
            ConfluenceContentParser.parse(pageData.storageBody)
        } catch (e: Exception) {
            return FetchResult.Error("Ошибка разбора содержимого: ${e.message}")
        }

        if (parsed2.plainText.isBlank()) return FetchResult.EmptyPage

        val ctx = ConfluenceContext(
            originalUrl = rawUrl,
            pageId = pageId,
            pageTitle = pageData.title,
            plainText = parsed2.plainText.take(8000),
            keyTerms = parsed2.keyTerms,
            canonicalWebUrl = pageData.webUrl.ifBlank { rawUrl }
        )
        return FetchResult.Success(ctx)
    }

    /**
     * Ищет подходящий McpEntry для Confluence.
     * Приоритеты:
     *  1. preset="confluence" или "jira" с совпадением хоста
     *  2. preset="confluence" или "jira" (любой)
     *  3. Любой enabled сервер с совпадением хоста
     *  4. Любой enabled сервер (первый попавшийся)
     */
    private fun findEntry(baseUrl: String): McpEntry? {
        val servers = McpState.getInstance().servers.filter { it.enabled }
        if (servers.isEmpty()) return null

        val baseHost = baseUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore("/").lowercase()

        fun entryHost(e: McpEntry): String =
            (if (e.type == "HTTP") e.url else extractHostFromEnv(e))
                .removePrefix("https://").removePrefix("http://")
                .substringBefore("/").lowercase()

        // 1. Atlassian preset + совпадение хоста
        servers.firstOrNull { (it.preset == "confluence" || it.preset == "jira") && entryHost(it) == baseHost }
            ?.let { return it }

        // 2. Atlassian preset (любой)
        servers.firstOrNull { it.preset == "confluence" || it.preset == "jira" }
            ?.let { return it }

        // 3. Любой сервер с совпадением хоста
        servers.firstOrNull { entryHost(it) == baseHost }?.let { return it }

        // 4. Любой enabled сервер — credentials берём из URL страницы
        return servers.first()
    }

    /**
     * Извлекает baseUrl и token из McpEntry.
     * Поддерживает оба типа: HTTP (url/accessToken) и STDIO (envJson с ATLASSIAN_URL/TOKEN).
     */
    private fun resolveCredentials(entry: McpEntry, fallbackBaseUrl: String): Pair<String, String> {
        return if (entry.type == "HTTP") {
            val url = entry.url.ifBlank { fallbackBaseUrl }
            url to entry.accessToken
        } else {
            // STDIO: credentials в envJson
            val env = parseEnvJson(entry.envJson)
            val url = (env["CONFLUENCE_URL"] ?: env["ATLASSIAN_URL"])
                ?.ifBlank { fallbackBaseUrl } ?: fallbackBaseUrl
            val token = env["CONFLUENCE_PERSONAL_TOKEN"]
                ?: env["ATLASSIAN_API_TOKEN"]
                ?: env["ATLASSIAN_TOKEN"]
                ?: entry.accessToken.ifBlank { "" }
            url to token
        }
    }

    private fun extractHostFromEnv(entry: McpEntry): String {
        val env = parseEnvJson(entry.envJson)
        return env["CONFLUENCE_URL"] ?: env["ATLASSIAN_URL"] ?: env["JIRA_URL"] ?: ""
    }

    private fun parseEnvJson(envJson: String): Map<String, String> {
        if (envJson.isBlank() || envJson == "{}") return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            jacksonObjectMapper()
                .readValue(envJson, Map::class.java)
                .entries.associate { it.key.toString() to it.value.toString() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
