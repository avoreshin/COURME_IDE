package changelogai.core.settings.model

enum class CommitSize(val value: Int) {
    Short(256),
    Standard(512),
    StandardPlus(1024),
    Large(2048)
}
