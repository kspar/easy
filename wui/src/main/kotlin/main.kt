import page.CoursesPage
import page.ExercisesPage
import kotlin.browser.window

object Page {
    var id: PageId? = null
}
enum class PageId {
    COURSES,
    EXERCISES
}

fun main() {
    println("wut")

    // Check path, update Page
    debug { window.location.pathname }

    // Simulating paths for testing
//    window.history.pushState(null, "", "/courses/12a/exercises")
    window.history.pushState(null, "", "/courses")

    debug { window.location.pathname }

    Page.id = pageIdFromPath(window.location.pathname)

    // Render correct page contents
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
    when(Page.id) {
        PageId.COURSES -> CoursesPage.render()
        PageId.EXERCISES -> ExercisesPage.render()
    }
}

