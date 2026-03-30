package changelogai.feature.gigacodeae.orchestrator

import changelogai.feature.gigacodeae.skill.SkillDefinition

/**
 * Rules-based классификатор намерений пользователя.
 * Определяет какого суб-агента вызвать БЕЗ дополнительного вызова LLM.
 */
class IntentClassifier {

    enum class Intent {
        CODE_WRITE,
        CODE_REVIEW,
        TEST_WRITE,
        SEARCH,
        QUESTION,
        COMPLEX_TASK,
        TOOL_EXECUTE,
        REFACTORING
    }

    data class IntentPlan(
        val primaryIntent: Intent,
        val agents: List<String>,
        val parallel: Boolean = false,
        val needsPlanning: Boolean = false
    )

    /** Имена доступных MCP-функций — устанавливается оркестратором перед classify() */
    var availableMcpToolNames: Set<String> = emptySet()

    fun classify(userText: String, currentSkill: SkillDefinition): IntentPlan {
        // 1. Скилл явно задан пользователем — самый надёжный сигнал
        when (currentSkill.id) {
            "CODE_ASSISTANT" -> return IntentPlan(Intent.CODE_WRITE, listOf("Code"))
            "TEST_WRITER" -> return IntentPlan(Intent.TEST_WRITE, listOf("Test"))
            "CODE_REVIEWER" -> return IntentPlan(Intent.CODE_REVIEW, listOf("Review"))
            "REFACTORING" -> return IntentPlan(Intent.REFACTORING, listOf("Code"))
            "DEVOPS" -> return IntentPlan(Intent.TOOL_EXECUTE, listOf("Tool"))
            "SEARCH" -> return IntentPlan(Intent.SEARCH, listOf("Search"))
            "PLANNER" -> return IntentPlan(Intent.COMPLEX_TASK, listOf("Planner"), needsPlanning = true)
        }

        val lower = userText.lowercase()

        // 2. MCP / внешние инструменты (Jira, Confluence, etc.) → ToolAgent
        if (isMcpToolRequest(lower)) {
            return IntentPlan(Intent.TOOL_EXECUTE, listOf("Tool"))
        }

        // 3. Сложная задача (нумерованный список / несколько шагов)
        if (isComplexTask(lower)) {
            return IntentPlan(Intent.COMPLEX_TASK, listOf("Planner"), needsPlanning = true)
        }

        // 4. Поиск
        if (isSearchQuery(lower)) {
            return IntentPlan(Intent.SEARCH, listOf("Search"))
        }

        // 5. Тесты
        if (isTestRequest(lower)) {
            return IntentPlan(Intent.TEST_WRITE, listOf("Test"))
        }

        // 6. Ревью
        if (isReviewRequest(lower)) {
            return IntentPlan(Intent.CODE_REVIEW, listOf("Review"))
        }

        // 7. Простой вопрос — прямой LLM без инструментов
        if (isSimpleQuestion(lower, userText)) {
            return IntentPlan(Intent.QUESTION, emptyList())
        }

        // 8. Default: задача по коду
        return IntentPlan(Intent.CODE_WRITE, listOf("Code"))
    }

    private fun isMcpToolRequest(lower: String): Boolean {
        // Статические ключевые слова внешних сервисов
        val externalServiceKeywords = listOf(
            "jira", "жира", "тикет", "ticket", "issue",
            "confluence", "конфлюенс", "вики", "wiki",
            "slack", "слак",
            "github", "гитхаб", "pull request", "пулл реквест",
            "gitlab", "гитлаб",
            "notion", "ноушн",
            "trello", "трелло",
            "linear", "линеар",
            "создай задачу", "создать задачу", "новую задачу", "новый тикет",
            "create task", "create issue", "create ticket",
            "обнови задачу", "update issue", "update task",
            "mcp"
        )
        if (externalServiceKeywords.any { it in lower }) return true

        // Динамическая проверка: если текст содержит имя доступного MCP-инструмента
        if (availableMcpToolNames.isNotEmpty()) {
            val words = lower.split(Regex("""\s+"""))
            for (toolName in availableMcpToolNames) {
                // Имена MCP-функций обычно snake_case, например "jira_create_issue"
                // Проверяем и полное имя, и части (prefix до первого _)
                val toolLower = toolName.lowercase()
                if (toolLower in lower) return true
                val prefix = toolLower.substringBefore("_")
                if (prefix.length >= 4 && prefix in lower) return true
            }
        }

        return false
    }

    private fun isComplexTask(lower: String): Boolean {
        // Нумерованный список: "1. ...\n2. ..." или "1) ... 2) ..."
        val numberedPattern = Regex("""(?m)^\s*\d+[.)]\s""")
        if (numberedPattern.findAll(lower).count() >= 2) return true
        // Несколько задач через "затем", "потом", "после этого"
        val sequenceWords = listOf("затем", "потом", "после этого", "далее", "then", "after that", "next")
        if (sequenceWords.count { it in lower } >= 2) return true
        return false
    }

    private fun isSearchQuery(lower: String): Boolean {
        val searchKeywords = listOf(
            "найди", "поиск", "где ", "where ", "find ", "search ",
            "покажи где", "в каком файле", "в каких файлах", "grep"
        )
        return searchKeywords.any { it in lower }
    }

    private fun isTestRequest(lower: String): Boolean {
        val testKeywords = listOf(
            "напиши тест", "добавь тест", "создай тест", "write test",
            "add test", "generate test", "unit test", "юнит тест",
            "покрой тестами", "тестирован"
        )
        return testKeywords.any { it in lower }
    }

    private fun isReviewRequest(lower: String): Boolean {
        val reviewKeywords = listOf(
            "ревью", "review", "проверь код", "check code",
            "посмотри код", "оцени код", "code review"
        )
        return reviewKeywords.any { it in lower }
    }

    private fun isSimpleQuestion(lower: String, original: String): Boolean {
        if (original.length > 150) return false
        if (!original.trimEnd().endsWith("?")) return false
        // Не содержит ссылок на файлы
        if ("@" in original) return false
        return true
    }
}
