package components.courses_page

import Auth
import CourseInfoCache
import PageName
import Role
import Str
import components.EasyPage
import debugFunStart
import getContainer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import objOf
import parseTo
import queries.*
import stringify
import tmRender


object CoursesPage : EasyPage() {

    @Serializable
    data class State(val studentState: StudentState?, val teacherState: TeacherState?)

    @Serializable
    data class StudentState(val listState: StudentCourseListComp.State)

    @Serializable
    data class TeacherState(val listState: StudentCourseListComp.State)



    @Serializable
    data class TeacherCourses(val courses: List<TeacherCourse>)

    @Serializable
    data class TeacherCourse(val id: String, val title: String, val student_count: Int)


    override val pageName: PageName = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/?$")

    override fun build(pageStateStr: String?) {
        val funLog = debugFunStart("CoursesPage.build")

        val state = pageStateStr?.parseTo(State.serializer())
        val studentState = state?.studentState
        val teacherState = state?.teacherState

        when (Auth.activeRole) {
            Role.STUDENT -> buildStudentCourses2(studentState)
            Role.TEACHER, Role.ADMIN -> buildTeacherCourses(null, Auth.activeRole)
        }

        funLog?.end()
    }

    private fun buildStudentCourses2(state: StudentState?) = MainScope().launch {
        val lst = StudentCourseListComp("content-container")
        if (state == null) {
            lst.create().await()
        } else {
            lst.createFromState(state.listState).await()
        }
        lst.build()
        val listState = lst.getCacheableState()
        updateState(State.serializer().stringify(State(StudentState(listState), null)))
    }


    override fun clear() {
        super.clear()
        getContainer().innerHTML = tmRender("tm-loading-placeholders",
                mapOf("marginTopRem" to 4, "titleWidthRem" to 20))
    }



    private fun buildTeacherCourses(pageState: State?, activeRole: Role) {
        val funLog = debugFunStart("CoursesPage.buildTeacherCourses")

        MainScope().launch {
            val funLogFetch = debugFunStart("CoursesPage.buildTeacherCourses.asyncFetchBuild")

            val isAdmin = activeRole == Role.ADMIN

            val resp = fetchEms("/teacher/courses", ReqMethod.GET,
                    successChecker = { http200 }).await()
            val courses = resp.parseTo(TeacherCourses.serializer()).await()

            // Populate course info cache
            courses.courses.forEach {
                CourseInfoCache[it.id] = CourseInfo(it.id, it.title)
            }

            val html = tmRender("tm-teach-course-list", mapOf(
                    "title" to if (isAdmin) Str.coursesTitleAdmin() else Str.coursesTitle(),
                    "addCourse" to isAdmin,
                    "newCourse" to Str.newCourseLink(),
                    "noCoursesLabel" to Str.noCoursesLabel(),
                    "courses" to courses.courses.map {
                        objOf("id" to it.id,
                                "title" to it.title,
                                "count" to it.student_count,
                                "students" to if (it.student_count == 1) Str.coursesStudent() else Str.coursesStudents())
                    }.toTypedArray()))

            getContainer().innerHTML = html

            funLogFetch?.end()
        }

        funLog?.end()
    }

}
