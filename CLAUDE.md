# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build the plugin ZIP
./gradlew buildPlugin

# Run the plugin in a sandboxed IDE instance
./gradlew runIde

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "changelogai.feature.jenkins.engine.JenkinsAnalyzerTest"

# Verify plugin against target IDEs
./gradlew verifyPlugin

# JaCoCo coverage report (generated automatically after test)
./gradlew jacocoTestReport
```

External repos (Nexus/Sberosc) require credentials passed as system properties:
`-Dgradle.wrapperUser=... -Dgradle.wrapperPassword=...` or `-Dgradle.wrapperOscToken=...`

Release build: add `-Drelease=true` to drop the `-SNAPSHOT` suffix.

## Project Layout

- Source root: `src/main/changelogai/` (non-standard — set in `sourceSets`)
- Tests: `src/test/kotlin/changelogai/`
- Plugin descriptor: `bin/main/META-INF/plugin.xml`
- Plugin ID / group / version: `gradle.properties`

## Architecture

Three layers with strict dependency rules (see `.claude/rules/architecture.md`):

```
platform/  →  core/  ←  feature/*/
```

`platform/` wires everything together. `feature/*` packages must not import each other. `core/` has no upward dependencies.

### Core Layer (`core/`)

| Package | Purpose |
|---|---|
| `core/llm/client/` | `LLMClient` interface + three implementations: `GigaChatClient` (mTLS), `ExternalGigaChatClient` (OAuth), `ChemodanClient` |
| `core/llm/cancellation/` | `Cancelable` interface, `AtomicCancelable`, `ProgressIndicatorWrapper` |
| `core/llm/model/` | DTOs for chat messages, requests, responses, function calls |
| `core/feature/` | `Feature` interface + `FeatureToggleState` registry |
| `core/settings/` | `PluginState` — `@Service(Level.APP)` + `PersistentStateComponent` |
| `core/skill/` | `SkillDefinition` data class; 9 built-in skills |
| `core/confluence/` | Confluence REST client + content parser |
| `core/mcp/` | MCP server config & JSON sync |

### Feature Layer (`feature/`)

Each feature implements `Feature` (provides `id`, `createTab(): JPanel`, availability check) and is registered in `platform/FeatureRegistry`.

| Feature | Description |
|---|---|
| `changelog` | Generates CHANGELOG.md from git commits via LLM |
| `gigacodeae` | Main AI chat with multi-agent orchestration, tool calls, MCP, skills |
| `kb` | Knowledge base: Confluence indexing + semantic search (embeddings) |
| `spec` | Technical specification generator from Confluence requirements |
| `jenkins` | Jenkins build dashboard + AI failure analysis |
| `sprint` | Sprint planning / backlog analysis |
| `coverage` | Code coverage analytics panel |

### Platform Layer (`platform/`)

`ChangelogToolWindowFactory` creates the tool window. `MainShell` hosts all tabs in a `CardLayout` with a vertical `NavBar`. `LLMClientFactory` creates the appropriate `LLMClient` based on settings.

### Multi-Agent Orchestration (feature/gigacodeae/orchestrator/)

`MainOrchestrator.sendMessage()` pipeline:
1. **Compress history** — `ContextCompressor` keeps recent messages + rolling summary
2. **Classify intent** — `IntentClassifier` (rules-based, no LLM call)
3. **Route** → direct answer, single specialist agent, or planner + multi-step agents
4. **Tool dispatch** — `ToolDispatcher` executes `ReadFileTool`, `WriteFileTool`, `SearchInFilesTool`, `RunTerminalTool`, `SearchKnowledgeBaseTool`, MCP tools

All LLM calls run off EDT; results are delivered via callbacks (`onAssistantMessage`, `onDone`, `onError`). Cancellation uses `AtomicCancelable`.

## Adding a New Feature

1. Create `feature/<name>/` package
2. Implement `Feature` interface (`core/feature/Feature.kt`)
3. Register in `plugin.xml` as `changelogai.feature` extension point
4. Toggle visibility via `FeatureToggleState`

## Threading Rules

- **Off EDT**: all LLM/network calls (`ApplicationManager.getApplication().executeOnPooledThread`)
- **On EDT**: all Swing/UI updates (`SwingUtilities.invokeLater`)
- **Cancellation**: pass `AtomicCancelable`; call `checkCanceled()` in long loops

## UI Panel Size Limit

Panel files must stay under 300 lines. When editing a panel that exceeds 300 lines, extract at least one sub-component. Current known violations: `SpecPanel.kt`, `ChatPanel.kt`, `AssessmentPanel.kt`, `KnowledgeBasePanel.kt`.

## Dependencies

Add new runtime libs to `runtimeClasspath` in `build.gradle.kts`, not `implementation`. The fat-jar build bundles all runtime deps.
