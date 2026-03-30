package changelogai.feature.spec.model

data class SpecDocument(
    val title: String,
    val functional: List<Requirement> = emptyList(),
    val nonFunctional: List<Requirement> = emptyList(),
    val acceptanceCriteria: List<Requirement> = emptyList(),
    val edgeCases: List<Requirement> = emptyList()
) {
    data class Requirement(
        val id: String,
        val description: String,
        val priority: Priority = Priority.MEDIUM
    )

    enum class Priority { CRITICAL, HIGH, MEDIUM, LOW }

    fun isEmpty() = functional.isEmpty() && nonFunctional.isEmpty() &&
            acceptanceCriteria.isEmpty() && edgeCases.isEmpty()
}
