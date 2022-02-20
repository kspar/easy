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

    override val pathSchema = "/library"

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        doInPromise {
            ExerciseLibRootComp(CONTENT_CONTAINER_ID)
                .createAndBuild().await()
        }
    }

    fun link() = constructPathLink(emptyMap())
}