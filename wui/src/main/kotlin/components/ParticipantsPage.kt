package components

import Str
import debug
import errorMessage
import fetchEms
import getContainer
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import objOf
import parseTo
import tmRender
import toJsObj
import kotlin.browser.window

object ParticipantsPage : EasyPage() {

    @Serializable
    data class Participants(
            val students: List<Participant>,
            val teachers: List<Participant>
    )

    @Serializable
    data class Participant(
            val id: String,
            val email: String,
            val given_name: String,
            val family_name: String
    )

    override val pageName: Any
        get() = PageName.PARTICIPANTS

    override fun pathMatches(path: String): Boolean =
        path.matches("^/courses/\\w+/participants/?$")

    override fun build(pageStateStr: String?) {
        if (Auth.activeRole != Role.ADMIN && Auth.activeRole != Role.TEACHER){
            errorMessage { Str.noPermissionForPage() }
            error("User is not admin nor teacher")
        }

        val courseId = extractSanitizedCourseId(window.location.pathname)
        debug { "Course ID: $courseId" }

        MainScope().launch {
            val resp = fetchEms("/courses/$courseId/participants", ReqMethod.GET).await()
            if (!resp.http200){
                errorMessage { Str.somethingWentWrong() }
                error("Fetching participants failed with status ${resp.status}")
            }

            val participants = resp.parseTo(Participants.serializer()).await()
            debug { "$participants" }

            val students = participants.students.map {
                objOf(
                        "name" to "${it.given_name} ${it.family_name}",
                        "username" to it.id,
                        "email" to it.email
                )
            }.toTypedArray()

            val teachers = participants.teachers.map {
                objOf(
                        "name" to "${it.given_name} ${it.family_name}",
                        "username" to it.id,
                        "email" to it.email
                )
            }.toTypedArray()

            getContainer().innerHTML = tmRender("tm-teach-participants", mapOf(
                    "myCoursesLabel" to "Minu kursused",
                    "title" to "Programmeerimine",
                    "courseHref" to "#",
                    "participantsLabel" to "Osalejad",
                    "teacherLabel" to "Õpetajad",
                    "nameLabel" to "Nimi",
                    "usernameLabel" to "Kasutajanimi",
                    "emailLabel" to "Email",
                    "studentsLabel" to "Õpilased",
                    "addStudentsLink" to "Lisa õpilasi",
                    "addStudentsHelp" to "Õpilaste lisamiseks sisesta kasutajate kasutajanimed või meiliaadressid eraldi ridadele või eraldatuna tühikutega.",
                    "addStudentsFieldLabel" to "Õpilaste nimekiri",
                    "addButtonLabel" to "Lisa",
                    "students" to students,
                    "teachers" to teachers
            ))
        }


    }
    private fun extractSanitizedCourseId(path: String): String {
        val match = path.match("^/courses/(\\w+)/participants/?$")
        if (match != null && match.size == 2) {
            return match[1]
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }


}