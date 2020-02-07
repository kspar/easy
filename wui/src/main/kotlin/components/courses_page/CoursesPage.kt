package components.courses_page

import Auth
import PageName
import Role
import components.EasyPage
import debugFunStart
import getContainer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import parseTo
import stringify
import tmRender


object CoursesPage : EasyPage() {

    @Serializable
    data class State(val studentState: StudentState?, val teacherState: TeacherState?)

    @Serializable
    data class StudentState(val listState: StudentCourseListComp.State)

    @Serializable
    data class TeacherState(val listState: TeacherCourseListComp.State)


    override val pageName: PageName = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/?$")

    override fun build(pageStateStr: String?) {
        MainScope().launch {
            val funLog = debugFunStart("CoursesPage.build")

            val state = pageStateStr?.parseTo(State.serializer())
            val studentState = state?.studentState
            val teacherState = state?.teacherState

            when (Auth.activeRole) {
                Role.STUDENT -> buildStudentCourses(studentState)
                Role.TEACHER, Role.ADMIN -> buildTeacherCourses(teacherState, Auth.activeRole)
            }

            funLog?.end()
        }
    }

    private suspend fun buildStudentCourses(state: StudentState?) {
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

    private suspend fun buildTeacherCourses(state: TeacherState?, role: Role) {
        val lst = TeacherCourseListComp("content-container", role == Role.ADMIN)
        if (state == null) {
            lst.create().await()
        } else {
            lst.createFromState(state.listState).await()
        }
        lst.build()
        val listState = lst.getCacheableState()
        updateState(State.serializer().stringify(State(null, TeacherState(listState))))
    }

    override fun clear() {
        super.clear()
        getContainer().innerHTML = tmRender("tm-loading-placeholders",
                mapOf("marginTopRem" to 4, "titleWidthRem" to 20))
    }
}
