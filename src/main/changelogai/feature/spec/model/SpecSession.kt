package changelogai.feature.spec.model

import java.util.UUID

data class SpecSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Новая спецификация",
    val createdAt: Long = System.currentTimeMillis(),
    var taskDescription: String = "",
    var spec: SpecDocument? = null,
    var prdDocument: String? = null,
    var mermaidDiagrams: String? = null,
    var state: SpecState = SpecState.IDLE
)
