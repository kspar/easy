package pages.courses

import Auth
import CONTENT_CONTAINER_ID
import PageName
import Role
import ScrollPosition
import debugFunStart
import getWindowScrollPosition
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import pages.EasyPage
import parseTo
import plainDstStr
import restoreWindowScroll
import rip.kspar.ezspa.CacheableComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import stringify
import warn
import kotlin.js.Promise


object CoursesPage : EasyPage() {

    @Serializable
    data class State(val rootState: CoursesRootComponent.State, val scrollPosition: ScrollPosition)

    private var rootComp: CoursesRootComponent? = null

    override val pageName: PageName = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/?$")

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        MainScope().launch {
            val funLog = debugFunStart("CoursesPage.build")

            val state = pageStateStr?.parseTo(State.serializer())

            val root = CoursesRootComponent(Auth.activeRole, CONTENT_CONTAINER_ID)
            rootComp = root

            if (state == null) {
                root.createAndBuild().await()
            } else {
                root.createAndBuildFromState(state.rootState).await()
                restoreWindowScroll(state.scrollPosition)
            }

            funLog?.end()
        }
    }

    override fun onPreNavigation() {
        val root = rootComp
        if (root != null) {
            updateState(State.serializer().stringify(State(root.getCacheableState(), getWindowScrollPosition())))
            rootComp = null
        } else {
            warn { "Cannot cache courses page - root component is null" }
        }
    }
}


class CoursesRootComponent(
        private val role: Role,
        dstId: String
) : CacheableComponent<CoursesRootComponent.State>(null, dstId) {

    @Serializable
    data class State(val studentState: StudentCoursesRootComp.State?, val teacherState: TeacherCoursesRootComp.State?)


    private var studentRoot: StudentCoursesRootComp? = null
    private var teacherRoot: TeacherCoursesRootComp? = null

    override val children: List<Component>
        get() = listOfNotNull(studentRoot, teacherRoot)

    override fun create(): Promise<*> = doInPromise {
        when (role) {
            Role.STUDENT -> studentRoot = StudentCoursesRootComp(this)
            Role.TEACHER -> teacherRoot = TeacherCoursesRootComp(false, this)
            Role.ADMIN -> teacherRoot = TeacherCoursesRootComp(true, this)
        }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        when {
            state.studentState != null -> studentRoot = StudentCoursesRootComp(this)
            state.teacherState != null -> teacherRoot = TeacherCoursesRootComp(role == Role.ADMIN, this)
        }
    }

    override fun createAndBuildChildrenFromState(state: State): Promise<*> = doInPromise {
        studentRoot?.createAndBuildFromState(state.studentState!!)
        teacherRoot?.createAndBuildFromState(state.teacherState!!)
    }

    override fun render(): String = plainDstStr(children[0].dstId)

    override fun getCacheableState(): State = State(studentRoot?.getCacheableState(), teacherRoot?.getCacheableState())
}

