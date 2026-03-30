# /confluence-spec — Загрузка требований из Confluence в Spec Generator

## Назначение
Загружает страницу Confluence по URL и использует её содержимое как источник требований для генерации или валидации спецификации в Spec Generator.

## Использование

```
/confluence-spec <URL страницы Confluence> [режим: generate|validate]
```

### Примеры

```
/confluence-spec https://company.atlassian.net/wiki/spaces/PROJ/pages/12345/Feature-Name
/confluence-spec https://confluence.company.ru/pages/viewpage.action?pageId=67890 validate
/confluence-spec https://company.atlassian.net/wiki/x/AbCd generate
```

## Поддерживаемые форматы URL

| Тип | Формат |
|-----|--------|
| Atlassian Cloud | `https://*.atlassian.net/wiki/spaces/SPACE/pages/{id}/...` |
| On-premise (viewpage) | `https://host/pages/viewpage.action?pageId={id}` |
| Short link | `https://*.atlassian.net/wiki/x/{key}` |

## Режимы работы

### `generate` (по умолчанию)
Загружает страницу Confluence и использует её как **дополнительный контекст** для генерации новой спецификации:
- Содержимое страницы добавляется в промпт как блок «Требования из Confluence»
- Ключевые термины из страницы используются для кросс-проверки покрытия
- Если страница похожа на готовое ТЗ — предлагается переключиться в режим `validate`

### `validate`
Загружает страницу Confluence и запускает **оценку существующего ТЗ**:
1. **Извлечение** — ConfluenceSpecExtractorAgent извлекает FR/NFR/AC/EDGE из текста
2. **Валидация** — SpecValidator проверяет структуру и формат
3. **Оценка качества** — QualityAssessorAgent оценивает каждое требование
4. **Заполнение пробелов** — GapFillerAgent генерирует недостающие секции
5. **Оценка трудозатрат** — EffortEstimatorAgent считает Story Points
6. **Отчёт** — итоговый score 0–100, список замечаний, рекомендации

## Настройка Confluence

Для работы необходимо настроить Confluence MCP сервер в разделе **MCP** плагина:

1. Откройте вкладку **MCP** в GigaGit
2. Нажмите **+ Confluence**
3. Укажите:
   - **Адрес**: `https://company.atlassian.net/wiki` или `https://confluence.company.ru`
   - **Access Token**: Personal Access Token (PAT) из Confluence
   - **Сертификат** *(опционально)*: путь к `.pem` файлу для корпоративного CA

### Получение Personal Access Token

**Atlassian Cloud:**
- Перейдите в `https://id.atlassian.com/manage-profile/security/api-tokens`
- Создайте новый токен

**Confluence On-Premise:**
- Перейдите в `Профиль → Личный токен доступа`
- Создайте токен с правами на чтение

## Добавление ссылки на требования в спецификацию

При использовании Confluence URL, ссылка на исходную страницу автоматически добавляется:
- В блок промпта агентам как `Источник требований: <URL>`
- В PRD-документ в раздел «Источники»
- В `AssessmentReport.pageUrl` для отображения в панели оценки

### Формат в PRD

```markdown
## Источники требований

| Источник | Ссылка |
|----------|--------|
| Confluence | [Название страницы](https://...) |
```

## Кросс-проверка покрытия

После генерации спецификации автоматически выполняется проверка:
- Из текста страницы Confluence извлекаются до 30 ключевых терминов
- Проверяется, упомянуты ли они в сгенерированной спецификации
- Если покрытие < 50% — добавляется предупреждение в ValidationBanner

## Эвристика определения существующего ТЗ

Если страница содержит ≥2 из следующих паттернов, Spec Generator предложит переключиться в режим `validate`:
- Идентификаторы `FR-001`, `NFR-001`, `AC-001`, `EDGE-001`
- Ключевые слова: `Given`, `When`, `Then`
- Разделы: «Функциональные требования», «Критерии приёмки», «Acceptance Criteria»

## Связанные файлы

| Файл | Описание |
|------|----------|
| `ConfluenceFetcher.kt` | Точка входа: поиск credentials и загрузка страницы |
| `ConfluenceRestClient.kt` | HTTP-клиент для Confluence REST API |
| `ConfluenceUrlParser.kt` | Парсинг трёх форматов URL |
| `ConfluenceContentParser.kt` | Конвертация Storage Format XML → plain text |
| `ConfluenceContext.kt` | Модель данных загруженной страницы |
| `ConfluenceAssessmentOrchestrator.kt` | Оркестратор оценки ТЗ (5 фаз) |
| `AssessmentPanel.kt` | UI панель «Оценка ТЗ» |
| `SpecValidator.crossCheckConfluence()` | Кросс-проверка покрытия ключевых терминов |
