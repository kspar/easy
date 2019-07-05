package components

import PageName
import debug
import debugFunStart
import getContainer
import objOf
import spa.Page
import tmRender
import kotlin.browser.window

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

        getContainer().innerHTML = tmRender("tm-stud-exercises-list", mapOf(
                "exercises" to arrayOf(
                        objOf(
                                "link" to "/",
                                "title" to "1.1 Blbblabla",
                                "deadline" to "1. juuli 2019, 11.11",
                                "evalTeacher" to true,
                                "points" to 99,
                                "completed" to true),
                        objOf(
                                "link" to "/",
                                "title" to "1.2 Yoyoyoyoyo",
                                "deadline" to "2. juuli 2019, 11.11",
                                "evalMissing" to true,
                                "unstarted" to true
                        ),
                        objOf(
                                "link" to "/",
                                "title" to "1.3 Miskeskus",
                                "deadline" to "3. juuli 2019, 11.11",
                                "evalAuto" to true,
                                "points" to 42,
                                "started" to true
                        )
                )
        ))
//        getContainer().appendText("exercises")

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
