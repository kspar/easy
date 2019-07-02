package pages

import PageName
import debug
import debugFunStart
import getElemById
import spa.Page
import kotlin.browser.window
import kotlin.dom.appendText

object ExercisesPage : Page() {
    override val pageName: PageName
        get() = PageName.EXERCISES

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises$")


    override fun build(pageStateStr: String?) {
        val funLog = debugFunStart("ExercisesPage.build")

        val path = window.location.pathname
        debug { "Current path: $path" }

        val courseId = extractCourseId(path)
        debug { "Course ID: $courseId" }

        Sidenav.build(courseId)

        // Fetch exercises

        // Render

        // Paint

        getElemById("container").appendText("exercises")

        funLog?.end()
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
