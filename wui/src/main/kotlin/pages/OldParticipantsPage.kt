package pages

import AppProperties
import DateSerializer
import PageName
import PaginationConf
import Role
import cache.BasicCourseInfo
import debug
import getContainer
import getLastPageOffset
import isNotNullAndTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.dom.clear
import kotlinx.serialization.Serializable
import libheaders.Materialize
import onSingleClickWithDisabled
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLTextAreaElement
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.*
import rip.kspar.ezspa.*
import successMessage
import tmRender
import kotlin.js.Date
import kotlin.math.min

object OldParticipantsPage : EasyPage() {

    private const val PAGE_STEP = AppProperties.PARTICIPANTS_ROWS_ON_PAGE

    @Serializable
    data class Participants(
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
            val moodle_username: String,
            val email: String,
            val groups: List<Group>
    )

    @Serializable
    data class Group(
            val id: String,
            val name: String
    )

    @Serializable
    data class MoodleStatus(
        val moodle_props: MoodleProps?
    )

    @Serializable
    data class MoodleProps(
        val moodle_short_name: String,
        val students_synced: Boolean,
        val grades_synced: Boolean,
        val sync_students_in_progress: Boolean,
        val sync_grades_in_progress: Boolean,
    )

    @Serializable
    data class MoodleSyncedStatus(
        val status: MoodleSyncStatus,
    )

    enum class MoodleSyncStatus { FINISHED, IN_PROGRESS }


