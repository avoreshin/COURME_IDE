package changelogai.feature.kb.model

/**
 * Тип источника для индексации базы знаний.
 */
enum class KBSourceType {
    SPACE,      // Все страницы из Confluence Space
    PAGE_TREE,  // Дерево страниц от корневой
    MANUAL      // Список отдельных URL
}

/**
 * Конфигурация источника данных для KB.
 */
data class KBSource(
    val type: KBSourceType,
    val spaceKey: String = "",
    val rootPageUrl: String = "",
    val manualUrls: List<String> = emptyList()
)
