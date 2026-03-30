package changelogai.core.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "McpSettings", storages = [Storage("ChangelogAI.xml")])
class McpState : PersistentStateComponent<McpState> {

    var servers: MutableList<McpEntry> = mutableListOf()

    override fun getState(): McpState = this

    override fun loadState(state: McpState) {
        servers = state.servers
    }

    companion object {
        fun getInstance(): McpState =
            ApplicationManager.getApplication().getService(McpState::class.java)
    }
}
