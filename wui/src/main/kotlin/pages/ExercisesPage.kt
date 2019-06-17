package pages

import PageId
import AppState
import debug
import debugFunEnd
import debugFunStart
import getElemById
import kotlin.dom.appendText
import kotlin.js.Date

object ExercisesPage : Page<String>() {
    override val pageId: PageId
        get() = PageId.EXERCISES

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises$")


    override fun build(pageState: String?) {
        val fl = debugFunStart("ExercisesPage.build")

        val courseId = extractCourseId(AppState.path)
        debug { "Course ID: $courseId" }

        //val state = pageState.unsafeCast<String?>()
        debug { "Page state: $pageState" }

        // Fetch exercises

        // Render

        // Paint

        getElemById("container").appendText("exercises")
        updateState("ExercisesPage ${Date().toISOString()}")

        debugFunEnd(fl)
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
