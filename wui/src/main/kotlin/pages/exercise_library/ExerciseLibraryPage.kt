package pages.exercise_library

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import pages.Title
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import restore
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml
import translation.Str

object ExerciseLibraryPage : EasyPage() {

    private var rootComp: ExerciseLibComp? = null

    override val pageName: Any
        get() = PageName.EXERCISE_LIBRARY

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN, Role.TEACHER)

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(activePage = ActivePage.LIBRARY)

    override val titleSpec: Title.Spec
        get() = Title.Spec(Str.exerciseLibrary)

    // root - /library/dir/root
    // dir - /library/dir/{dirId}/parent1/parent2/dir
    override val pathSchema = "/library/dir/{dirId}/**"

    private fun getDirId() = parsePathParams()["dirId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val scrollPosition = pageStateStr.getScrollPosFromState()
        getHtml().addClass("wui3")

        val dirId = getDirId().let { if (it == "root") null else it }

        doInPromise {
            rootComp = ExerciseLibComp(dirId, ::setWildcardPath, CONTENT_CONTAINER_ID)
                .also { it.createAndBuild().await() }
            scrollPosition?.restore()
        }
    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
        rootComp?.destroy()
    }

    override fun onPreNavigation() {
        updateStateWithScrollPos()
    }

    fun linkToRoot() = linkToDir("root")

    fun linkToDir(dirId: String) = constructPathLink(mapOf("dirId" to dirId))

    private fun setWildcardPath(wildcardPathSuffix: String) {
        updateUrl(linkToDir(getDirId()) + wildcardPathSuffix)
    }
}