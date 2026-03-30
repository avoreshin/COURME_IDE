package changelogai.feature.spec.model

data class ClarificationQuestion(
    val id: String,
    val text: String,
    val options: List<String> = emptyList(),
    var answer: String? = null
)
