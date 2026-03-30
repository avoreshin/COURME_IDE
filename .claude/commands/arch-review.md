Проведи архитектурное ревью проекта. Выполни следующие проверки:

1. **God classes** — найди файлы > 300 строк в `src/main/changelogai/feature/*/ui/` и `src/main/changelogai/feature/*/engine/`
2. **Нарушения слоёв** — проверь импорты между feature-пакетами (feature/kb не должен импортировать feature/spec и т.д.)
3. **Swing в оркестраторах** — проверь что `MainOrchestrator.kt` и `SpecOrchestrator.kt` не импортируют javax.swing или com.intellij.ui
4. **EDT нарушения** — поищи прямые LLM-вызовы без `executeOnPooledThread`

По каждой проблеме: укажи файл, строку, и конкретный шаг для исправления.
