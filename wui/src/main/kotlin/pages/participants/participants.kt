package pages.participants

import Icons
import cache.BasicCourseInfo
import components.PageTabsComp
import dao.ParticipantsDAO
import kotlinx.coroutines.await
import pages.Title
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import tmRender
import translation.Str

class ParticipantsRootComp(
    private val courseId: String,
    private val isAdmin: Boolean,
    dstId: String,
) : Component(null, dstId) {

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
        val participantsPromise = ParticipantsDAO.getCourseParticipants(courseId)
        val groupsPromise = ParticipantsDAO.getCourseGroups(courseId)
        val moodleStatusPromise = ParticipantsDAO.getCourseMoodleSettings(courseId)

        courseTitle = courseTitlePromise.await().effectiveTitle

        Title.update {
            it.pageTitle = Str.participants
            it.parentPageTitle = courseTitle
        }

        val participants = participantsPromise.await()
        val groupsResp = groupsPromise.await()
        val moodleStatus = moodleStatusPromise.await()

        val groups = groupsResp.groups.sortedBy { it.name }
        val isMoodleLinked = moodleStatus.moodle_props != null
        val studentsSynced = moodleStatus.moodle_props?.students_synced ?: false

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
            add(
                Sidenav.Action(Icons.addPerson, "Lisa õpetajaid") {
                    if (addTeachersModal.openWithClosePromise().await()) {
                        val t = tabsComp.getSelectedTab()
                        createAndBuild().await()
                        tabsComp.setSelectedTab(t)
                    }
                }
            )
            if (!studentsSynced) add(
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