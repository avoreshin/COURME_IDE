package changelogai.feature.jenkins.model

data class JenkinsPipeline(
    val name: String,
    val url: String,
    val status: BuildStatus = BuildStatus.UNKNOWN,
    val lastBuild: JenkinsBuild? = null
)
