package changelogai.feature.kb

import changelogai.core.settings.PluginState
import changelogai.feature.kb.chunking.TextChunker
import changelogai.feature.kb.confluence.ConfluenceBulkFetcher
import changelogai.feature.kb.embedding.EmbeddingClient
import changelogai.feature.kb.model.*
import changelogai.feature.kb.store.BM25Index
import changelogai.feature.kb.store.VectorIndex
import changelogai.feature.kb.store.VectorStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level сервис базы знаний.
 * Оркестрирует полный pipeline: загрузка → чанкинг → эмбеддинги → хранение → поиск.
 */
@Service(Service.Level.PROJECT)
class KnowledgeBaseService(private val project: Project) {

    private val store by lazy { VectorStore(project.basePath ?: ".") }
    private val index = VectorIndex()
    private val bm25Index = BM25Index()
    private var loaded = false

    // ---- Indexing ----

    /**
     * Индексирует Confluence Space.
     */
    fun indexSpace(
        spaceKey: String,
        baseUrl: String = "",
        onProgress: (String) -> Unit
    ): KBIndexState {
        onProgress("Загрузка страниц из Space $spaceKey...")
        val fetcher = ConfluenceBulkFetcher()
        val result = fetcher.fetchSpace(spaceKey, baseUrl) { loaded, total ->
            onProgress("Загрузка страниц: $loaded/$total")
        }
        return when (result) {
            is ConfluenceBulkFetcher.BulkResult.Success -> {
                val source = KBSource(KBSourceType.SPACE, spaceKey = spaceKey)
                indexPages(result.pages, source, onProgress)
            }
            is ConfluenceBulkFetcher.BulkResult.NoCredentials ->
                error("Нет настроенного Confluence MCP-сервера. Настройте подключение в MCP.")
            is ConfluenceBulkFetcher.BulkResult.Error ->
                error(result.message)
        }
    }

    /**
     * Индексирует дерево страниц от корневой.
     */
    fun indexPageTree(
        rootPageUrl: String,
        onProgress: (String) -> Unit
    ): KBIndexState {
        onProgress("Загрузка дерева страниц...")
        val fetcher = ConfluenceBulkFetcher()
        val result = fetcher.fetchPageTree(rootPageUrl) { loaded, total ->
            onProgress("Загрузка страниц: $loaded/$total")
        }
        return when (result) {
            is ConfluenceBulkFetcher.BulkResult.Success -> {
                val source = KBSource(KBSourceType.PAGE_TREE, rootPageUrl = rootPageUrl)
                indexPages(result.pages, source, onProgress)
            }
            is ConfluenceBulkFetcher.BulkResult.NoCredentials ->
                error("Нет настроенного Confluence MCP-сервера. Настройте подключение в MCP.")
            is ConfluenceBulkFetcher.BulkResult.Error ->
                error(result.message)
        }
    }

    /**
     * Индексирует список URL.
     */
    fun indexManualUrls(
        urls: List<String>,
        onProgress: (String) -> Unit
    ): KBIndexState {
        onProgress("Загрузка ${urls.size} страниц...")
        val fetcher = ConfluenceBulkFetcher()
        val result = fetcher.fetchPages(urls) { loaded, total ->
            onProgress("Загрузка страниц: $loaded/$total")
        }
        return when (result) {
            is ConfluenceBulkFetcher.BulkResult.Success -> {
                val source = KBSource(KBSourceType.MANUAL, manualUrls = urls)
                indexPages(result.pages, source, onProgress)
            }
            is ConfluenceBulkFetcher.BulkResult.NoCredentials ->
                error("Нет настроенного Confluence MCP-сервера. Настройте подключение в MCP.")
            is ConfluenceBulkFetcher.BulkResult.Error ->
                error(result.message)
        }
    }

