package pages.exercise_library

import CONTENT_CONTAINER_ID
import PageName
import Role
import Str
import kotlinx.coroutines.await
import pages.EasyPage
import pages.Title
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import rip.kspar.ezspa.doInPromise

object ExerciseLibraryPage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE_LIBRARY

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN, Role.TEACHER)

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(activePage = ActivePage.LIBRARY)

    override val titleSpec: Title.Spec
        get() = Title.Spec(Str.exerciseLibrary())

    // root - /library/dir/root
    // dir - /library/dir/{dirId}/parent1/parent2/dir
    override val pathSchema = "/library/dir/{dirId}/**"

    private fun getDirId() = parsePathParams()["dirId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        val dirId = getDirId().let { if (it == "root") null else it }

        doInPromise {
            ExerciseLibRootComp(dirId, CONTENT_CONTAINER_ID)
                .createAndBuild().await()
        }
    }

    fun linkToRoot() = linkToDir("root")

    fun linkToDir(dirId: String) = constructPathLink(mapOf("dirId" to dirId))
}