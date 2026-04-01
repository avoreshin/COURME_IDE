package changelogai.feature.jenkins.engine

import changelogai.feature.jenkins.model.BuildStatus
import changelogai.feature.jenkins.model.JenkinsAnalysis
import changelogai.feature.jenkins.model.JenkinsBuild
import changelogai.feature.jenkins.model.JenkinsPipeline

object JenkinsDemoData {

    private val demoLog = """
        [Pipeline] Start of Pipeline
        [Pipeline] node
        Running on Jenkins in /var/jenkins_home/workspace/my-service
        [Pipeline] stage (Build)
        [Pipeline] sh
        + ./gradlew build
        > Task :compileKotlin FAILED

        FAILURE: Build failed with an exception.

        * What went wrong:
        Execution failed for task ':compileKotlin'.
        > Compilation error. See log for more details

        e: src/main/kotlin/com/example/OrderService.kt: (42, 15):
          Unresolved reference: UserRepository

        e: src/main/kotlin/com/example/PaymentController.kt: (87, 9):
          None of the following functions can be called with the arguments supplied:
          public final fun process(order: Order): Result<Unit>

        * Try:
        > Run with --stacktrace option to get a stack trace.

        BUILD FAILED in 23s
        3 actionable tasks: 3 executed
    """.trimIndent()

    val demoAnalysis = JenkinsAnalysis(
        rootCause = "Ошибка компиляции: `UserRepository` не найден в `OrderService.kt`. " +
                "Вероятно, был удалён или переименован класс/интерфейс в последнем коммите.",
        relatedFiles = listOf(
            "src/main/kotlin/com/example/OrderService.kt",
            "src/main/kotlin/com/example/PaymentController.kt"
        ),
        suggestions = listOf(
            "Проверить, не был ли переименован `UserRepository` в последнем коммите",
            "Убедиться, что зависимость модуля с `UserRepository` добавлена в build.gradle.kts",
            "Проверить сигнатуру метода `process()` в `PaymentController.kt` — ожидается `Order`, передаётся другой тип"
        )
    )

    val pipelines: List<JenkinsPipeline> = listOf(
        JenkinsPipeline(
            name = "my-service",
            url = "http://jenkins.demo/job/my-service",
            status = BuildStatus.FAILURE,
            lastBuild = JenkinsBuild(
                number = 142,
                status = BuildStatus.FAILURE,
                timestamp = System.currentTimeMillis() - 600_000,
                durationMs = 23_000,
                log = demoLog
            )
        ),
        JenkinsPipeline(
            name = "api-gateway",
            url = "http://jenkins.demo/job/api-gateway",
            status = BuildStatus.SUCCESS,
            lastBuild = JenkinsBuild(
                number = 89,
                status = BuildStatus.SUCCESS,
                timestamp = System.currentTimeMillis() - 1_800_000,
                durationMs = 45_000,
                log = "BUILD SUCCESSFUL in 45s"
            )
        ),
        JenkinsPipeline(
            name = "frontend",
            url = "http://jenkins.demo/job/frontend",
            status = BuildStatus.IN_PROGRESS,
            lastBuild = JenkinsBuild(
                number = 55,
                status = BuildStatus.IN_PROGRESS,
                timestamp = System.currentTimeMillis() - 120_000,
                durationMs = 0,
                log = "Build in progress..."
            )
        )
    )
}