    /**
     * Инкрементальное обновление — переиндексирует только изменённые страницы.
     */
    fun reindex(onProgress: (String) -> Unit): KBIndexState {
        val (_, meta) = store.load()
        if (meta == null) error("Индекс не найден. Выполните полную индексацию.")

        val source = meta.source
        // Загружаем свежие страницы
        val fetcher = ConfluenceBulkFetcher()
        val result = when (source.type) {
            KBSourceType.SPACE -> fetcher.fetchSpace(source.spaceKey) { l, t -> onProgress("Загрузка: $l/$t") }
            KBSourceType.PAGE_TREE -> fetcher.fetchPageTree(source.rootPageUrl) { l, t -> onProgress("Загрузка: $l/$t") }
            KBSourceType.MANUAL -> fetcher.fetchPages(source.manualUrls) { l, t -> onProgress("Загрузка: $l/$t") }
        }

        val freshPages = when (result) {
            is ConfluenceBulkFetcher.BulkResult.Success -> result.pages
            is ConfluenceBulkFetcher.BulkResult.NoCredentials -> error("Нет Confluence MCP-сервера")
            is ConfluenceBulkFetcher.BulkResult.Error -> error(result.message)
        }

        onProgress("── Загружено страниц: ${freshPages.size}, в индексе: ${meta.pageVersions.size}")

        val oldVersions = meta.pageVersions
        val changedPages = freshPages.filter { page ->
            oldVersions[page.id] != page.version
        }
        val newPages = freshPages.filter { page -> page.id !in oldVersions }
        val deletedCount = oldVersions.keys.count { it !in freshPages.map { p -> p.id }.toSet() }

        onProgress("  Изменено: ${changedPages.size}, новых: ${newPages.size}, удалено: $deletedCount")

        if (changedPages.isEmpty() && newPages.isEmpty()) {
            onProgress("Изменений не найдено")
            return meta
        }

        onProgress("── Обновляю ${changedPages.size} изменённых страниц...")

        // Удаляем старые чанки изменённых страниц
        for (page in changedPages) {
            store.removeByPageId(page.id)
        }

        // Чанкинг + эмбеддинг новых
        val newEntries = chunkAndEmbed(changedPages, onProgress)
        store.appendEntries(newEntries)

        // Обновляем метаданные
        val newVersions = oldVersions.toMutableMap()
        for (page in freshPages) {
            newVersions[page.id] = page.version
        }
        // Удаляем страницы, которых больше нет
        val freshIds = freshPages.map { it.id }.toSet()
        newVersions.keys.retainAll(freshIds)

        val (allEntries, _) = store.load()
        val updatedMeta = KBIndexState(
            source = source,
            pageCount = freshPages.size,
            chunkCount = allEntries.size,
            lastIndexedAt = System.currentTimeMillis(),
            pageVersions = newVersions
        )
        store.updateMeta(updatedMeta)

        // Перезагружаем индекс
        index.load(allEntries)
        bm25Index.load(allEntries)
        loaded = true

        val repo = KnowledgeBaseRepository.getInstance(project)
        repo.source = source

        onProgress("Обновление завершено: ${changedPages.size} страниц обновлено")
        return updatedMeta
    }

    fun clearIndex() {
        store.clear()
        index.clear()
        bm25Index.clear()
        loaded = false
        KnowledgeBaseRepository.getInstance(project).source = null
    }

    // ---- Search ----

    /**
     * Семантический поиск по базе знаний.
     */
    fun search(query: String, topK: Int = 5): List<KBSearchResult> {
        ensureLoaded()
        if (index.size == 0 && bm25Index.size == 0) return emptyList()

        val settings = PluginState.getInstance()
        val effectiveEmbeddingUrl = settings.embeddingUrl.ifBlank { settings.aiUrl }

        if (effectiveEmbeddingUrl.isBlank()) {
            return bm25Index.search(query, topK)
        }

        val client = EmbeddingClient(
            baseUrl = effectiveEmbeddingUrl,
            token = settings.embeddingToken.ifBlank { settings.aiToken },
            model = settings.embeddingModel,
            certPath = settings.aiCertPath.takeIf { it.isNotBlank() }
        )

        val queryEmbedding = client.use { it.embedSingle(query) }
        return index.search(queryEmbedding, topK)
    }

    fun isIndexed(): Boolean {
        return store.exists()
    }

    fun getState(): KBIndexState? {
        val (_, meta) = store.load()
        return meta
    }

    // ---- Internal ----

    private fun indexPages(
        pages: List<ConfluenceBulkFetcher.PageWithContent>,
        source: KBSource,
        onProgress: (String) -> Unit
    ): KBIndexState {
        onProgress("── Загружено страниц: ${pages.size}")
        pages.forEach { p -> onProgress("  [${p.id}] «${p.title}» v${p.version} (${p.plainText.length} симв.)") }

        val entries = chunkAndEmbed(pages, onProgress)

        val bm25Mode = entries.all { it.embedding.isEmpty() }
        onProgress("── Режим поиска: ${if (bm25Mode) "BM25 (полнотекстовый)" else "семантический (векторный)"}")

        val pageVersions = pages.associate { it.id to it.version }
        val meta = KBIndexState(
            source = source,
            pageCount = pages.size,
            chunkCount = entries.size,
            lastIndexedAt = System.currentTimeMillis(),
            pageVersions = pageVersions
        )

        onProgress("── Сохраняю индекс на диск (${entries.size} чанков)...")
        store.save(entries, meta)

        // Загружаем в memory index
        index.load(entries)
        bm25Index.load(entries)
        loaded = true

        // Сохраняем конфигурацию источника
        val repo = KnowledgeBaseRepository.getInstance(project)
        repo.source = source

        onProgress("Индексация завершена: ${pages.size} страниц, ${entries.size} чанков")
        return meta
    }

