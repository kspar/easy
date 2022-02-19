package pages.participants

import DateSerializer
import Icons
import Str
import cache.BasicCourseInfo
import components.PageTabsComp
import components.StringComp
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.Title
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
    private val isAdmin: Boolean,
    dstId: String,
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
    data class Groups(
        val groups: List<Group>,
        val self_is_restricted: Boolean,
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

    private lateinit var tabsComp: PageTabsComp
    private lateinit var addStudentsModal: AddStudentsModalComp
    private lateinit var addTeachersModal: AddTeachersModalComp
    private lateinit var createGroupModal: CreateGroupModalComp

    private lateinit var courseTitle: String

    override val children: List<Component>
        get() = listOf(tabsComp, addStudentsModal, addTeachersModal, createGroupModal)

    override fun create() = doInPromise {
        val courseTitlePromise = BasicCourseInfo.get(courseId)
        val participantsPromise = fetchEms(
            "/courses/$courseId/participants", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        )
        val groupsPromise = fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        )
        val moodleStatusPromise = fetchEms(
            "/courses/$courseId/moodle", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage
        )

        courseTitle = courseTitlePromise.await().title

        Title.update {
            it.pageTitle = Str.participants()
            it.parentPageTitle = courseTitle
        }

        val participants = participantsPromise.await().parseTo(Participants.serializer()).await()
        val groups = groupsPromise.await().parseTo(Groups.serializer()).await()
        val moodleStatus = moodleStatusPromise.await().parseTo(MoodleStatus.serializer()).await()

        val isMoodleLinked = moodleStatus.moodle_props?.moodle_short_name != null
        val studentsSynced = moodleStatus.moodle_props?.students_synced ?: false
        val gradesSynced = moodleStatus.moodle_props?.grades_synced ?: false

        addStudentsModal = AddStudentsModalComp(courseId, groups.groups, this)
        addTeachersModal = AddTeachersModalComp(courseId, groups.groups, this)
        createGroupModal = CreateGroupModalComp(courseId, groups.groups, this)

        // TODO: remove
        val multipliedStudentsForTesting = participants.students.flatMap { a -> List(1) { a } }


        tabsComp = PageTabsComp(
            buildList {
                add(
                    PageTabsComp.Tab("Õpilased", preselected = true) {
                        ParticipantsStudentsListComp(
                            multipliedStudentsForTesting,
                            participants.students_pending,
                            participants.students_moodle_pending,
                            groups.groups,
                            !studentsSynced,
                            it
                        )
                    }
                )
                add(
                    PageTabsComp.Tab("Õpetajad") {
                        ParticipantsTeachersListComp(
                            courseId,
                            participants.teachers,
                            groups.groups,
                            !groups.self_is_restricted,
                            it
                        )
                    }
                )

                if (groups.groups.isNotEmpty()) add(
                    PageTabsComp.Tab("Rühmad") {
                        ParticipantsGroupsListComp(
                            courseId,
                            groups.groups,
                            participants.students,
                            participants.students_pending,
                            participants.students_moodle_pending,
                            participants.teachers,
                            !groups.self_is_restricted && !studentsSynced,
                            { createAndBuild().await() },
                            it
                        )
                    }
                )

                if (isMoodleLinked) add(
                    PageTabsComp.Tab("Moodle") {
                        StringComp("Moodle", it)
                    }
                )
            },
            this
        )


        // Create sidenav actions
        val sideActions = buildList {
            if (!studentsSynced) add(
                Sidenav.Action(Icons.addParticipant, "Lisa õpilasi") {
                    if (addStudentsModal.openWithClosePromise().await())
                        createAndBuild().await()
                }
            )
            if (!groups.self_is_restricted) add(
                Sidenav.Action(Icons.addParticipant, "Lisa õpetajaid") {
                    if (addTeachersModal.openWithClosePromise().await())
                        createAndBuild().await()
                }
            )
            if (!groups.self_is_restricted && !studentsSynced) add(
                Sidenav.Action(Icons.createCourseGroup, "Loo uus rühm") {
                    if (createGroupModal.openWithClosePromise().await())
                        createAndBuild().await()
                }
            )
            if (isAdmin && !isMoodleLinked) add(
                Sidenav.Action(Icons.moodle, "Seo UT Moodle kursusega") {
                    // TODO
                }
            )
        }

        if (sideActions.isNotEmpty()) {
            Sidenav.replacePageSection(
                Sidenav.PageSection("Osalejad", sideActions)
            )
        }
    }

    override fun render() = tmRender(
        "t-c-participants",
        "tabsId" to tabsComp.dstId,
        "addStudentsModalId" to addStudentsModal.dstId,
        "addTeachersModalId" to addTeachersModal.dstId,
        "createGroupModalId" to createGroupModal.dstId,
        "title" to courseTitle,
    )

    override fun renderLoading() = tmRender("tm-loading-placeholders", "marginTopRem" to 6, "titleWidthRem" to 30)

}