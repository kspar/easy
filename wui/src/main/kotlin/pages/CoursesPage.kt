package pages

import EasyRole
import Keycloak
import PageName
import ReqMethod
import Str
import debug
import debugFunStart
import errorMessage
import fetchEms
import getContainer
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.list
import objOf
import parseTo
import spa.Page
import tmRender
import kotlin.dom.clear


object CoursesPage : Page<CoursesPage.State>() {

    data class State(val coursesHtml: String)

    @Serializable
    data class StudentCourse(val id: String, val title: String)

    override val pageName: PageName
        get() = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build(pageState: State?) {
        val funLog = debugFunStart("CoursesPage.build")

        when (Keycloak.activeRole) {
            EasyRole.STUDENT -> buildStudentCourses(pageState)
            EasyRole.TEACHER, EasyRole.ADMIN -> buildTeacherCourses()
        }

        funLog?.end()
    }

    override fun clear() {
        getContainer().clear()
        Sidenav.remove()
    }

    private fun buildStudentCourses(pageState: State?) {
        val funLog = debugFunStart("CoursesPage.buildStudentCourses")

        // Just a PoC of using state, should probably not use here because courses might change
        if (pageState != null) {
            debug { "Got courses from state" }
            getContainer().innerHTML = pageState.coursesHtml

        } else {

            MainScope().launch {
                val funLogFetch = debugFunStart("CoursesPage.buildStudentCourses.asyncFetchBuild")

                val resp = fetchEms("/student/courses", ReqMethod.GET).await()
                if (!resp.http200) {
                    errorMessage { Str.fetchingCoursesFailed }
                    error("Fetching student courses failed")
                }
                val courses = resp.parseTo(StudentCourse.serializer().list).await()
                val coursesHtml = tmRender("tm-stud-course-list",
                        mapOf("title" to Str.coursesPageTitle,
                                "courses" to courses.map {
                                    objOf("title" to it.title, "id" to it.id)
                                }.toTypedArray()))

                debug { "Rendered courses html: $coursesHtml" }
                updateState(State(coursesHtml))

                getContainer().innerHTML = coursesHtml

                funLogFetch?.end()
            }
        }

        funLog?.end()
    }

    private fun buildTeacherCourses() {
        // TODO
        getContainer().innerHTML = "<a href=\"/courses/abc/exercises\">exercises</a>"
    }

}
