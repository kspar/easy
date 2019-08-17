package components

import PageName
import getContainer
import spa.Page
import kotlin.browser.window

object ExerciseSummaryPage : Page() {
    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises/\\w+/summary/?$")

    override fun build(pageStateStr: String?) {

        val pathIds = extractSanitizedPathIds(window.location.pathname)

        // TODO
        getContainer().innerHTML = "Exercise summary for exercise ${pathIds.exerciseId} on course ${pathIds.courseId}"

    }

    data class PathIds(val courseId: String, val exerciseId: String)

    private fun extractSanitizedPathIds(path: String): PathIds {
        val match = path.match("^/courses/(\\w+)/exercises/(\\w+)/summary/?\$")
        if (match != null && match.size == 3) {
            return PathIds(match[1], match[2])
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }
}