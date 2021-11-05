package pages.participants

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.await
import pages.EasyPage
import pages.leftbar.Leftbar
import rip.kspar.ezspa.doInPromise

// buildList is experimental
@ExperimentalStdlibApi
object ParticipantsPage : EasyPage() {

    override val pageName = PageName.PARTICIPANTS

    override val allowedRoles = listOf(Role.ADMIN, Role.TEACHER)

    override val leftbarConf: Leftbar.Conf
        get() = Leftbar.Conf(courseId)

    override val pathSchema = "/courses/{courseId}/participants"

    private val courseId: String
        get() = parsePathParams()["courseId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        doInPromise {
            ParticipantsRootComp(courseId, CONTENT_CONTAINER_ID).createAndBuild().await()
        }
    }
}