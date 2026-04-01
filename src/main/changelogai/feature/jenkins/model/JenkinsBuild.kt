package changelogai.feature.jenkins.model

data class JenkinsBuild(
    val number: Int,
    val status: BuildStatus,
    val timestamp: Long = 0L,
    val durationMs: Long = 0L,
    val log: String = ""
)

enum class BuildStatus {
    SUCCESS, FAILURE, UNSTABLE, IN_PROGRESS, ABORTED, UNKNOWN;

    companion object {
        fun fromString(value: String): BuildStatus = when (value.uppercase()) {
            "SUCCESS" -> SUCCESS
            "FAILURE", "FAILED" -> FAILURE
            "IN_PROGRESS", "INPROGRESS", "BUILDING" -> IN_PROGRESS
            "UNSTABLE" -> UNSTABLE
            "ABORTED" -> ABORTED
            else -> UNKNOWN
        }
    }
}
