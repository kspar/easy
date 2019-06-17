import pages.Page
import kotlin.browser.window

object PageManager {

    // No need for thread-safety, JS runs single-threaded
    private var pages: List<Page> = emptyList()

    fun registerPages(vararg newPages: Page) {
        pages += newPages
    }

    fun updatePage(pageState: Any? = null) {
        debug { "updatePage" }

        val path = window.location.pathname
        debug { "Current path: $path" }
        AppState.path = path

        val page = pageFromPath(path)
        AppState.id = page.pageId

        page.clear()
        page.build(pageState)
    }

    private fun pageFromPath(path: String): Page {
        val matchingPages = pages.filter { it.pathMatches(path) }
        val matchingCount = matchingPages.size
        return when {
            matchingCount == 1 -> matchingPages.single()
            matchingCount < 1 -> error("Path $path did not match any pages")
            else -> error("Path $path matched several pages: ${matchingPages.map { it.pageId }}")
        }
    }

}