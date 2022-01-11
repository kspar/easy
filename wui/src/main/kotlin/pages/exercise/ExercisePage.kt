package pages.exercise

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import pages.EasyPage
import queries.createQueryString
import queries.getCurrentQueryParamValue

object ExercisePage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN)

    override val pathSchema = "/exerciselib/{exerciseId}/details"

    private val exerciseId: String
        get() = parsePathParams()["exerciseId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        MainScope().launch {
            ExerciseRootComp(
                exerciseId,
                getCurrentQueryParamValue("tab"),
                { updateUrl(createQueryString("tab" to it)) },
                CONTENT_CONTAINER_ID
            ).createAndBuild().await()
        }
    }

    fun link(exerciseId: String): String = constructPathLink(mapOf("exerciseId" to exerciseId))
}