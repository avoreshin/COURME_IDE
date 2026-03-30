package changelogai.core.settings.model

enum class LLMTemperature(val value: Double) {
    Low(0.3),
    Standard(0.87),
    Medium(1.44),
    High(1.9)
}
