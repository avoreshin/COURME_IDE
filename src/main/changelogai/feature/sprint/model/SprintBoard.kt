package changelogai.feature.sprint.model

data class SprintInfo(
    val id: Int,
    val name: String,
    val startDate: String,
    val endDate: String,
    val goal: String,
    val boardId: String
)

data class JiraStory(
    val key: String,
    val summary: String,
    val description: String,
    val storyPoints: Int?,
    val status: String,
    val priority: String,
    val assignee: String?,
    val acceptanceCriteria: String,
    val subtasksCount: Int,
    val labels: List<String>,
    val type: String = "Task"
)

data class ReleaseVersion(
    val id: String,
    val name: String,
    val releaseDate: String?,
    val released: Boolean,
    val description: String = ""
)

data class ReleaseComposition(
    val version: ReleaseVersion,
    val issues: List<JiraStory>,
    val summary: String,
    val byType: Map<String, List<JiraStory>>
)

data class StoryAnalysis(
    val key: String,
    val riskLevel: RiskLevel,
    val issues: List<String>,
    val aiComment: String,
    val decompositionSuggestion: List<String>?
)

enum class RiskLevel { GREEN, YELLOW, RED }

data class SprintAnalysis(
    val sprint: SprintInfo,
    val stories: List<StoryAnalysis>,
    val velocityScore: Int,
    val readinessScore: Int,
    val criticalCount: Int,
    val summary: String
)
