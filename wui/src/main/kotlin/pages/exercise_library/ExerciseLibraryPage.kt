package pages.exercise_library

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.await
import pages.EasyPage
import rip.kspar.ezspa.doInPromise

object ExerciseLibraryPage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE_LIBRARY

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN, Role.TEACHER)

    // TODO: /library?
    override val pathSchema = "/exerciselib"

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        doInPromise {
            ExerciseLibRootComp(CONTENT_CONTAINER_ID)
                .createAndBuild().await()
        }
    }
}