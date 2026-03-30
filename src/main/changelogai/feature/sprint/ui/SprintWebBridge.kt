package changelogai.feature.sprint.ui

import changelogai.feature.sprint.engine.SprintAnalyzer
import changelogai.feature.sprint.model.JiraStory
import changelogai.feature.sprint.model.ReleaseComposition
import changelogai.feature.sprint.model.SprintAnalysis
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * Мост JS ↔ Kotlin для дашборда Sprint War Room.
 * Использует JBCefJSQuery (IntelliJ Platform JCEF API) для обработки вызовов из JS.
 */
class SprintWebBridge(
    private val browser: JBCefBrowser,
    private val analyzer: SprintAnalyzer
) {
    private val mapper = jacksonObjectMapper()
    private var jsQuery: JBCefJSQuery? = null

    /** Текущие истории — для поиска при decompose */
    private var allStories: List<JiraStory> = emptyList()

    /** Текущий загруженный релиз — для экспорта */
    private var currentRelease: ReleaseComposition? = null

    /** Вызывается при запросе refresh из JS */
    var onRefreshRequested: (() -> Unit)? = null

    fun setStories(stories: List<JiraStory>) {
        allStories = stories
    }

    /** Вливает результаты анализа в JS-дашборд */
    fun sendAnalysis(analysis: SprintAnalysis) {
        val json = mapper.writeValueAsString(analysis)
        browser.cefBrowser.executeJavaScript("window.loadAnalysis($json)", "", 0)
    }

    /** Загружает HTML дашборда и регистрирует JS-мост */
    fun injectDashboard() {
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        jsQuery = query

        // Регистрируем обработчик входящих запросов
        query.addHandler { request ->
            try {
                val result = handleRequest(request)
                JBCefJSQuery.Response(result)
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 500, e.message ?: "error")
            }
        }

        // После загрузки страницы — инжектируем мост как window.cefQuery
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                // Экспонируем функцию моста как window.cefQuery
                val bridgeFn = query.getFuncName()
                val injectScript = """
                    window.cefQuery = function(opts) {
                        var req = opts.request || '';
                        var onSuccess = opts.onSuccess || function(){};
                        var onFailure = opts.onFailure || function(){};
                        ${query.inject("req", "r => onSuccess(r)", "(c,m) => onFailure(c,m)")}
                    };
                """.trimIndent()
                b?.executeJavaScript(injectScript, "", 0)
            }
        }, browser.cefBrowser)

        // Загружаем HTML
        val html = loadDashboardHtml()
        val encoded = java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20")
        browser.loadURL("data:text/html;charset=utf-8,$encoded")
    }

    /** Dispatch входящих запросов из JS (вызывается с фонового потока через addHandler) */
    private fun handleRequest(request: String): String {
        val colonIdx = request.indexOf(':')
        if (colonIdx < 0) throw IllegalArgumentException("bad request format")
        val action  = request.substring(0, colonIdx)
        val payload = request.substring(colonIdx + 1)

        return when (action) {
            "decompose" -> {
                val story = allStories.firstOrNull { it.key == payload }
                    ?: throw NoSuchElementException("Story $payload not found")
                val subtasks = analyzer.decompose(story)
                mapper.writeValueAsString(subtasks)
            }
            "createSubtasks" -> {
                val req = mapper.readValue(payload, CreateSubtasksRequest::class.java)
                analyzer.createSubtasks(req.parentKey, req.subtasks)
                "ok"
            }
            "refresh" -> {
                ApplicationManager.getApplication().invokeLater {
                    onRefreshRequested?.invoke()
                }
                "ok"
            }
            "getVersions" -> {
                val versions = analyzer.getVersions(payload)
                mapper.writeValueAsString(versions)
            }
            "loadRelease" -> {
                val colonIdx2 = payload.indexOf(':')
                if (colonIdx2 < 0) throw IllegalArgumentException("loadRelease requires project:version")
                val projectKey = payload.substring(0, colonIdx2)
                val versionName = payload.substring(colonIdx2 + 1)
                val release = analyzer.loadRelease(projectKey, versionName)
                currentRelease = release
                mapper.writeValueAsString(release)
            }
            "exportRelease" -> {
                val release = currentRelease
                    ?: throw IllegalStateException("No release loaded")
                analyzer.exportReleaseMarkdown(release)
            }
            else -> throw IllegalArgumentException("unknown action: $action")
        }
    }

    private fun loadDashboardHtml(): String =
        javaClass.classLoader
            ?.getResourceAsStream("sprint/dashboard.html")
            ?.bufferedReader()?.readText()
            ?: "<html><body style='background:#08090B;color:#DDE0EA'>Dashboard not found</body></html>"

    private data class CreateSubtasksRequest(
        val parentKey: String = "",
        val subtasks: List<String> = emptyList()
    )
}
