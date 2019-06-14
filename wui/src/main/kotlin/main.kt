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
    debug { window.location.pathname }

    // Simulating paths for testing
//    window.history.pushState(null, "", "/courses/12a/exercises")
//    window.history.pushState(null, "", "/courses")
//    debug { window.location.pathname }

    Page.id = pageIdFromPath(window.location.pathname)
    renderPage()
}

fun pageIdFromPath(path: String): PageId {
    return when {
        path.matches("^/courses$") -> PageId.COURSES
        path.matches("^/courses/\\w+/exercises$") -> PageId.EXERCISES
        else -> error("Unmatched path")
    }
}

fun renderPage() {
    clearPage()
    when (Page.id) {
        PageId.COURSES -> CoursesPage.render()
        PageId.EXERCISES -> ExercisesPage.render()
    }
}

fun clearPage() {
    getElemById("container").clear()
}

