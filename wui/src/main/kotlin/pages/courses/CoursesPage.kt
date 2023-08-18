package pages.courses

import Auth
import CONTENT_CONTAINER_ID
import PageName
import Role
import ScrollPosition
import Str
import getWindowScrollPosition
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import kotlinx.serialization.Serializable
import pages.EasyPage
import pages.Title
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import parseTo
import restore
import rip.kspar.ezspa.*
import stringify
import warn
import kotlin.js.Promise


object CoursesPage : EasyPage() {

    const val REDIR_ALLOWED_PARAM = "redir"

    @Serializable
    data class State(val rootState: CoursesRootComponent.State, val scrollPosition: ScrollPosition)

    private var rootComp: CoursesRootComponent? = null

    override val pageName: PageName = PageName.COURSES

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(activePage = ActivePage.MY_COURSES)

    override val titleSpec: Title.Spec
        get() = Title.Spec(Str.myCourses())

    override val pathSchema = "/courses"

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        getHtml().addClass("wui3")
        doInPromise {
            val state = pageStateStr?.parseTo(State.serializer())
            val root = CoursesRootComponent(Auth.activeRole, CONTENT_CONTAINER_ID)
            rootComp = root

            if (state == null) {
                root.createAndBuild().await()
            } else {
                root.createAndBuildFromState(state.rootState).await()
                state.scrollPosition.restore()
            }
        }
    }

    override fun onPreNavigation() {
        val root = rootComp
        if (root != null) {
            updateState(State.serializer().stringify(State(root.getCacheableState(), getWindowScrollPosition())))
        } else {
            warn { "Cannot cache courses page - root component is null" }
        }
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
        rootComp = null
        getHtml().removeClass("wui3")
    }

    fun link() = constructPathLink(emptyMap())
}


class CoursesRootComponent(
    private val role: Role,
    dstId: String
) : CacheableComponent<CoursesRootComponent.State>(null, dstId) {

    @Serializable
    data class State(val studentState: StudentCoursesComp.State?, val teacherState: TeacherCoursesComp.State?)


    private var studentRoot: StudentCoursesComp? = null
    private var teacherRoot: TeacherCoursesComp? = null

    override val children: List<Component>
        get() = listOfNotNull(studentRoot, teacherRoot)

    override fun create(): Promise<*> = doInPromise {
        when (role) {
            Role.STUDENT -> studentRoot = StudentCoursesComp(this)
            Role.TEACHER -> teacherRoot = TeacherCoursesComp(false, this)
            Role.ADMIN -> teacherRoot = TeacherCoursesComp(true, this)
        }
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        when {
            state.studentState != null -> studentRoot = StudentCoursesComp(this)
            state.teacherState != null -> teacherRoot = TeacherCoursesComp(role == Role.ADMIN, this)
        }
    }

    override fun createAndBuildChildrenFromState(state: State): Promise<*> = doInPromise {
        studentRoot?.createAndBuildFromState(state.studentState!!)
        teacherRoot?.createAndBuildFromState(state.teacherState!!)
    }

    override fun render(): String = plainDstStr(children[0].dstId)

    override fun getCacheableState(): State = State(studentRoot?.getCacheableState(), teacherRoot?.getCacheableState())
}

