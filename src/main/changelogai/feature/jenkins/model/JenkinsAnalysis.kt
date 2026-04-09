package changelogai.feature.jenkins.model

data class JenkinsAnalysis(
    val rootCause: String,
    val relatedFiles: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)