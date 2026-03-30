# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew buildPlugin          # Build plugin distribution (.zip in build/distributions/)
./gradlew runIde               # Run IDE instance with plugin loaded for manual testing
./gradlew test                 # Run all tests
./gradlew test --rerun-tasks   # Force re-run tests (bypass cache)
./gradlew jacocoTestReport     # Generate HTML coverage report in build/reports/jacoco/
./build.sh                     # Convenience script with release/publish options
```

## Architecture

The plugin is an IntelliJ Platform plugin (plugin ID: `changelogai`, display name: `AI-OAssist`) that integrates AI capabilities into JetBrains IDEs.

### Layer Structure

**Platform layer** (`platform/`) — IntelliJ integration. `PluginToolWindowFactory` is the entry point, creates the right-sidebar tool window and delegates to `FeatureRegistry`, which resolves `changelogai.feature` extension points from `plugin.xml`.

**Feature layer** (`feature/`) — Six independently toggleable features, each registered as an extension point. Features can be enabled/disabled without IDE restart via `FeatureToggleState`.

**Core layer** (`core/`) — Shared LLM client abstraction, settings, and feature interfaces. `LLMClientFactory` (in `platform/`) creates the appropriate client based on settings.

### Features

| Feature | Package | Description |
|---|---|---|
| Changelog Generator | `feature/changelog/` | Git commit → release notes via LLM |
| GigaCodeAE | `feature/gigacodeae/` | Main AI chat assistant with multi-agent orchestration |
| Spec Generator | `feature/spec/` | Generates specifications from Confluence requirements |
| Knowledge Base | `feature/kb/` | RAG system indexing Confluence pages |
| Coverage Analysis | `feature/coverage/` | Test coverage display panel |
| MCP | Integrated into gigacodeae | Model Context Protocol tool integration |

### GigaCodeAE Orchestrator (most complex component)

The AI chat flows through: `ChatPanel` → `ChatAgent` → `MainOrchestrator` → specialized agents.

Agents (`feature/gigacodeae/orchestrator/agents/`):
- `PlannerAgent` — decomposes user task into steps
- `CodeAgent` — writes/modifies code
- `SearchAgent` — searches codebase
- `TestAgent` — writes/runs tests
- `ReviewAgent` — reviews changes
- `ToolAgent` — executes MCP and builtin tools
- `SummarizerAgent` — compresses context when token limit approaches

### LLM Client

`core/llm/client/` contains: `GigaChatClient` (internal API), `ExternalGigaChatClient` (external API). Both implement `LLMClient`. Requests support cancellation via `Cancelable` interface integrated with IDE progress indicators (`core/llm/cancellation/`). LLM call tracing is available via `core/llm/debug/`.

### Knowledge Base (RAG)

`feature/kb/` implements retrieval-augmented generation: Confluence pages are fetched, chunked, embedded, and stored in a hybrid BM25 + vector index (`feature/kb/store/BM25Index.kt`). `KnowledgeBaseService` is a project-level service managing the index lifecycle.

### Settings & State

- `PluginState` (application service) — all plugin settings (API keys, model selection, etc.)
- `PluginDefaults` — default values
- `FeatureToggleState` (application service) — per-feature enable/disable flags
- `McpState`, `SkillState` — feature-specific persistent state

## Source Layout

Non-standard source root: `src/main/changelogai/` (not `src/main/kotlin/`). Tests are at `src/test/kotlin/changelogai/`.

## Key Files

- `src/main/resources/META-INF/plugin.xml` — extension points, services, actions registration
- `gradle.properties` — plugin version, ID, and IDE version constraints
- `build.gradle.kts` — fat jar config, JaCoCo, Maven publishing to internal Nexus

## Architecture Rules

@.claude/rules/architecture.md

## Known Technical Debt (address when touching these files)

| File | Lines | Problem | Fix |
|------|-------|---------|-----|
| `feature/spec/ui/SpecPanel.kt` | 1259 | God class — UI + logic mixed | Extract `SpecToolbar`, `SpecEditor`, `SpecResultView` |
| `feature/gigacodeae/ui/ChatPanel.kt` | 694 | Too large | Extract `MessageListPanel`, `ChatToolbar` |
| `feature/spec/engine/SpecOrchestrator.kt` | 594 | May have UI deps | Verify no Swing imports |
| `feature/spec/ui/AssessmentPanel.kt` | 447 | Too large | Extract `AssessmentResultView` |
| `feature/kb/ui/KnowledgeBasePanel.kt` | 401 | Too large | Extract `KbIndexStatusPanel` |

## Dependencies

Runtime: Apache HttpClient (LLM API calls), Jackson (JSON). The plugin bundles all runtime dependencies into a fat jar — add new dependencies to `runtimeClasspath` configuration, not just `implementation`.
