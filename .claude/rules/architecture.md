---
description: Architectural constraints and refactoring rules for this IntelliJ plugin
---

# Architecture Rules

## Layer Boundaries (strictly enforced)

```
platform/ → can use core/ only
feature/*/ → can use core/ only, NOT other features
core/      → no dependencies on platform/ or feature/
```

Never import across feature packages (e.g., `feature/kb` must not import from `feature/spec`).

## UI Panels — size limit 300 lines

Current violations to fix incrementally:
- `SpecPanel.kt` (1259 lines) — extract: `SpecToolbar`, `SpecEditor`, `SpecResultView`
- `ChatPanel.kt` (694 lines) — extract: `MessageListPanel`, `ChatToolbar`
- `AssessmentPanel.kt` (447 lines) — extract: `AssessmentResultView`
- `KnowledgeBasePanel.kt` (401 lines) — extract: `KbIndexStatusPanel`

**Rule:** When touching a panel file > 300 lines, extract at least one sub-component.

## Orchestrators — no UI dependencies

`SpecOrchestrator` and `MainOrchestrator` must not import Swing/JBUI classes.
UI updates must go through callbacks/listeners passed in constructor.

## Services — IntelliJ lifecycle

- Application-level state → `@Service(Service.Level.APP)` + `PersistentStateComponent`
- Project-level state → `@Service(Service.Level.PROJECT)`
- Never use `static` fields or singletons — use IntelliJ DI

## Coroutines / Threading

- LLM calls: always off EDT (`ApplicationManager.getApplication().executeOnPooledThread`)
- UI updates: always on EDT (`SwingUtilities.invokeLater` or `ApplicationManager.invokeLater`)
- Cancellation: use `Cancelable` interface from `core/llm/cancellation/`

## Adding new features

1. Create package under `feature/<name>/`
2. Register extension point in `plugin.xml` as `changelogai.feature`
3. Implement `FeatureTab` interface from `core/feature/`
4. Toggle via `FeatureToggleState`

## Dependencies

New runtime libs → add to `runtimeClasspath` in `build.gradle.kts`, NOT `implementation`.
The plugin builds a fat jar — all runtime deps must be bundled.
