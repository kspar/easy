package spa

import Str
import debugFunStart
import getContainer
import queries.AbortController
import queries.AbortSignal
import tmRender
import kotlin.browser.window

object PageManager {

    // No need for thread-safety, JS runs single-threaded
    private var pages: List<Page> = emptyList()
    private var currentPage: Page? = null

    private var abortControllers = mutableListOf<AbortController>()


    fun updatePage(pageState: String? = null) {
        val funLog = debugFunStart("updatePage")

        abortControllers.forEach { it.abort() }
        abortControllers.clear()

        val path = window.location.pathname
        val newPage = pageFromPath(path)

        currentPage?.destruct()
        currentPage = newPage

        newPage.assertAuthorisation()
        newPage.clear()
        newPage.build(pageState)

        funLog?.end()
    }

    fun navigateTo(path: String) {
        window.history.pushState(null, "", path)
        updatePage()
    }

    fun getNavCancelSignal(): AbortSignal =
            AbortController().also {
                abortControllers.add(it)
            }.signal

    fun registerPages(newPages: List<Page>) {
        pages += newPages
    }

    private fun pageFromPath(path: String): Page {
        val matchingPages = pages.filter { it.pathMatches(path) }
        val matchingCount = matchingPages.size
        return when {
            matchingCount == 1 -> matchingPages.single()
            matchingCount < 1 -> {
                handlePageNotFound()
                error("Path $path did not match any pages")
            }
            else -> error("Path $path matched several pages: ${matchingPages.map { it.pageName }}")
        }
    }

    private fun handlePageNotFound() {
        // TODO: dead robot
        getContainer().innerHTML = tmRender("tm-no-access-page", mapOf(
                "title" to Str.notFoundPageTitle(),
                "msg" to Str.notFoundPageMsg()
        ))
    }

}