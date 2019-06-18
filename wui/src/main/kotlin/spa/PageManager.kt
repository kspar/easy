package spa

import AppState
import debug
import debugFunStart
import kotlin.browser.window

object PageManager {

    // No need for thread-safety, JS runs single-threaded
    private var pages: List<Page<Any>> = emptyList()

    fun registerPages(vararg newPages: Page<*>) {
        // Mandatory pledge:
        // Dear compiler, I am aware that these objects are not really Page<Any> but I promise
        // to take care to only pass to these Pages the objects that they expect.
        // At least as long as the JS History API implementations behave. Hopeful smiley face?
        pages += newPages.map { it as Page<Any> }
    }

    fun updatePage(pageState: Any? = null) {
        // TODO: clear from non-spa stuff: logging and appstate
        val funLog = debugFunStart("updatePage")

        val path = window.location.pathname
        debug { "Current path: $path" }
        AppState.path = path

        val page = pageFromPath(path)

        page.clear()
        page.build(pageState)

        funLog?.end()
    }

    private fun pageFromPath(path: String): Page<Any> {
        val matchingPages = pages.filter { it.pathMatches(path) }
        val matchingCount = matchingPages.size
        return when {
            matchingCount == 1 -> matchingPages.single()
            matchingCount < 1 -> error("Path $path did not match any pages")
            else -> error("Path $path matched several pages: ${matchingPages.map { it.pageName }}")
        }
    }

}