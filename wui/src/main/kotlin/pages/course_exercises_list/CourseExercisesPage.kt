package pages.course_exercises_list

import Auth
import PageName
import Role
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import restore
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getHtml


object CourseExercisesPage : EasyPage() {

    private var rootComp: Component? = null

    override val pageName: PageName
        get() = PageName.EXERCISES

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_EXERCISES)

    override val pathSchema = "/courses/{courseId}/exercises"

    override val courseId: String
        get() = parsePathParams()["courseId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val scrollPosition = pageStateStr.getScrollPosFromState()
        getHtml().addClass("wui3")

        rootComp = when (Auth.activeRole) {
            Role.STUDENT -> StudentCourseExercisesComp(courseId).also {
                it.createAndBuild().then { scrollPosition?.restore() }
            }

            Role.TEACHER, Role.ADMIN -> TeacherCourseExercisesComp(courseId).also {
                it.createAndBuild().then { scrollPosition?.restore() }
            }
        }
    }

    override fun onPreNavigation() {
        updateStateWithScrollPos()
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
        rootComp = null
        getHtml().removeClass("wui3")
    }

    fun link(courseId: String): String = constructPathLink(mapOf("courseId" to courseId))
}