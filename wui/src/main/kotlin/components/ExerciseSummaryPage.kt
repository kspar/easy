package components

import PageName
import getContainer
import getElemsByClass
import libheaders.Materialize
import tmRender
import kotlin.browser.window

object ExerciseSummaryPage : EasyPage() {
    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises/\\w+/summary/?$")

    override fun build(pageStateStr: String?) {

        val pathIds = extractSanitizedPathIds(window.location.pathname)

        getContainer().innerHTML = "Exercise summary for exercise ${pathIds.exerciseId} on course ${pathIds.courseId}"

        getContainer().innerHTML = tmRender("tm-teach-exercise", mapOf(
                "coursesHref" to "/courses",
                "courseHref" to "/courses/${pathIds.courseId}/exercises",
                "courses" to "Minu kursused",
                "courseTitle" to "TODO",
                "exerciseTitle" to "TODO",
                "exerciseLabel" to "Ülesanne",
                "testingLabel" to "Katsetamine",
                "studentSubmLabel" to "Esitused"
        ))

        Materialize.Tabs.init(getElemsByClass("tabs")[0])

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