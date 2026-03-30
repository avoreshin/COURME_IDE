package changelogai.feature.spec.model

data class ReasoningStep(
    val type: StepType,
    val text: String,
    val elapsedMs: Long = 0
) {
    enum class StepType { THINKING, QUESTION, OUTPUT }
}
