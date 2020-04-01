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
import kotlin.browser.window

object ExercisePage : EasyPage() {

    override val pageName: Any
        get() = PageName.EXERCISE

    override val allowedRoles: List<Role>
        get() = listOf(Role.ADMIN)

    override fun pathMatches(path: String): Boolean =
            path.matches("^/exercises/\\w+/details/?$")

    override fun build(pageStateStr: String?) {
        val exerciseId = extractSanitizedExerciseId(window.location.pathname)

        MainScope().launch {
            ExerciseRootComp(exerciseId,
                    getCurrentQueryParamValue("tab"),
                    { updateUrl(createQueryString("tab" to it)) }, CONTENT_CONTAINER_ID)
                    .createAndBuild().await()
        }
    }

    private fun extractSanitizedExerciseId(path: String): String {
        val match = path.match("^/exercises/(\\w+)/details/?$")
        if (match != null && match.size == 2) {
            return match[1]
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }

}