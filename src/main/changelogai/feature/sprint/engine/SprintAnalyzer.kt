package changelogai.feature.sprint.engine

import changelogai.core.llm.model.ChatMessage
import changelogai.core.llm.model.ChatRequest
import changelogai.core.settings.PluginState
import changelogai.feature.sprint.model.*
import changelogai.platform.LLMClientFactory
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project

class SprintAnalyzer(private val project: Project) {

    private val fetcher = JiraMcpFetcher(project)
    private val mapper  = jacksonObjectMapper()

    // ── Публичный API ─────────────────────────────────────────────────────────

    fun isConfigured(): Boolean = fetcher.isConfigured()

    /**
     * Шаг 1: загружает активный спринт и его истории через Jira MCP.
     * Использует JQL sprint in openSprints() — не требует специального инструмента.
     * Запускать с фонового потока.
     */
    fun loadSprint(boardInput: String): Pair<SprintInfo, List<JiraStory>> {
        val projectKey = extractProjectKey(boardInput)

        val jql = if (projectKey != null)
            "sprint in openSprints() AND project = \"$projectKey\" ORDER BY priority DESC"
        else
            "sprint in openSprints() ORDER BY priority DESC"

        val issuesJson = fetcher.resolveAndCall(
            JiraTool.SEARCH_ISSUES_JQL,
            mapOf("jql" to jql, "maxResults" to 100)
        )
        val stories = parseStories(issuesJson)
        val sprint = parseSprintFromIssues(issuesJson)
            ?: SprintInfo(0, "Активный спринт", "", "", "", projectKey ?: boardInput)
        return sprint to stories
    }

    /**
     * Загружает список версий (fixVersions) проекта.
     */
    fun getVersions(projectKey: String): List<ReleaseVersion> {
        return try {
            val json = fetcher.resolveAndCall(
                JiraTool.GET_PROJECT_VERSIONS,
                mapOf("projectKey" to projectKey, "project_key" to projectKey)
            )
            parseVersions(json)
        } catch (_: Exception) {
            // Fallback: извлекаем уникальные fixVersion из задач
            val issuesJson = fetcher.resolveAndCall(
                JiraTool.SEARCH_ISSUES_JQL,
                mapOf(
                    "jql" to "project = \"$projectKey\" AND fixVersion is not EMPTY ORDER BY fixVersion DESC",
                    "maxResults" to 100
                )
            )
            extractVersionsFromIssues(issuesJson)
        }
    }

    /**
     * Загружает состав релиза (все задачи в fixVersion), группирует по типу и генерирует AI-сводку.
     */
    fun loadRelease(projectKey: String, versionName: String): ReleaseComposition {
        val jql = "project = \"$projectKey\" AND fixVersion = \"$versionName\" ORDER BY issuetype ASC"
        val issuesJson = fetcher.resolveAndCall(
            JiraTool.SEARCH_ISSUES_JQL,
            mapOf("jql" to jql, "maxResults" to 200)
        )
        val issues = parseStories(issuesJson)
        val byType = issues.groupBy { it.type.ifBlank { "Task" } }
        val summary = generateReleaseSummary(versionName, issues, byType)
        val version = ReleaseVersion(id = "", name = versionName, releaseDate = null, released = false)
        return ReleaseComposition(version, issues, summary, byType)
    }

