package pages.courses

import Auth
import PageName
import Role
import debugFunStart
import getContainer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import pages.EasyPage
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
        val lst = StudentCourseListComp(null, "content-container")
        if (state == null) {
            lst.createAndBuild().await()
            val listState = lst.getCacheableState()
            updateState(State.serializer().stringify(State(StudentState(listState), null)))
        } else {
            lst.createFromState(state.listState).await()
            lst.rebuild()
        }
    }

    private suspend fun buildTeacherCourses(state: TeacherState?, role: Role) {
        val lst = TeacherCourseListComp(role == Role.ADMIN, null, "content-container")
        if (state == null) {
            lst.createAndBuild().await()
            val listState = lst.getCacheableState()
            updateState(State.serializer().stringify(State(null, TeacherState(listState))))
        } else {
            lst.createFromState(state.listState).await()
            lst.rebuild()
        }
    }

    override fun clear() {
        super.clear()
        getContainer().innerHTML = tmRender("tm-loading-placeholders",
                mapOf("marginTopRem" to 4, "titleWidthRem" to 20))
    }
}
