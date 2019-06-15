import pages.CoursesPage
import pages.ExercisesPage
import pages.Page
import kotlin.browser.window


fun main() {
    debug { "Yello" }

    renderOnce()
    updatePage()
}

/**
 * Do actions that must be done only once per document load i.e. SPA refresh
 */
fun renderOnce() {
    debug { "renderOnce" }

}

fun updatePage() {
    // For starters, assume all info about a page is transferred via URL

    debug { "updatePage" }

    // Simulating paths for testing
//    window.history.pushState(null, "", "/courses/12a/exercises")
//    window.history.pushState(null, "", "/courses")

    val path = window.location.pathname
    debug { "Current path: $path" }

    PageState.path = path

    val page = pageFromPath(path)

    PageState.id = page.pageId

    // Instead of a global clearPage(), it would be possible to define custom clearPages
    // for all Pages with a default one in Page

    page.clear()
    page.build()
}

fun pageFromPath(path: String): Page {
    return when {
        CoursesPage.pathMatches(path) -> CoursesPage
        ExercisesPage.pathMatches(path) -> ExercisesPage
        else -> error("Unmatched path")
    }
}

