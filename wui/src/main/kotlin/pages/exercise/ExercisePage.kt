package pages.exercise

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import pages.EasyPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.createQueryString
import queries.getCurrentQueryParamValue

object ExercisePage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN)

    // /library/exercise/{exerciseId}/dir1-name/dir2-name/ex-name
    override val pathSchema = "/library/exercise/{exerciseId}/**"

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(activePage = ActivePage.LIBRARY)

    private val exerciseId: String
        get() = parsePathParams()["exerciseId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        MainScope().launch {
            ExerciseRootComp(
                exerciseId,
                getCurrentQueryParamValue("tab"),
                ::setWildcardPath,
                { updateUrl(createQueryString("tab" to it)) },
                CONTENT_CONTAINER_ID
            ).createAndBuild().await()
        }
    }

    fun link(exerciseId: String): String = constructPathLink(mapOf("exerciseId" to exerciseId))

    private fun setWildcardPath(wildcardPathSuffix: String) {
        updateUrl(link(exerciseId) + wildcardPathSuffix)
    }
}