    /**
     * Экспортирует состав релиза в Markdown.
     */
    fun exportReleaseMarkdown(release: ReleaseComposition): String {
        val sb = StringBuilder()
        sb.appendLine("# Release: ${release.version.name}")
        sb.appendLine()
        sb.appendLine("**Всего задач:** ${release.issues.size}")
        sb.appendLine()
        if (release.summary.isNotBlank()) {
            sb.appendLine("## AI-сводка")
            sb.appendLine(release.summary)
            sb.appendLine()
        }
        release.byType.entries.sortedBy { it.key }.forEach { (type, issues) ->
            sb.appendLine("## $type (${issues.size})")
            issues.forEach { issue ->
                val sp = if (issue.storyPoints != null) " [${issue.storyPoints} SP]" else ""
                sb.appendLine("- **${issue.key}**$sp — ${issue.summary}")
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    /**
     * Шаг 2: AI-анализ историй батчами.
     * Запускать с фонового потока.
     */
    fun analyze(sprint: SprintInfo, stories: List<JiraStory>): SprintAnalysis {
        if (stories.isEmpty()) {
            return SprintAnalysis(sprint, emptyList(), 0, 100, 0, "Нет историй в спринте")
        }
        val storyAnalyses = stories.chunked(15).flatMap { batch ->
            analyzeBatch(sprint, batch)
        }
        val readinessScore = if (storyAnalyses.isEmpty()) 100
            else (storyAnalyses.count { it.riskLevel == RiskLevel.GREEN } * 100 / storyAnalyses.size)
        val criticalCount = storyAnalyses.count { it.riskLevel == RiskLevel.RED }
        val velocityScore = calcVelocityScore(stories, storyAnalyses)

        return SprintAnalysis(
            sprint = sprint,
            stories = storyAnalyses,
            velocityScore = velocityScore,
            readinessScore = readinessScore,
            criticalCount = criticalCount,
            summary = buildSummary(sprint, storyAnalyses)
        )
    }

    /**
     * Шаг 3: декомпозиция одной истории на подзадачи.
     * Запускать с фонового потока.
     */
    fun decompose(story: JiraStory): List<String> {
        val state = PluginState.getInstance()
        val prompt = """
            Декомпозируй следующую Jira-историю на 3-6 конкретных подзадач для разработчика.
            Верни JSON-массив строк — краткие названия подзадач на русском языке.
            Только массив, без лишнего текста.

            История: ${story.key} — ${story.summary}
            Описание: ${story.description.take(500)}
            Story Points: ${story.storyPoints ?: "не указаны"}
            AC: ${story.acceptanceCriteria.take(300)}
        """.trimIndent()

        val request = ChatRequest(
            model = state.aiModel,
            temperature = 0.3,
            maxTokens = 1000,
            messages = listOf(ChatMessage("user", prompt))
        )
        return LLMClientFactory.create(state, null).use { client ->
            val raw = client.postChatCompletions(request).choices.firstOrNull()?.message?.content ?: "[]"
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            try { mapper.readValue<List<String>>(clean) }
            catch (_: Exception) { listOf("Декомпозиция ${story.key}") }
        }
    }

    /**
     * Шаг 4: создать подзадачи в Jira через MCP.
     * Запускать с фонового потока.
     */
    fun createSubtasks(parentKey: String, subtasks: List<String>) {
        subtasks.forEach { summary ->
            try {
                fetcher.resolveAndCall(
                    JiraTool.CREATE_ISSUE,
                    mapOf(
                        "parent" to parentKey,
                        "summary" to summary,
                        "issuetype" to "Subtask",
                        "issue_type" to "Subtask"
                    )
                )
            } catch (_: Exception) {
                // Попробуем без parent-поля (некоторые серверы используют другой формат)
                fetcher.resolveAndCall(
                    JiraTool.CREATE_ISSUE,
                    mapOf(
                        "parentKey" to parentKey,
                        "summary" to summary,
                        "issueType" to "Subtask"
                    )
                )
            }
        }
    }

    // ── Парсинг ───────────────────────────────────────────────────────────────

    /**
     * Извлекает ключ Jira-проекта из URL борда или простого текста.
     * Примеры: "SCRUM", "https://company.atlassian.net/jira/software/projects/SCRUM/boards/42"
     */
    private fun extractProjectKey(input: String): String? {
        // URL со структурой /projects/KEY/
        Regex("""/projects/([A-Z][A-Z0-9]+)/""").find(input)?.let { return it.groupValues[1] }
        // URL с /browse/KEY-123 или /browse/KEY
        Regex("""/browse/([A-Z][A-Z0-9]+)""").find(input)?.let { return it.groupValues[1] }
        // Просто ключ проекта или ключ задачи (SCRUM или SCRUM-123 -> SCRUM)
        val trimmed = input.trim()
        if (Regex("""/boards/\d+""").containsMatchIn(trimmed)) return null  // только номер борда — ключ неизвестен
        Regex("""^([A-Z][A-Z0-9]+)(-\d+)?$""").find(trimmed)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * Извлекает SprintInfo из поля sprint первого issue в ответе на SEARCH_ISSUES_JQL.
     * GigaChat Jira MCP возвращает sprint-поле с name/startDate/endDate/goal/id.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSprintFromIssues(json: String): SprintInfo? {
        return try {
            val data: Any = mapper.readValue(json, Any::class.java)
            val issues: List<Map<String, Any>> = when {
                data is Map<*, *> && data["issues"] != null -> data["issues"] as List<Map<String, Any>>
                data is List<*> -> data as List<Map<String, Any>>
                else -> return null
            }
            val fields = (issues.firstOrNull() ?: return null)["fields"] as? Map<String, Any> ?: return null
            val raw = fields["sprint"] ?: fields["customfield_10020"] ?: return null
            val sprintMap: Map<String, Any> = when {
                raw is Map<*, *> -> raw as Map<String, Any>
                raw is List<*> -> {
                    val list = raw as List<Map<String, Any>>
                    list.firstOrNull { it["state"]?.toString() == "active" } ?: list.lastOrNull() ?: return null
                }
                else -> return null
            }
            SprintInfo(
                id = sprintMap["id"]?.toString()?.toIntOrNull() ?: 0,
                name = sprintMap["name"]?.toString() ?: return null,
                startDate = sprintMap["startDate"]?.toString() ?: sprintMap["start_date"]?.toString() ?: "",
                endDate = sprintMap["endDate"]?.toString() ?: sprintMap["end_date"]?.toString() ?: "",
                goal = sprintMap["goal"]?.toString() ?: "",
                boardId = sprintMap["boardId"]?.toString() ?: ""
            )
        } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVersions(json: String): List<ReleaseVersion> {
        return try {
            val data: Any = mapper.readValue(json, Any::class.java)
            val versions: List<Map<String, Any>> = when {
                data is List<*> -> data as List<Map<String, Any>>
                data is Map<*, *> && data["versions"] != null -> data["versions"] as List<Map<String, Any>>
                else -> return emptyList()
            }
            versions.mapNotNull { v ->
                val name = v["name"]?.toString() ?: return@mapNotNull null
                ReleaseVersion(
                    id = v["id"]?.toString() ?: "",
                    name = name,
                    releaseDate = v["releaseDate"]?.toString() ?: v["release_date"]?.toString(),
                    released = v["released"]?.toString()?.toBoolean() ?: false,
                    description = v["description"]?.toString() ?: ""
                )
            }.sortedByDescending { it.releaseDate ?: "9999" }
        } catch (_: Exception) { emptyList() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractVersionsFromIssues(json: String): List<ReleaseVersion> {
        return try {
            val data: Any = mapper.readValue(json, Any::class.java)
            val issues: List<Map<String, Any>> = when {
                data is Map<*, *> && data["issues"] != null -> data["issues"] as List<Map<String, Any>>
                data is List<*> -> data as List<Map<String, Any>>
                else -> return emptyList()
            }
            val seen = mutableSetOf<String>()
            issues.flatMap { issue ->
                val fields = issue["fields"] as? Map<String, Any> ?: return@flatMap emptyList()
                val fixVersions = fields["fixVersions"] as? List<Map<String, Any>> ?: return@flatMap emptyList()
                fixVersions.mapNotNull { v ->
                    val name = v["name"]?.toString() ?: return@mapNotNull null
                    if (seen.add(name)) ReleaseVersion(
                        id = v["id"]?.toString() ?: "",
                        name = name,
                        releaseDate = v["releaseDate"]?.toString(),
                        released = v["released"]?.toString()?.toBoolean() ?: false
                    ) else null
                }
            }.sortedByDescending { it.releaseDate ?: "9999" }
        } catch (_: Exception) { emptyList() }
    }

    private fun generateReleaseSummary(
        versionName: String,
        issues: List<JiraStory>,
        byType: Map<String, List<JiraStory>>
    ): String {
        if (issues.isEmpty()) return "В релизе нет задач."
        val state = PluginState.getInstance()
        val typeSummary = byType.entries
            .sortedByDescending { it.value.size }
            .joinToString(", ") { (type, list) -> "${list.size} ${type.lowercase()}" }
        val issuesList = issues.take(20).joinToString("\n") { "- ${it.key}: ${it.summary}" }
        val prompt = """
            Составь краткую сводку для релиза "$versionName" на русском языке (2-3 предложения).
            Всего задач: ${issues.size} ($typeSummary).
            Список задач:
            $issuesList
            Только текст сводки, без заголовков и списков.
        """.trimIndent()
        val request = ChatRequest(
            model = state.aiModel,
            temperature = 0.3,
            maxTokens = 300,
            messages = listOf(ChatMessage("user", prompt))
        )
        return try {
            LLMClientFactory.create(state, null).use { client ->
                client.postChatCompletions(request).choices.firstOrNull()?.message?.content?.trim()
                    ?: "В релизе $versionName: ${issues.size} задач ($typeSummary)."
            }
        } catch (_: Exception) {
            "В релизе $versionName: ${issues.size} задач ($typeSummary)."
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStories(json: String): List<JiraStory> {
        return try {
            val data: Any = mapper.readValue(json, Any::class.java)
            val issues: List<Map<String, Any>> = when {
                data is Map<*, *> && data["issues"] != null -> data["issues"] as List<Map<String, Any>>
                data is List<*> -> data as List<Map<String, Any>>
                else -> emptyList()
            }
            issues.mapNotNull { issue ->
                try {
                    val key = issue["key"]?.toString() ?: return@mapNotNull null
                    val fields = issue["fields"] as? Map<String, Any> ?: emptyMap()
                    val desc = extractText(fields["description"]) ?: ""
                    val ac = extractAC(fields, desc)
                    JiraStory(
                        key = key,
                        summary = fields["summary"]?.toString() ?: key,
                        description = desc.take(500),
                        storyPoints = fields["story_points"]?.toString()?.toIntOrNull()
                            ?: fields["customfield_10016"]?.toString()?.toDoubleOrNull()?.toInt()
                            ?: fields["storyPoints"]?.toString()?.toIntOrNull(),
                        status = (fields["status"] as? Map<*, *>)?.get("name")?.toString() ?: "To Do",
                        priority = (fields["priority"] as? Map<*, *>)?.get("name")?.toString() ?: "Medium",
                        assignee = (fields["assignee"] as? Map<*, *>)?.get("displayName")?.toString()
                            ?: (fields["assignee"] as? Map<*, *>)?.get("name")?.toString(),
                        acceptanceCriteria = ac.take(300),
                        subtasksCount = (fields["subtasks"] as? List<*>)?.size ?: 0,
                        labels = (fields["labels"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                        type = (fields["issuetype"] as? Map<*, *>)?.get("name")?.toString() ?: "Task"
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractText(obj: Any?): String? = when {
        obj == null -> null
        obj is String -> obj
        obj is Map<*, *> -> {
            // Atlassian Document Format (ADF)
            val content = obj["content"] as? List<*>
            content?.joinToString("\n") { block ->
                if (block is Map<*, *>) {
                    (block["content"] as? List<*>)?.joinToString("") { inline ->
                        (inline as? Map<*, *>)?.get("text")?.toString() ?: ""
                    } ?: ""
                } else ""
            }?.trim()
        }
        else -> obj.toString()
    }

    private fun extractAC(fields: Map<String, Any>, description: String): String {
        // Ищем AC в кастомных полях или в описании
        val customAc = fields["customfield_10034"]?.let { extractText(it) }
            ?: fields["acceptance_criteria"]?.let { extractText(it) }
        if (!customAc.isNullOrBlank()) return customAc

        // Из описания — ищем секцию с AC
        val acRegex = Regex("""(?i)(acceptance criteria|критерии приёмки|ac:|criteria)[:\s]*(.+?)(?=\n\n|\z)""", RegexOption.DOT_MATCHES_ALL)
        return acRegex.find(description)?.groupValues?.get(2)?.trim() ?: ""
    }

    // ── LLM-анализ ─────────────────────────────────────────────────────────────

    private fun analyzeBatch(sprint: SprintInfo, stories: List<JiraStory>): List<StoryAnalysis> {
        val state = PluginState.getInstance()
        val storiesText = stories.joinToString("\n\n") { s ->
            """---
STORY: ${s.key} | SP: ${s.storyPoints ?: "?"} | ${s.status} | ${s.priority}
SUMMARY: ${s.summary}
AC: ${s.acceptanceCriteria.ifBlank { "НЕТ" }}
DESC: ${s.description.take(200)}""".trimIndent()
        }

        val prompt = """
Ты аналитик Jira. Спринт "${sprint.name}", цель: "${sprint.goal.ifBlank { "не указана" }}".

Проанализируй каждую историю и верни JSON-массив объектов.
Для каждой истории:
- key: ключ задачи
- riskLevel: "GREEN" (готова к разработке) | "YELLOW" (есть риски) | "RED" (есть блокеры)
- issues: массив строк с конкретными проблемами (пустой если GREEN)
- aiComment: 1-2 предложения
- decompositionSuggestion: массив подзадач если SP > 5, иначе null

Критерии RED: нет AC, размытые требования, SP > 8, технический долг без контекста.
Критерии YELLOW: SP 5-8, частичные AC, неясные NFR.
Критерии GREEN: конкретные AC, SP ≤ 5, понятный scope.

Истории:
$storiesText

Верни только JSON-массив, без пояснений.
        """.trimIndent()

        val request = ChatRequest(
            model = state.aiModel,
            temperature = 0.2,
            maxTokens = 4000,
            messages = listOf(ChatMessage("user", prompt))
        )

        return LLMClientFactory.create(state, null).use { client ->
            val raw = client.postChatCompletions(request).choices.firstOrNull()?.message?.content ?: "[]"
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            try {
                mapper.readValue<List<RawStoryAnalysis>>(clean).map { it.toStoryAnalysis() }
            } catch (_: Exception) {
                stories.map { defaultAnalysis(it) }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RawStoryAnalysis(
        val key: String = "",
        val riskLevel: String = "YELLOW",
        val issues: List<String> = emptyList(),
        val aiComment: String = "",
        val decompositionSuggestion: List<String>? = null
    ) {
        fun toStoryAnalysis() = StoryAnalysis(
            key = key,
            riskLevel = runCatching { RiskLevel.valueOf(riskLevel) }.getOrDefault(RiskLevel.YELLOW),
            issues = issues,
            aiComment = aiComment,
            decompositionSuggestion = decompositionSuggestion
        )
    }

    private fun defaultAnalysis(story: JiraStory) = StoryAnalysis(
        key = story.key,
        riskLevel = if (story.acceptanceCriteria.isBlank()) RiskLevel.RED else RiskLevel.YELLOW,
        issues = if (story.acceptanceCriteria.isBlank()) listOf("Нет Acceptance Criteria") else emptyList(),
        aiComment = "Требуется ручная проверка",
        decompositionSuggestion = null
    )

    private fun calcVelocityScore(stories: List<JiraStory>, analyses: List<StoryAnalysis>): Int {
        val totalSP = stories.sumOf { it.storyPoints ?: 3 }
        val readyCount = analyses.count { it.riskLevel == RiskLevel.GREEN }
        val readyRatio = if (analyses.isEmpty()) 1.0 else readyCount.toDouble() / analyses.size
        val spScore = when {
            totalSP <= 20 -> 90
            totalSP <= 40 -> 75
            totalSP <= 60 -> 60
            else -> 45
        }
        return (spScore * readyRatio).toInt().coerceIn(0, 100)
    }

    private fun buildSummary(sprint: SprintInfo, analyses: List<StoryAnalysis>): String {
        val red = analyses.count { it.riskLevel == RiskLevel.RED }
        val yellow = analyses.count { it.riskLevel == RiskLevel.YELLOW }
        val green = analyses.count { it.riskLevel == RiskLevel.GREEN }
        return "Спринт ${sprint.name}: $green готовы, $yellow с рисками, $red заблокированы."
    }
}
