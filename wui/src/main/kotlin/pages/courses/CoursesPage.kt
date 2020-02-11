package pages.courses

import Auth
import PageName
import Role
import debugFunStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import pages.EasyPage
import parseTo
import stringify


object CoursesPage : EasyPage() {

    @Serializable
    data class State(val studentState: StudentState?, val teacherState: TeacherState?)

    @Serializable
    data class StudentState(val listState: StudentCoursesRootComp.State)

    @Serializable
    data class TeacherState(val listState: TeacherCoursesRootComp.State)


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
        val root = StudentCoursesRootComp(null, "content-container")
        if (state == null) {
            root.createAndBuild().await()
            val rootState = root.getCacheableState()
            updateState(State.serializer().stringify(State(StudentState(rootState), null)))
        } else {
            root.createFromState(state.listState).await()
            root.rebuild()
        }
    }

    private suspend fun buildTeacherCourses(state: TeacherState?, role: Role) {
        val root = TeacherCoursesRootComp(role == Role.ADMIN, null, "content-container")
        if (state == null) {
            root.createAndBuild().await()
            val rootState = root.getCacheableState()
            updateState(State.serializer().stringify(State(null, TeacherState(rootState))))
        } else {
            root.createFromState(state.listState).await()
            root.rebuild()
        }
    }
}
