package pages

import PageState
import debug
import getElemById
import kotlin.dom.appendText

object ExercisesPage : Page {

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises$")


    override fun build() {
        val courseId = extractCourseId(PageState.path)
        debug { "Course ID: $courseId" }

        // Fetch exercises

        // Render

        // Paint

        getElemById("container").appendText("exercises")
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
