package pages.exercise

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Navigation
import rip.kspar.ezspa.getHtml

object ExercisePage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN, Role.TEACHER)

    // /library/exercise/{exerciseId}/dir1-name/dir2-name/ex-name
    override val pathSchema = "/library/exercise/{exerciseId}/**"

    private val exerciseId: String
        get() = parsePathParams()["exerciseId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        getHtml().addClass("wui3")

        MainScope().launch {
            val root = ExerciseRootComp(
                exerciseId,
                ::setWildcardPath,
                CONTENT_CONTAINER_ID
            )
            root.createAndBuild().await()

            Navigation.catchNavigation {
                root.hasUnsavedChanges()
            }
        }
    }

    override fun destruct() {
        super.destruct()
        Navigation.stopNavigationCatching()
        getHtml().removeClass("wui3")
    }

    fun link(exerciseId: String): String = constructPathLink(mapOf("exerciseId" to exerciseId))

    private fun setWildcardPath(wildcardPathSuffix: String) {
        updateUrl(link(exerciseId) + wildcardPathSuffix)
    }
}