package components

import Auth
import CourseInfoCache
import JsonUtil
import PageName
import ReqMethod
import Role
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
import queries.CourseInfo
import spa.Page
import tmRender


object CoursesPage : Page() {

    @Serializable
    data class State(val coursesHtml: String, val role: Role)

    @Serializable
    data class StudentCourse(val id: String, val title: String)

    @Serializable
    data class TeacherCourse(val id: String, val title: String, val student_count: Int)


    override val pageName: PageName
        get() = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/?$")

    override fun build(pageStateStr: String?) {
        val funLog = debugFunStart("CoursesPage.build")

        val pageState = pageStateStr?.parseTo(State.serializer())

        when (Auth.activeRole) {
            Role.STUDENT -> buildStudentCourses(pageState)
            Role.TEACHER, Role.ADMIN -> buildTeacherCourses(pageState, Auth.activeRole)
        }

        funLog?.end()
    }

    override fun clear() {
        super.clear()
        getContainer().innerHTML = tmRender("tm-loading-placeholders", emptyMap())
    }


    private fun buildStudentCourses(pageState: State?) {
        val funLog = debugFunStart("CoursesPage.buildStudentCourses")

        if (pageState != null && pageState.role == Role.STUDENT) {
            debug { "Got courses html from state" }
            getContainer().innerHTML = pageState.coursesHtml
            return
        }

        MainScope().launch {
            val funLogFetch = debugFunStart("CoursesPage.buildStudentCourses.asyncFetchBuild")

            val resp = fetchEms("/student/courses", ReqMethod.GET).await()
            if (!resp.http200) {
                errorMessage { Str.fetchingCoursesFailed() }
                error("Fetching student courses failed with status ${resp.status}")
            }
            val courses = resp.parseTo(StudentCourse.serializer().list).await()

            // Populate course info cache
            courses.forEach {
                CourseInfoCache[it.id] = CourseInfo(it.id, it.title)
            }

            val coursesHtml = tmRender("tm-stud-course-list",
                    mapOf("title" to Str.coursesTitle(),
                            "courses" to courses.map {
                                objOf("title" to it.title, "id" to it.id)
                            }.toTypedArray()))

            debug { "Rendered courses html: $coursesHtml" }

            getContainer().innerHTML = coursesHtml

            val newState = State(coursesHtml, Role.STUDENT)
            updateState(JsonUtil.stringify(State.serializer(), newState))

            funLogFetch?.end()
        }

        funLog?.end()
    }

    private fun buildTeacherCourses(pageState: State?, activeRole: Role) {
        val funLog = debugFunStart("CoursesPage.buildTeacherCourses")

        if (pageState != null && pageState.role == activeRole) {
            debug { "Got courses html from state" }
            getContainer().innerHTML = pageState.coursesHtml
            return
        }

        MainScope().launch {
            val funLogFetch = debugFunStart("CoursesPage.buildTeacherCourses.asyncFetchBuild")

            val isAdmin = activeRole == Role.ADMIN

            val resp = fetchEms("/teacher/courses", ReqMethod.GET).await()
            if (!resp.http200) {
                errorMessage { Str.fetchingCoursesFailed() }
                error("Fetching teacher courses failed with status ${resp.status}")
            }
            val courses = resp.parseTo(TeacherCourse.serializer().list).await()

            // Populate course info cache
            courses.forEach {
                CourseInfoCache[it.id] = CourseInfo(it.id, it.title)
            }

            val html = tmRender("tm-teach-course-list", mapOf(
                    "title" to if (isAdmin) Str.coursesTitleAdmin() else Str.coursesTitle(),
                    "addCourse" to isAdmin,
                    "newCourse" to Str.newCourseLink(),
                    "courses" to courses.map {
                        objOf("id" to it.id,
                                "title" to it.title,
                                "count" to it.student_count,
                                "students" to if (it.student_count == 1) Str.coursesStudent() else Str.coursesStudents())
                    }.toTypedArray()))

            getContainer().innerHTML = html

            val newState = State(html, activeRole)
            updateState(JsonUtil.stringify(State.serializer(), newState))

            funLogFetch?.end()
        }

        funLog?.end()
    }

}
