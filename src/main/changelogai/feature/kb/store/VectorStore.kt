package changelogai.feature.kb.store

import changelogai.feature.kb.model.KBIndexState
import changelogai.feature.kb.model.KBSource
import changelogai.feature.kb.model.KBSourceType
import changelogai.feature.kb.model.VectorEntry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Файловое хранилище векторного индекса в JSONL-формате.
 *
 * Файлы:
 * - .idea/changelogai/kb-index.jsonl — чанки + эмбеддинги
 * - .idea/changelogai/kb-meta.json   — метаданные индекса
 */
class VectorStore(projectBasePath: String) {

    private val baseDir = Path.of(projectBasePath, ".idea", "changelogai")
    private val indexFile = baseDir.resolve("kb-index.jsonl")
    private val metaFile = baseDir.resolve("kb-meta.json")
    private val mapper = jacksonObjectMapper()

    fun exists(): Boolean = Files.exists(indexFile) && Files.exists(metaFile)

    fun save(entries: List<VectorEntry>, meta: KBIndexState) {
        ensureDir()
        // Записываем индекс
        indexFile.toFile().bufferedWriter().use { writer ->
            for (entry in entries) {
                writer.write(mapper.writeValueAsString(entryToMap(entry)))
                writer.newLine()
            }
        }
        // Записываем метаданные
        metaFile.toFile().writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metaToMap(meta)))
    }

    fun load(): Pair<List<VectorEntry>, KBIndexState?> {
        if (!exists()) return emptyList<VectorEntry>() to null

        val entries = indexFile.toFile().bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }
                .map { line -> mapToEntry(mapper.readValue<Map<String, Any>>(line)) }
                .toList()
        }

        val meta = try {
            val metaMap = mapper.readValue<Map<String, Any>>(metaFile.toFile().readText())
            mapToMeta(metaMap)
        } catch (_: Exception) {
            null
        }

        return entries to meta
    }

    fun appendEntries(newEntries: List<VectorEntry>) {
        ensureDir()
        Files.newBufferedWriter(indexFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { writer ->
            for (entry in newEntries) {
                writer.write(mapper.writeValueAsString(entryToMap(entry)))
                writer.newLine()
            }
        }
    }

    fun removeByPageId(pageId: String) {
        if (!Files.exists(indexFile)) return
        val remaining = indexFile.toFile().bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }
                .filter { line ->
                    try {
                        val map = mapper.readValue<Map<String, Any>>(line)
                        map["pageId"] != pageId
                    } catch (_: Exception) { true }
                }
                .toList()
        }
        indexFile.toFile().writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
    }

    fun updateMeta(meta: KBIndexState) {
        ensureDir()
        metaFile.toFile().writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metaToMap(meta)))
    }

    fun clear() {
        runCatching { Files.deleteIfExists(indexFile) }
        runCatching { Files.deleteIfExists(metaFile) }
    }

    private fun ensureDir() {
        Files.createDirectories(baseDir)
    }

    // ---- Serialization helpers ----

    private fun entryToMap(e: VectorEntry): Map<String, Any> = mapOf(
        "pageId" to e.pageId,
        "pageTitle" to e.pageTitle,
        "heading" to e.heading,
        "chunkIndex" to e.chunkIndex,
        "text" to e.text,
        "pageUrl" to e.pageUrl,
        "embedding" to e.embedding.toList()
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapToEntry(m: Map<String, Any>): VectorEntry {
        val embeddingList = m["embedding"] as? List<Number> ?: emptyList()
        return VectorEntry(
            pageId = m["pageId"]?.toString() ?: "",
            pageTitle = m["pageTitle"]?.toString() ?: "",
            heading = m["heading"]?.toString() ?: "",
            chunkIndex = (m["chunkIndex"] as? Number)?.toInt() ?: 0,
            text = m["text"]?.toString() ?: "",
            pageUrl = m["pageUrl"]?.toString() ?: "",
            embedding = FloatArray(embeddingList.size) { embeddingList[it].toFloat() }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun metaToMap(meta: KBIndexState): Map<String, Any> = mapOf(
        "sourceType" to meta.source.type.name,
        "spaceKey" to meta.source.spaceKey,
        "rootPageUrl" to meta.source.rootPageUrl,
        "manualUrls" to meta.source.manualUrls,
        "pageCount" to meta.pageCount,
        "chunkCount" to meta.chunkCount,
        "lastIndexedAt" to meta.lastIndexedAt,
        "pageVersions" to meta.pageVersions
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapToMeta(m: Map<String, Any>): KBIndexState {
        val sourceType = try { KBSourceType.valueOf(m["sourceType"]?.toString() ?: "MANUAL") } catch (_: Exception) { KBSourceType.MANUAL }
        return KBIndexState(
            source = KBSource(
                type = sourceType,
                spaceKey = m["spaceKey"]?.toString() ?: "",
                rootPageUrl = m["rootPageUrl"]?.toString() ?: "",
                manualUrls = (m["manualUrls"] as? List<String>) ?: emptyList()
            ),
            pageCount = (m["pageCount"] as? Number)?.toInt() ?: 0,
            chunkCount = (m["chunkCount"] as? Number)?.toInt() ?: 0,
            lastIndexedAt = (m["lastIndexedAt"] as? Number)?.toLong() ?: 0,
            pageVersions = (m["pageVersions"] as? Map<String, Number>)?.mapValues { it.value.toInt() } ?: emptyMap()
        )
    }
}
