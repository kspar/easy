package components

import Str
import errorMessage
import getContainer
import kotlinx.serialization.Serializable
import tmRender

object ParticipantsPage : EasyPage() {
    override val pageName: Any
        get() = PageName.PARTICIPANTS

    override fun pathMatches(path: String): Boolean =
        path.matches("^/courses/\\w+/participants/?$")

    override fun build(pageStateStr: String?) {
        if (Auth.activeRole != Role.ADMIN && Auth.activeRole != Role.TEACHER){
            errorMessage { Str.noPermissionForPage() }
            error("User is not admin nor teacher")
        }

        getContainer().innerHTML = tmRender("tm-teach-participants", mapOf())

    }

    @Serializable
    data class AdminCourse(val id: String)

}