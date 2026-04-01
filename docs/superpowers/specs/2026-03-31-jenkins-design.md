# Jenkins Feature — Design Spec

**Date:** 2026-03-31  
**Status:** Approved

---

## Overview

Новая фича `feature/jenkins/` для плагина AI-OAssist: вкладка Jenkins Dashboard с просмотром пайплайнов, логов сборок, запуском сборок и inline AI-анализом упавших билдов. Подключение через MCP-сервер Jenkins.

---

## Architecture

Новый пакет `feature/jenkins/` по образцу `feature/sprint/`. Слоевые границы строго соблюдаются:

```
feature/jenkins/
├── JenkinsFeature.kt               # Feature entry point, регистрация в plugin.xml
├── model/
│   ├── JenkinsPipeline.kt          # data class: name, url, status, lastBuild
│   ├── JenkinsBuild.kt             # data class: number, status, timestamp, duration, log
│   └── JenkinsAnalysis.kt          # data class: summary, rootCause, relatedFiles, suggestions
├── engine/
│   ├── JenkinsMcpFetcher.kt        # MCP-клиент: getPipelines, getBuildLog, triggerBuild
│   ├── JenkinsAnalyzer.kt          # LLM-анализ лога + контекста проекта
│   └── JenkinsContextCollector.kt  # git log, git diff, Jenkinsfile для LLM-промпта
└── ui/
    ├── JenkinsPanel.kt             # Корневая панель (< 300 строк)
    ├── JenkinsPipelineList.kt      # Список пайплайнов с иконками статусов
    ├── JenkinsBuildLog.kt          # Отображение лога сборки
    └── JenkinsAnalysisPanel.kt     # Inline AI-результат + кнопка "Продолжить в GigaCodeAE"
```

**Layer boundaries:**
- `engine/` зависит только от `core/llm/` и `core/settings/`
- `JenkinsAnalyzer` не импортирует Swing/JBUI
- UI-обновления только через колбэки, переданные в конструктор
- Интеграция с GigaCodeAE через колбэк `onContinueInChat: (String) -> Unit` в `JenkinsFeature` — прямого импорта `feature/gigacodeae` из `feature/jenkins` нет

---

## MCP Integration

`JenkinsMcpFetcher` использует существующий `McpService` плагина. Отдельных настроек не требуется — пользователь настраивает Jenkins MCP-сервер через существующий `McpManagerPanel`.

**Ожидаемые MCP-инструменты Jenkins-сервера:**

| Инструмент | Описание |
|---|---|
| `jenkins_get_pipelines` | Список пайплайнов с текущим статусом |
| `jenkins_get_build_log(pipeline, buildNumber)` | Текст лога сборки |
| `jenkins_trigger_build(pipeline)` | Запуск сборки |

**Обработка ошибок:**
- MCP не настроен → кнопка "Настроить MCP" (аналог SprintPanel), Demo Mode включается автоматически
- Пайплайн недоступен → статус "Unknown" без краша
- Таймаут лога → стриминг с прогресс-индикатором

---

## Demo Mode

Переключатель `JBCheckBox "Demo"` в тулбаре Jenkins-панели. Когда включён:
- `JenkinsMcpFetcher` возвращает данные из `JenkinsDemoData.kt` вместо MCP-вызовов
- `JenkinsDemoData` содержит: фейковые пайплайны (2-3 шт.), лог с реалистичной ошибкой компиляции, готовый AI-анализ
- Demo Mode включается **по умолчанию** если MCP не настроен
- Позволяет показывать функционал без реального Jenkins

---

## Dashboard UI

Вкладка `JenkinsPanel` (<300 строк) оркестрирует:

1. **Тулбар** — кнопка "Обновить", переключатель "Demo", статус подключения
2. **`JenkinsPipelineList`** — список пайплайнов с иконками: зелёный (SUCCESS), красный (FAILED), жёлтый (IN_PROGRESS). Клик → загружает последние запуски
3. **`JenkinsBuildLog`** — лог выбранной сборки + кнопка "Анализировать" (появляется только для FAILED-сборок) + кнопка "Запустить сборку"
4. **`JenkinsAnalysisPanel`** — inline AI-результат + кнопка "Продолжить в GigaCodeAE"

---

## AI Analysis

`JenkinsAnalyzer` делает один LLM-вызов через существующий `LLMClient`.

**Входные данные:**
- Лог сборки (последние ~4000 символов — там обычно ошибка)
- Список изменённых файлов из `git diff HEAD~1` (от `JenkinsContextCollector`)
- Содержимое Jenkinsfile если есть в корне проекта

**Структура ответа (парсится из markdown):**
- `rootCause` — краткая причина падения (1-2 предложения)
- `relatedFiles` — файлы проекта, вероятно связанные с ошибкой
- `suggestions` — конкретные шаги для исправления

**UI flow:**
1. Пользователь нажимает "Анализировать" в `JenkinsBuildLog`
2. `JenkinsAnalysisPanel` показывает спиннер; запрос выполняется в `executeOnPooledThread` (не EDT)
3. Результат рендерится inline в `JenkinsAnalysisPanel`
4. Кнопка "Продолжить в GigaCodeAE" вызывает колбэк `onContinueInChat` с промптом: лог + анализ

---

## Registration

В `plugin.xml` добавить:
```xml
<extensions defaultExtensionNs="changelogai">
    <feature implementation="changelogai.feature.jenkins.JenkinsFeature"/>
</extensions>
```

В `FeatureToggleState` добавить ключ `"jenkins"`.

---

## Testing

- Unit-тесты `JenkinsAnalyzer` с mock `LLMClient`
- Unit-тесты `JenkinsMcpFetcher` с mock `McpService`
- Unit-тест `JenkinsContextCollector` на парсинг git-вывода
- Demo-режим покрывается через `JenkinsDemoData` без моков
