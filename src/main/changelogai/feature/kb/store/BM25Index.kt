package changelogai.feature.kb.store

import changelogai.feature.kb.model.KBSearchResult
import changelogai.feature.kb.model.VectorEntry
import kotlin.math.ln

/**
 * In-memory BM25 полнотекстовый индекс.
 * Fallback когда Embedding API не настроен. Работает без внешних зависимостей.
 */
class BM25Index {

    private val entries = mutableListOf<VectorEntry>()
    private val k1 = 1.5f
    private val b  = 0.75f

    val size: Int get() = entries.size

    fun load(data: List<VectorEntry>) {
        entries.clear()
        entries.addAll(data)
    }

    fun clear() { entries.clear() }

    fun search(query: String, topK: Int = 5): List<KBSearchResult> {
        if (entries.isEmpty()) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()

        val n = entries.size.toFloat()

        // Предвычисляем токены всех документов один раз
        val tokenized = entries.map { tokenize(it.text) }

        // IDF для каждого термина запроса
        val idf = queryTerms.associateWith { term ->
            val df = tokenized.count { terms -> term in terms }.toFloat()
            ln(((n - df + 0.5f) / (df + 0.5f) + 1.0f).toDouble()).toFloat()
        }

        val avgLen = tokenized.map { it.size }.average().toFloat().coerceAtLeast(1f)

        return entries.indices
            .map { i ->
                val terms = tokenized[i]
                val tf = terms.groupingBy { it }.eachCount()
                val docLen = terms.size.toFloat()
                val score = queryTerms.sumOf { term ->
                    val tfVal = tf[term]?.toFloat() ?: 0f
                    val idfVal = idf[term] ?: 0f
                    val num = tfVal * (k1 + 1)
                    val den = tfVal + k1 * (1 - b + b * docLen / avgLen)
                    (idfVal * num / den).toDouble()
                }.toFloat()
                entries[i] to score
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (entry, score) ->
                KBSearchResult(
                    chunkText = entry.text,
                    score = score.coerceAtMost(1f),
                    pageTitle = entry.pageTitle,
                    pageUrl = entry.pageUrl,
                    heading = entry.heading
                )
            }
    }

    // Русско-английский токенизатор: lowercase, разбивка по не-буквам, без стоп-слов
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^а-яёa-z0-9]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }

    companion object {
        private val STOP_WORDS = setOf(
            "и","в","во","не","что","он","на","я","с","со","как","а","то","все","она","так",
            "его","но","да","ты","к","у","же","вы","за","бы","по","только","ее","мне","было",
            "вот","от","меня","еще","нет","о","из","ему","теперь","когда","даже","ну","вдруг",
            "ли","если","уже","или","ни","быть","был","него","до","вас","нибудь","опять","уж",
            "вам","ведь","там","потом","себя","ничего","ей","может","они","тут","где","есть",
            "надо","ней","для","мы","тебя","их","чем","была","сам","чтоб","без","будто","чего",
            "раз","тоже","себе","под","будет","ж","тогда","кто","этот","того","потому","этого",
            "какой","совсем","ним","здесь","этом","один","почти","мой","тем","чтобы","нее","сейчас",
            "были","куда","зачем","всех","никогда","можно","при","наконец","два","об","другой",
            "хоть","после","над","больше","тот","через","эти","нас","про","всего","них","какая",
            "много","разве","три","эту","моя","впрочем","хорошо","свою","этой","перед","иногда",
            "лучше","чуть","том","нельзя","такой","им","более","всегда","конечно","всю","между",
            "the","a","an","is","are","was","were","be","been","being","have","has","had","do",
            "does","did","will","would","could","should","may","might","shall","can","to","of",
            "in","on","at","by","for","with","about","as","from","that","this","it","its"
        )
    }
}