    private fun chunkAndEmbed(
        pages: List<ConfluenceBulkFetcher.PageWithContent>,
        onProgress: (String) -> Unit
    ): List<VectorEntry> {
        val settings = PluginState.getInstance()

        // 1. Чанкинг
        onProgress("── Чанкинг: размер=${settings.chunkSize} токенов, перекрытие=${settings.chunkOverlap}")
        val allChunks = mutableListOf<Triple<ConfluenceBulkFetcher.PageWithContent, TextChunker.ChunkResult, Int>>()
        var globalIdx = 0
        for (page in pages) {
            val chunks = TextChunker.chunk(
                page.plainText,
                maxTokens = settings.chunkSize,
                overlapTokens = settings.chunkOverlap
            )
            onProgress("  «${page.title}»: ${page.plainText.length} символов → ${chunks.size} чанков")
            for (chunk in chunks) {
                allChunks.add(Triple(page, chunk, globalIdx++))
            }
        }

        if (allChunks.isEmpty()) error("Не удалось создать чанки — страницы пусты")

        onProgress("Итого чанков: ${allChunks.size} из ${pages.size} страниц")

        // 2. Эмбеддинги — или BM25 fallback
        val effectiveEmbeddingUrl = settings.embeddingUrl.ifBlank { settings.aiUrl }

        if (effectiveEmbeddingUrl.isBlank()) {
            onProgress("── Режим: BM25 (Embedding URL не задан)")
            return toBm25Entries(allChunks)
        }

        val embeddingSource = if (settings.embeddingUrl.isNotBlank()) "embeddingUrl" else "aiUrl (fallback)"
        onProgress("── Эмбеддинги: url=$effectiveEmbeddingUrl [$embeddingSource], model=${settings.embeddingModel}")

        val client = EmbeddingClient(
            baseUrl = effectiveEmbeddingUrl,
            token = settings.embeddingToken.ifBlank { settings.aiToken },
            model = settings.embeddingModel,
            certPath = settings.aiCertPath.takeIf { it.isNotBlank() }
        )

        val texts = allChunks.map { (_, chunk, _) -> chunk.text }
        onProgress("Отправляю ${texts.size} чанков в Embedding API (батчи по 10)...")

        val embeddings = try {
            client.use { c ->
                c.embed(texts) { processed, total ->
                    onProgress("  Эмбеддинги: $processed/$total")
                }
            }
        } catch (e: Exception) {
            onProgress("  Ошибка Embedding API: ${e.message}")
            onProgress("── Переключаюсь на BM25...")
            return toBm25Entries(allChunks)
        }

        onProgress("Эмбеддинги получены: ${embeddings.size} векторов, размерность=${embeddings.firstOrNull()?.size ?: 0}")

        // 3. Собираем VectorEntry
        return allChunks.zip(embeddings).map { (triple, embedding) ->
            val (page, chunk, _) = triple
            VectorEntry(
                pageId = page.id,
                pageTitle = page.title,
                heading = chunk.headingPath,
                chunkIndex = chunk.chunkIndex,
                text = chunk.text,
                pageUrl = page.webUrl,
                embedding = embedding
            )
        }
    }

    private fun toBm25Entries(
        allChunks: List<Triple<ConfluenceBulkFetcher.PageWithContent, TextChunker.ChunkResult, Int>>
    ) = allChunks.map { (page, chunk, _) ->
        VectorEntry(
            pageId = page.id,
            pageTitle = page.title,
            heading = chunk.headingPath,
            chunkIndex = chunk.chunkIndex,
            text = chunk.text,
            pageUrl = page.webUrl
        )
    }

    private fun ensureLoaded() {
        if (loaded) return
        val (entries, _) = store.load()
        index.load(entries)
        bm25Index.load(entries)
        loaded = true
    }

    companion object {
        fun getInstance(project: Project): KnowledgeBaseService =
            project.getService(KnowledgeBaseService::class.java)
    }
}
