package changelogai.feature.kb.store

import changelogai.feature.kb.model.KBSearchResult
import changelogai.feature.kb.model.VectorEntry

/**
 * In-memory индекс для поиска по косинусному сходству.
 * Оптимизирован для <10K чанков (поиск ~1ms).
 */
class VectorIndex {

    private val entries = mutableListOf<VectorEntry>()

    val size: Int get() = entries.size

    fun load(data: List<VectorEntry>) {
        entries.clear()
        entries.addAll(data)
    }

    fun clear() {
        entries.clear()
    }

    fun search(queryEmbedding: FloatArray, topK: Int = 5, minScore: Float = 0.3f): List<KBSearchResult> {
        if (entries.isEmpty()) return emptyList()

        val queryNorm = norm(queryEmbedding)
        if (queryNorm == 0f) return emptyList()

        return entries
            .map { entry ->
                val score = cosineSimilarity(queryEmbedding, entry.embedding, queryNorm)
                entry to score
            }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (entry, score) ->
                KBSearchResult(
                    chunkText = entry.text,
                    score = score,
                    pageTitle = entry.pageTitle,
                    pageUrl = entry.pageUrl,
                    heading = entry.heading
                )
            }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray, aNorm: Float): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var bNormSq = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            bNormSq += b[i] * b[i]
        }
        val bNorm = kotlin.math.sqrt(bNormSq)
        if (bNorm == 0f) return 0f
        return dot / (aNorm * bNorm)
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return kotlin.math.sqrt(sum)
    }
}
