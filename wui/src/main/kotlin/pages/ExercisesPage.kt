package pages

import PageId
import AppState
import debug
import getElemById
import kotlin.dom.appendText
import kotlin.js.Date

object ExercisesPage : Page() {
    override val pageId: PageId
        get() = PageId.EXERCISES

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises$")


    override fun build(pageState: Any?) {
        debug { "ExercisesPage.build" }
        val courseId = extractCourseId(AppState.path)
        debug { "Course ID: $courseId" }

        val state = pageState.unsafeCast<String?>()
        debug { "Page state: $state" }

        // Fetch exercises

        // Render

        // Paint

        getElemById("container").appendText("exercises")
        updateState("ExercisesPage ${Date().toISOString()}")
    }

    private fun extractCourseId(path: String): String {
        val match = path.match("^/courses/(\\w+)/exercises$")
        if (match != null && match.size == 2) {
            return match[1]
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }
}
