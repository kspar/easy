package pages.exercise_library

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import pages.EasyPage

object ExerciseLibraryPage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE_LIBRARY

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN, Role.TEACHER)

    override fun pathMatches(path: String): Boolean =
            path.matches("^/exerciselib/?$")

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        MainScope().launch {
            ExerciseLibRootComp(CONTENT_CONTAINER_ID)
                    .createAndBuild().await()
        }
    }
}