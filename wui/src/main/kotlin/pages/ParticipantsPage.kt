package pages

import DateSerializer
import PageName
import Role
import debug
import getContainer
import getElemById
import getElemByIdAs
import getElemByIdOrNull
import getNodelistBySelector
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.Materialize
import objOf
import onVanillaClick
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLTextAreaElement
import queries.*
import successMessage
import tmRender
import kotlin.browser.window
import kotlin.dom.clear
import kotlin.js.Date

object ParticipantsPage : EasyPage() {

    @Serializable
    data class Participants(
            val moodle_short_name: String? = null,
            val moodle_students_synced: Boolean? = null,
            val moodle_grades_synced: Boolean? = null,
            val student_count: Int? = null,
            val teacher_count: Int? = null,
            val students_pending_count: Int? = null,
            val students_moodle_pending_count: Int? = null,
            val students: List<Student> = emptyList(),
            val teachers: List<Teacher> = emptyList(),
            val students_pending: List<PendingStudent> = emptyList(),
            val students_moodle_pending: List<PendingMoodleStudent> = emptyList()
    )

    @Serializable
    data class Teacher(
            val id: String,
            val email: String,
            val given_name: String,
            val family_name: String,
            val groups: List<Group>,
            @Serializable(with = DateSerializer::class)
            val created_at: Date?
    )

    @Serializable
    data class Student(
            val id: String,
            val email: String,
            val given_name: String,
            val family_name: String,
            val groups: List<Group>,
            val moodle_username: String? = null,
            @Serializable(with = DateSerializer::class)
            val created_at: Date?
    )

    @Serializable
    data class PendingStudent(
            val email: String,
            @Serializable(with = DateSerializer::class)
            val valid_from: Date,
            val groups: List<Group>
    )

    @Serializable
    data class PendingMoodleStudent(
            val ut_username: String,
            val email: String,
            val groups: List<Group>
    )

    @Serializable
    data class Group(
            val id: String,
            val name: String
    )


    override val pageName: Any
        get() = PageName.PARTICIPANTS

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses/\\w+/participants/?$")

