package components

import Auth
import PageName
import ReqMethod
import Role
import Str
import debug
import errorMessage
import fetchEms
import getContainer
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import http200
import http400
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import objOf
import onVanillaClick
import org.w3c.dom.HTMLTextAreaElement
import parseTo
import queries.BasicCourseInfo
import tmRender
import kotlin.browser.window
import kotlin.dom.clear

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

    @Serializable
    data class NewStudent(
            val student_id_or_email: String
    )

    @Serializable
    data class NoStudentFound(
            val id: String,
            val code: String,
            val attrs: MissingStudent,
            val log_msg: String
    )

    @Serializable
    data class MissingStudent(
            val missing_student: String
    )

    override val pageName: Any
        get() = PageName.PARTICIPANTS

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/\\w+/participants/?$")

    override fun build(pageStateStr: String?) {

        suspend fun postNewStudents(studentIds: List<String>, courseId: String) {
            debug { "Posting new students: $studentIds" }

            val newStudents = studentIds.map {
                NewStudent(it)
            }

            val resp = fetchEms("/courses/$courseId/students", ReqMethod.POST, mapOf(
                    "students" to newStudents)).await()

            if (resp.http200)
                return

            if (resp.http400) {
                val noStudentBody = resp.parseTo(NoStudentFound.serializer()).await()
                if (noStudentBody.code == "STUDENT_NOT_FOUND") {
                    errorMessage { "Ei leidnud kasutajanime/emailiga õpilast: ${noStudentBody.attrs.missing_student}" }
                    error(noStudentBody.log_msg)
                }
            }

            errorMessage { Str.somethingWentWrong() }
            error("Adding students failed with status ${resp.status}")
        }

        fun toggleAddStudents(courseId: String) {
            if (getElemByIdOrNull("add-students-wrap") == null) {
                // Box not visible
                debug { "Open add students box" }
                getElemById("add-students-section").innerHTML = tmRender("tm-teach-participants-add", mapOf(
                        "addStudentsHelp" to "Õpilaste lisamiseks sisesta kasutajate kasutajanimed või meiliaadressid eraldi ridadele või eraldatuna tühikutega.",
                        "addStudentsFieldLabel" to "Õpilaste nimekiri",
                        "addButtonLabel" to "Lisa"
                ))

                getElemById("add-students-button").onVanillaClick(true) {
                    MainScope().launch {
                        val ids = getElemByIdAs<HTMLTextAreaElement>("new-students-field").value
                                .split(" ", "\n")
                                .filter { it.isNotBlank() }

                        postNewStudents(ids, courseId)
                        build(null)
                    }
                }

                getElemById("add-students-link").innerHTML = "&#9660; Sulge"

            } else {
                // Box is visible
                debug { "Close add students box" }
                getElemById("add-students-section").clear()
                getElemById("add-students-link").innerHTML = "&#9658; Lisa õpilasi"
            }
        }

        if (Auth.activeRole != Role.ADMIN && Auth.activeRole != Role.TEACHER) {
            errorMessage { Str.noPermissionForPage() }
            error("User is not admin nor teacher")
        }

        val courseId = extractSanitizedCourseId(window.location.pathname)
        debug { "Course ID: $courseId" }

        MainScope().launch {
            val participantsPromise = fetchEms("/courses/$courseId/participants", ReqMethod.GET)
            val courseInfoPromise = BasicCourseInfo.get(courseId)

            val resp = participantsPromise.await()
            val courseTitle = courseInfoPromise.await().title

            if (!resp.http200) {
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
                    "title" to courseTitle,
                    "courseHref" to "/courses/$courseId/exercises",
                    "participantsLabel" to "Osalejad",
                    "teachersLabel" to "Õpetajad",
                    "nameLabel" to "Nimi",
                    "usernameLabel" to "Kasutajanimi",
                    "emailLabel" to "Email",
                    "studentsLabel" to "Õpilased",
                    "addStudentsLink" to "&#9658; Lisa õpilasi",
                    "students" to students,
                    "teachers" to teachers
            ))

            getElemById("add-students-link").onVanillaClick(true) { toggleAddStudents(courseId) }
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