import pages.CoursesPage
import pages.ExercisesPage
import kotlin.browser.window
import kotlin.dom.clear


fun main() {
    println("wut")

    renderOnce()
    updatePage()
}

/**
 * Do actions that must be done only once per document load i.e. SPA refresh
 */
fun renderOnce() {

}

fun updatePage() {
    // For starters, assume all info about a page is transferred via URL

    // Simulating paths for testing
//    window.history.pushState(null, "", "/courses/12a/exercises")
//    window.history.pushState(null, "", "/courses")
//    debug { window.location.pathname }

    val path = window.location.pathname
    debug { path }
    PageState.path = path
    PageState.id = pageIdFromPath(path)
    renderPage()
}

fun pageIdFromPath(path: String): PageId {
    return when {
        CoursesPage.pathMatches(path) -> PageId.COURSES
        ExercisesPage.pathMatches(path) -> PageId.EXERCISES
        else -> error("Unmatched path")
    }
}

fun renderPage() {
    clearPage()
    when (PageState.id) {
        PageId.COURSES -> CoursesPage.build()
        PageId.EXERCISES -> ExercisesPage.build()
    }
}

fun clearPage() {
    getElemById("container").clear()
}