    override fun build(pageStateStr: String?) {

        suspend fun postNewStudents(emails: List<String>, courseId: String) {
            debug { "Posting new students: $emails" }

            val newStudents = emails.map {
                mapOf("email" to it, "groups" to emptyList<Nothing>())
            }

            fetchEms("/courses/$courseId/students", ReqMethod.POST, mapOf(
                    "students" to newStudents), successChecker = { http200 }).await()

            successMessage { "Õpilased edukalt lisatud" }
        }

        fun toggleAddStudents(courseId: String) {
            if (getElemByIdOrNull("add-students-wrap") == null) {
                // Box not visible
                debug { "Open add students box" }
                getElemById("add-students-section").innerHTML = tmRender("tm-teach-participants-add", mapOf(
                        "addStudentsHelp" to "Õpilaste lisamiseks sisesta kasutajate meiliaadressid eraldi ridadele või eraldatuna tühikutega. " +
                                "Kui sisestatud emaili aadressiga õpilast ei leidu, siis lisatakse õpilane kursusele kasutaja registreerimise hetkel.",
                        "addStudentsFieldLabel" to "Õpilaste meiliaadressid",
                        "addButtonLabel" to "Lisa"
                ))

                getElemById("add-students-button").onVanillaClick(true) {
                    MainScope().launch {
                        val emails = getElemByIdAs<HTMLTextAreaElement>("new-students-field").value
                                .split(" ", "\n")
                                .filter { it.isNotBlank() }

                        postNewStudents(emails, courseId)
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

        val courseId = extractSanitizedCourseId(window.location.pathname)

        MainScope().launch {
            val participantsPromise = fetchEms("/courses/$courseId/participants", ReqMethod.GET,
                    successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage)
            val courseTitle = BasicCourseInfo.get(courseId).await().title

            val participants = participantsPromise.await()
                    .parseTo(Participants.serializer()).await()

            val isMoodleSynced = participants.moodle_short_name != null
            val studentsSynced = participants.moodle_students_synced ?: false
            val gradesSynced = participants.moodle_grades_synced ?: false

            data class PendingStudentRow(val moodleUsername: String?, val email: String?, val groups: String)
            data class ActiveStudentRow(val givenName: String, val familyName: String, val username: String, val moodleUsername: String?, val email: String, val groups: String)
            data class TeacherRow(val givenName: String, val familyName: String, val username: String, val email: String, val groups: String)

            val normalPendingRows = participants.students_pending.map {s ->
                PendingStudentRow( null, s.email, s.groups.joinToString { it.name })
            }.sortedWith(compareBy(PendingStudentRow::groups, PendingStudentRow::email))

            val moodlePendingRows = participants.students_moodle_pending.map { s ->
                PendingStudentRow(s.ut_username, s.email, s.groups.joinToString { it.name })
            }.sortedWith(compareBy(PendingStudentRow::groups, PendingStudentRow::email))

            val pendingRows = normalPendingRows + moodlePendingRows

            val activeRows = participants.students.map { s ->
                ActiveStudentRow(s.given_name, s.family_name, s.id, s.moodle_username, s.email, s.groups.joinToString { it.name })
            }.sortedWith(compareBy(ActiveStudentRow::groups, ActiveStudentRow::familyName, ActiveStudentRow::givenName))

            val teacherRows = participants.teachers.map { t ->
                TeacherRow(t.given_name, t.family_name, t.id, t.email, t.groups.joinToString { it.name })
            }.sortedWith(compareBy(TeacherRow::groups, TeacherRow::familyName, TeacherRow::givenName))

            val pendingStudents = pendingRows.mapIndexed { i, s ->
                mapOf(
                        "number" to (i+1),
                        "moodleUsername" to s.moodleUsername,
                        "email" to s.email,
                        "group" to s.groups
                )
            }

            val students = activeRows.mapIndexed {i, s ->
                mapOf(
                        "number" to (i+1),
                        "name" to "${s.givenName} ${s.familyName}",
                        "username" to s.username,
                        "moodleUsername" to s.moodleUsername,
                        "email" to s.email,
                        "group" to s.groups
                )
            }

            val teachers = teacherRows.mapIndexed {i, t ->
                mapOf(
                        "number" to (i+1),
                        "name" to "${t.givenName} ${t.familyName}",
                        "username" to t.username,
                        "email" to t.email,
                        "group" to t.groups
                )
            }

            getContainer().innerHTML = tmRender("tm-teach-participants", mapOf(
                    "myCoursesLabel" to "Minu kursused",
                    "title" to courseTitle,
                    "courseHref" to "/courses/$courseId/exercises",
                    "participantsLabel" to "Osalejad",
                    "teachersLabel" to "Õpetajad",
                    "numberLabel" to "Jrk",
                    "nameLabel" to "Nimi",
                    "usernameLabel" to "Kasutajanimi",
                    "emailLabel" to "Email",
                    "groupLabel" to "Rühmad",
                    "teacherGroupLabel" to "Piiratud rühmad",
                    "pendingTooltip" to "Selle meiliaadressiga kasutajat ei eksisteeri. Kui selline kasutaja registreeritakse, siis lisatakse ta automaatselt siia kursusele.",
                    "studentsLabel" to "Õpilased",
                    "addStudentsLink" to "&#9658; Lisa õpilasi",
                    "isMoodleSynced" to isMoodleSynced,
                    "studentsSynced" to studentsSynced,
                    "moodleShortnameLabel" to "Moodle'i kursuse lühinimi",
                    "moodleShortname" to participants.moodle_short_name,
                    "syncStudentsLabel" to "Lae õpilased Moodle'ist",
                    "moodleUsernameLabel" to "UT kasutajanimi",
                    "moodlePendingTooltip" to "Selle UT kasutajanimega kasutajat ei eksisteeri. Kui selline kasutaja registreeritakse, siis lisatakse ta automaatselt siia kursusele.",
                    "activeStudentsLabel" to "Aktiivsed",
                    "pendingStudentsLabel" to "Ootel",
                    "teachers" to teachers,
                    "hasActiveStudents" to students.isNotEmpty(),
                    "students" to students,
                    "hasPendingStudents" to pendingStudents.isNotEmpty(),
                    "pendingStudents" to pendingStudents
            ))

            if (!studentsSynced) {
                getElemById("add-students-link").onVanillaClick(true) { toggleAddStudents(courseId) }
            }

            if (studentsSynced) {
                val syncBtn = getElemByIdAs<HTMLButtonElement>("sync-students-button")
                syncBtn.onVanillaClick(true) {
                    MainScope().launch {
                        syncBtn.disabled = true
                        fetchEms("/courses/$courseId/moodle", ReqMethod.POST,
                                mapOf(
                                        "moodle_short_name" to participants.moodle_short_name,
                                        "sync_students" to studentsSynced,
                                        "sync_grades" to gradesSynced
                                ), successChecker = { http200 }).await()
                        successMessage { "Õpilased edukalt sünkroniseeritud" }
                        build(null)
                    }
                }
            }

            initTooltips()
        }
    }


    private fun initTooltips() {
        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
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