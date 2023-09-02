package pages.participants

import DateSerializer
import Icons
import cache.BasicCourseInfo
import components.PageTabsComp
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.Title
import pages.sidenav.Sidenav
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import tmRender
import translation.Str
import kotlin.js.Date

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

    private val tabStudentsId = IdGenerator.nextId()
    private val tabTeachersId = IdGenerator.nextId()
    private val tabGroupsId = IdGenerator.nextId()
    private val tabMoodleId = IdGenerator.nextId()

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
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        )
        val groupsPromise = fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        )
        val moodleStatusPromise = fetchEms(
            "/courses/$courseId/moodle", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        )

        courseTitle = courseTitlePromise.await().effectiveTitle

        Title.update {
            it.pageTitle = Str.participants
            it.parentPageTitle = courseTitle
        }

        val participants = participantsPromise.await().parseTo(Participants.serializer()).await()
        val groupsResp = groupsPromise.await().parseTo(Groups.serializer()).await()
        val moodleStatus = moodleStatusPromise.await().parseTo(MoodleStatus.serializer()).await()

        val groups = groupsResp.groups.sortedBy { it.name }
        val hasRestrictedGroups = groupsResp.self_is_restricted
        val isMoodleLinked = moodleStatus.moodle_props != null
        val studentsSynced = moodleStatus.moodle_props?.students_synced ?: false
        val gradesSynced = moodleStatus.moodle_props?.grades_synced ?: false

        addStudentsModal = AddStudentsModalComp(courseId, groups, this)
        addTeachersModal = AddTeachersModalComp(courseId, groups, this)
        createGroupModal = CreateGroupModalComp(courseId, groups, this)

        // for load testing
//        val multipliedStudentsForTesting = participants.students.flatMap { a -> List(1) { a } }


        tabsComp = PageTabsComp(
            tabs = buildList {
                add(
                    PageTabsComp.Tab("Õpilased", preselected = true, id = tabStudentsId) {
                        ParticipantsStudentsListComp(
                            courseId,
                            participants.students,
                            participants.students_pending,
                            participants.students_moodle_pending,
                            groups,
                            !studentsSynced,
                            {
                                val t = tabsComp.getSelectedTab()
                                createAndBuild().await()
                                tabsComp.setSelectedTab(t)
                            },
                            it
                        )
                    }
                )
                add(
                    PageTabsComp.Tab("Õpetajad", id = tabTeachersId) {
                        ParticipantsTeachersListComp(
                            courseId,
                            participants.teachers,
                            groups,
                            !hasRestrictedGroups,
                            {
                                val t = tabsComp.getSelectedTab()
                                createAndBuild().await()
                                tabsComp.setSelectedTab(t)
                            },
                            it
                        )
                    }
                )

                if (groups.isNotEmpty()) add(
                    PageTabsComp.Tab("Rühmad", id = tabGroupsId) {
                        ParticipantsGroupsListComp(
                            courseId,
                            groups,
                            participants.students,
                            participants.students_pending,
                            participants.students_moodle_pending,
                            participants.teachers,
                            !hasRestrictedGroups && !studentsSynced,
                            {
                                val t = tabsComp.getSelectedTab()
                                createAndBuild().await()
                                tabsComp.setSelectedTab(t)
                            },
                            it
                        )
                    }
                )

                if (moodleStatus.moodle_props != null) add(
                    PageTabsComp.Tab("Moodle", id = tabMoodleId) {
                        ParticipantsMoodleTabComp(
                            courseId,
                            moodleStatus.moodle_props,
                            {
                                val t = tabsComp.getSelectedTab()
                                createAndBuild().await()
                                tabsComp.setSelectedTab(t)
                            },
                            it
                        )
                    }
                )
            },
            parent = this
        )


        // Create sidenav actions
        val sideActions = buildList {
            if (!studentsSynced) add(
                Sidenav.Action(Icons.addPerson, "Lisa õpilasi") {
                    if (addStudentsModal.openWithClosePromise().await()) {
                        val t = tabsComp.getSelectedTab()
                        createAndBuild().await()
                        tabsComp.setSelectedTab(t)
                    }
                }
            )
            if (!hasRestrictedGroups) add(
                Sidenav.Action(Icons.addPerson, "Lisa õpetajaid") {
                    if (addTeachersModal.openWithClosePromise().await()) {
                        val t = tabsComp.getSelectedTab()
                        createAndBuild().await()
                        tabsComp.setSelectedTab(t)
                    }
                }
            )
            if (!hasRestrictedGroups && !studentsSynced) add(
                Sidenav.Action(Icons.createCourseGroup, "Loo uus rühm") {
                    if (createGroupModal.openWithClosePromise().await()) {
                        val t = tabsComp.getSelectedTab()
                        createAndBuild().await()
                        tabsComp.setSelectedTab(t)
                    }
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