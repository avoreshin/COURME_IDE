package changelogai.feature.kb.model

/**
 * Метаданные текущего индекса базы знаний.
 */
data class KBIndexState(
    val source: KBSource,
    val pageCount: Int = 0,
    val chunkCount: Int = 0,
    val lastIndexedAt: Long = 0,
    val pageVersions: Map<String, Int> = emptyMap()  // pageId -> version
)