    override val pageName: Any
        get() = PageName.PARTICIPANTS

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_PARTICIPANTS_OLD)

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override val pathSchema = "/courses/{courseId}/participants-old"

    private val courseId: String
        get() = parsePathParams()["courseId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        buildParticipants(courseId)
    }

    data class PendingStudentRow(val moodleUsername: String?, val email: String?, val groups: String)
    data class ActiveStudentRow(val givenName: String, val familyName: String, val username: String, val moodleUsername: String?, val email: String, val groups: String)
    data class TeacherRow(val givenName: String, val familyName: String, val username: String, val email: String, val groups: String)

    private fun buildParticipants(courseId: String) {
        MainScope().launch {
            val participantsPromise = fetchEms("/courses/$courseId/participants", ReqMethod.GET,
                    successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage)
            val moodleStatusPromise = fetchEms(
                "/courses/$courseId/moodle", ReqMethod.GET,
                successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
            )
            val courseTitle = BasicCourseInfo.get(courseId).await().title

            val participants = participantsPromise.await()
                    .parseTo(Participants.serializer()).await()
            val moodleStatus = moodleStatusPromise.await()
                .parseTo(MoodleStatus.serializer()).await()

            val isMoodleSynced = moodleStatus.moodle_props?.moodle_short_name != null
            val studentsSynced = moodleStatus.moodle_props?.students_synced ?: false
            val gradesSynced = moodleStatus.moodle_props?.grades_synced ?: false

            val normalPendingRows = participants.students_pending.map { s ->
                PendingStudentRow(null, s.email, s.groups.joinToString { it.name })
            }.sortedWith(compareBy(PendingStudentRow::groups, PendingStudentRow::email))
            val moodlePendingRows = participants.students_moodle_pending.map { s ->
                PendingStudentRow(s.moodle_username, s.email, s.groups.joinToString { it.name })
            }.sortedWith(compareBy(PendingStudentRow::groups, PendingStudentRow::email))

            val pendingRows = normalPendingRows + moodlePendingRows

            val activeRows = participants.students.map { s ->
                ActiveStudentRow(s.given_name, s.family_name, s.id, s.moodle_username, s.email, s.groups.joinToString { it.name })
            }.sortedWith(compareBy(ActiveStudentRow::groups, ActiveStudentRow::familyName, ActiveStudentRow::givenName))

            val teacherRows = participants.teachers.map { t ->
                TeacherRow(t.given_name, t.family_name, t.id, t.email, t.groups.joinToString { it.name })
            }.sortedWith(compareBy(TeacherRow::groups, TeacherRow::familyName, TeacherRow::givenName))

            paintParticipants(courseId, courseTitle, isMoodleSynced, moodleStatus.moodle_props?.moodle_short_name, studentsSynced, gradesSynced,
                    teacherRows, activeRows, 0, pendingRows, 0)
        }
    }

    private fun paintParticipants(courseId: String, courseTitle: String, isMoodleSynced: Boolean, moodleShortName: String?,
                                  studentsSynced: Boolean, gradesSynced: Boolean, teacherRows: List<TeacherRow>,
                                  studentRows: List<ActiveStudentRow>, studentShowFrom: Int,
                                  pendingRows: List<PendingStudentRow>, pendingShowFrom: Int) {

        val teachers = teacherRows.mapIndexed { i, t ->
            mapOf(
                    "number" to (i + 1),
                    "name" to "${t.givenName} ${t.familyName}",
                    "username" to t.username,
                    "email" to t.email,
                    "group" to t.groups
            )
        }

        val studentRowTotal = studentRows.count()
        val studentShowTo = min(studentShowFrom + PAGE_STEP, studentRowTotal)
        val studentPaginationConf = if (studentRowTotal > PAGE_STEP) {
            PaginationConf(studentShowFrom + 1, studentShowTo, studentRowTotal,
                    studentShowFrom != 0, studentShowFrom + PAGE_STEP < studentRowTotal)
        } else null
        val students = studentRows.subList(studentShowFrom, studentShowTo)
                .mapIndexed { i, s ->
                    mapOf(
                            "number" to (studentShowFrom + i + 1),
                            "name" to "${s.givenName} ${s.familyName}",
                            "username" to s.username,
                            "moodleUsername" to s.moodleUsername,
                            "email" to s.email,
                            "group" to s.groups
                    )
                }

        val pendingRowTotal = pendingRows.count()
        val pendingShowTo = min(pendingShowFrom + PAGE_STEP, pendingRowTotal)
        val pendingPaginationConf = if (pendingRowTotal > PAGE_STEP) {
            PaginationConf(pendingShowFrom + 1, pendingShowTo, pendingRowTotal,
                    pendingShowFrom != 0, pendingShowFrom + PAGE_STEP < pendingRowTotal)
        } else null
        val pendingStudents = pendingRows.subList(pendingShowFrom, pendingShowTo)
                .mapIndexed { i, s ->
                    mapOf(
                            "number" to (pendingShowFrom + i + 1),
                            "moodleUsername" to s.moodleUsername,
                            "email" to s.email,
                            "group" to s.groups
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
                "moodleShortname" to moodleShortName,
                "syncStudentsLabel" to "Lae õpilased Moodle'ist",
                "moodleUsernameLabel" to "UT kasutajanimi",
                "moodlePendingTooltip" to "Selle UT kasutajanimega kasutajat ei eksisteeri. Kui selline kasutaja registreeritakse, siis lisatakse ta automaatselt siia kursusele.",
                "activeStudentsLabel" to "Aktiivsed",
                "pendingStudentsLabel" to "Ootel",
                "pageTotalLabel" to ", kokku ",
                "teachers" to teachers,
                "hasActiveStudents" to students.isNotEmpty(),
                "studentsPagination" to
                        studentPaginationConf?.let { mapOf("pageStart" to it.pageStart, "pageEnd" to it.pageEnd, "pageTotal" to it.pageTotal, "canGoBack" to it.canGoBack, "canGoForward" to it.canGoForward) },
                "students" to students,
                "hasPendingStudents" to pendingStudents.isNotEmpty(),
                "pendingStudentsPagination" to
                        pendingPaginationConf?.let { mapOf("pageStart" to it.pageStart, "pageEnd" to it.pageEnd, "pageTotal" to it.pageTotal, "canGoBack" to it.canGoBack, "canGoForward" to it.canGoForward) },
                "pendingStudents" to pendingStudents
        ))

        if (!studentsSynced) {
            getElemById("add-students-link").onVanillaClick(true) { toggleAddStudents(courseId) }
        }

        if (studentsSynced) {
            val syncBtn = getElemByIdAs<HTMLButtonElement>("sync-students-button")
            syncBtn.onSingleClickWithDisabled("Sünkroniseerin...") {

                debug { "Syncing students from moodle" }
                val moodleStudentsSyncStatus = fetchEms("/courses/$courseId/moodle/students", ReqMethod.POST,
                    successChecker = { http200 }).await()
                    .parseTo(MoodleSyncedStatus.serializer()).await().status

                if (moodleStudentsSyncStatus == MoodleSyncStatus.IN_PROGRESS) {
                    debug { "Sync already in progress" }
                    successMessage { "Sünkroniseerimine juba käib" }

                    while (true) {
                        sleep(3000).await()
                        debug { "Polling moodle students sync status" }

                        val moodleProps = fetchEms(
                            "/courses/$courseId/moodle", ReqMethod.GET,
                            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
                        ).await().parseTo(MoodleStatus.serializer()).await().moodle_props

                        if (moodleProps?.sync_students_in_progress != true) {
                            break
                        }
                    }
                }
                debug { "Sync completed" }
                successMessage { "Õpilased edukalt sünkroniseeritud" }
                build(null)
            }
        }

        if (studentPaginationConf?.canGoBack.isNotNullAndTrue) {
            getElemsByClass("student-go-first").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, 0, pendingRows, pendingShowFrom)
            }
            getElemsByClass("student-go-back").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, studentShowFrom - PAGE_STEP, pendingRows, pendingShowFrom)
            }
        }
        if (studentPaginationConf?.canGoForward.isNotNullAndTrue) {
            getElemsByClass("student-go-forward").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, studentShowFrom + PAGE_STEP, pendingRows, pendingShowFrom)
            }
            getElemsByClass("student-go-last").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, getLastPageOffset(studentRowTotal, PAGE_STEP), pendingRows, pendingShowFrom)
            }
        }

        if (pendingPaginationConf?.canGoBack.isNotNullAndTrue) {
            getElemsByClass("pending-go-first").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, studentShowFrom, pendingRows, 0)
            }
            getElemsByClass("pending-go-back").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, studentShowFrom, pendingRows, pendingShowFrom - PAGE_STEP)
            }
        }
        if (pendingPaginationConf?.canGoForward.isNotNullAndTrue) {
            getElemsByClass("pending-go-forward").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, studentShowFrom, pendingRows, pendingShowFrom + PAGE_STEP)
            }
            getElemsByClass("pending-go-last").onVanillaClick(true) {
                paintParticipants(courseId, courseTitle, isMoodleSynced, moodleShortName, studentsSynced, gradesSynced,
                        teacherRows, studentRows, studentShowFrom, pendingRows, getLastPageOffset(pendingRowTotal, PAGE_STEP))
            }
        }

        initTooltips()
    }

    private suspend fun postNewStudents(emails: List<String>, courseId: String) {
        debug { "Posting new students: $emails" }

        val newStudents = emails.map {
            mapOf("email" to it, "groups" to emptyList<Nothing>())
        }

        fetchEms("/courses/$courseId/students", ReqMethod.POST, mapOf(
                "students" to newStudents), successChecker = { http200 }).await()

        successMessage { "Õpilased edukalt lisatud" }
    }

    private fun toggleAddStudents(courseId: String) {
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

    private fun initTooltips() {
        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
    }

    fun link(courseId: String) = constructPathLink(mapOf("courseId" to courseId))
}