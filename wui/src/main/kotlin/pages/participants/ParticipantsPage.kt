package pages.participants

import CONTENT_CONTAINER_ID
import Icons
import PageName
import Role
import debug
import kotlinx.coroutines.await
import pages.EasyPage
import pages.courses.CoursesPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import rip.kspar.ezspa.doInPromise


object ParticipantsPage : EasyPage() {

    override val pageName = PageName.PARTICIPANTS

    override val allowedRoles = listOf(Role.ADMIN, Role.TEACHER)

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(
            courseId, ActivePage.COURSE_PARTICIPANTS, Sidenav.PageSection(
                "Osalejad",
                listOf(
                    Sidenav.Link(Icons.robot, "Mine kuhugi", CoursesPage.link()),
                    Sidenav.Action(Icons.removeParticipant, "Tee midagi", { debug { "Tee midagi" } }),
                )
            )
        )

    override val pathSchema = "/courses/{courseId}/participants"

    private val courseId: String
        get() = parsePathParams()["courseId"]


    @OptIn(ExperimentalStdlibApi::class)
    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        doInPromise {
            ParticipantsRootComp(courseId, CONTENT_CONTAINER_ID).createAndBuild().await()
        }
    }

    fun link(courseId: String) = constructPathLink(mapOf("courseId" to courseId))
}