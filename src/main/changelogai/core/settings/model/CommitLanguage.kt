package changelogai.core.settings.model

enum class CommitLanguage(private val displayName: String) {
    Russian("Русский"),
    English("English");

    override fun toString() = displayName
}
