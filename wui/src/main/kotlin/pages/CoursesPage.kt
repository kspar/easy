package pages

import PageName
import debug
import debugFunEnd
import debugFunStart
import getElemById
import objOf
import spa.Page
import tmRender
import kotlin.js.Date

object CoursesPage : Page<CoursesPage.State>() {

    data class State(val coursesHtml: String)

    override val pageName: PageName
        get() = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build(pageState: State?) {
        val fl = debugFunStart("CoursesPage.build")

        val coursesListHtml = if (pageState != null) {
            debug { "Got courses from state" }
            pageState.coursesHtml
        } else {
            val html = genCourseListHtml()
            updateState(State(html))
            html
        }

        getElemById("container").innerHTML = coursesListHtml

        debugFunEnd(fl)
    }

    private fun genCourseListHtml() = tmRender("tm-stud-course-list",
            mapOf("course" to arrayOf(
                    objOf("title" to "Title${Date().getMilliseconds()}", "id" to { 1 + 2 }),
                    objOf("title" to "Title84", "id" to "42f")
            )))


}
