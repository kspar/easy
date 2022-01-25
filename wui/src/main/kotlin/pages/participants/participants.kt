package pages.participants

import DateSerializer
import Icons
import cache.BasicCourseInfo
import components.BreadcrumbsComp
import components.Crumb
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.sidenav.Sidenav
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import tmRender
import kotlin.js.Date

// buildList is experimental
@ExperimentalStdlibApi
class ParticipantsRootComp(
    private val courseId: String,
    dstId: String
) : Component(null, dstId) {

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

    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var teachersList: ParticipantsTeachersListComp
    private lateinit var studentsList: ParticipantsStudentsListComp
    private lateinit var addStudentsModal: AddStudentsModalComp
    private lateinit var courseTitle: String

    override val children: List<Component>
        get() = listOf(breadcrumbs, teachersList, studentsList, addStudentsModal)

    override fun create() = doInPromise {
        val courseTitlePromise = BasicCourseInfo.get(courseId)
        val participantsPromise = fetchEms(
            "/courses/$courseId/participants", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        )
        val moodleStatusPromise = fetchEms(
            "/courses/$courseId/moodle", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        )

        addStudentsModal = AddStudentsModalComp(courseId, this)

        courseTitle = courseTitlePromise.await().title
        breadcrumbs = BreadcrumbsComp(
            listOf(
                Crumb.myCourses,
                Crumb.courseExercises(courseId, courseTitle),
                Crumb("Osalejad")
            ), this
        )

        val participants = participantsPromise.await()
            .parseTo(Participants.serializer()).await()

        val moodleStatus = moodleStatusPromise.await()
            .parseTo(MoodleStatus.serializer()).await()

        val isMoodleSynced = moodleStatus.moodle_props?.moodle_short_name != null
        val studentsSynced = moodleStatus.moodle_props?.students_synced ?: false
        val gradesSynced = moodleStatus.moodle_props?.grades_synced ?: false

        // TODO: remove
//        val multipliedStudentsForTesting = participants.students.flatMap { a -> List(5) { a } }

        teachersList = ParticipantsTeachersListComp(courseId, this)
        studentsList = ParticipantsStudentsListComp(
            participants.students,
            participants.students_pending,
            participants.students_moodle_pending,
            !studentsSynced,
            this
        )

        if (!studentsSynced) {
            Sidenav.replacePageSection(
                Sidenav.PageSection(
                    "Osalejad",
                    listOf(Sidenav.Action(Icons.addToGroup, "Lisa õpilasi") {
                        if (addStudentsModal.openWithClosePromise().await())
                            createAndBuild()
                    })
                )
            )
        }
    }

    override fun render() = tmRender(
        "t-c-participants",
        "breadcrumbsId" to breadcrumbs.dstId,
        "teachersListId" to teachersList.dstId,
        "studentsListId" to studentsList.dstId,
        "addStudentsModalId" to addStudentsModal.dstId,
        "title" to courseTitle,
        "teachersLabel" to "Õpetajad",
        "studentsLabel" to "Õpilased",
    )
}