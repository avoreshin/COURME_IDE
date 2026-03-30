package changelogai.feature.kb.model

/**
 * Одна запись в векторном хранилище: чанк текста + его эмбеддинг.
 */
data class VectorEntry(
    val pageId: String,
    val pageTitle: String,
    val heading: String,
    val chunkIndex: Int,
    val text: String,
    val pageUrl: String = "",
    val embedding: FloatArray = FloatArray(0)  // пустой = BM25-режим без эмбеддинга
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorEntry) return false
        return pageId == other.pageId && chunkIndex == other.chunkIndex
    }

    override fun hashCode(): Int = 31 * pageId.hashCode() + chunkIndex
}
