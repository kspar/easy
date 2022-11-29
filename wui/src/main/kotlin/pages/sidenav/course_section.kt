package pages.sidenav

import Icons
import Role
import cache.BasicCourseInfo
import kotlinx.coroutines.await
import pages.course_exercises.CourseExercisesPage
import pages.grade_table.GradeTablePage
import pages.participants.ParticipantsPage
import rip.kspar.ezspa.*
import successMessage
import tmRender
import kotlin.js.Promise

class SidenavCourseSectionComp(
    private val activeRole: Role,
    private val courseId: String,
    parent: Component,
    dstId: String,
) : SidenavSectionComp(parent, dstId) {

    private lateinit var courseTitle: String
    private lateinit var updateCourseModal: UpdateCourseModalComp

    private val updateModalLinkId = IdGenerator.nextId()
    private val exercisesItemId = IdGenerator.nextId()
    private val gradesItemId = IdGenerator.nextId()
    private val participantsItemId = IdGenerator.nextId()

    override val children
        get() = listOf(updateCourseModal)

    override fun create(): Promise<*> = doInPromise {
        val info = BasicCourseInfo.get(courseId).await()
        courseTitle = info.effectiveTitle
        updateCourseModal = UpdateCourseModalComp(courseId, info.title, info.alias, activeRole == Role.ADMIN, this)
    }

    override fun render(): String = tmRender(
        "t-c-sidenav-course-section",
        "courseTitle" to courseTitle,
        "isTeacherOrAdmin" to listOf(Role.TEACHER, Role.ADMIN).contains(activeRole),
        "exercisesId" to exercisesItemId,
        "gradesId" to gradesItemId,
        "participantsId" to participantsItemId,
        "updateCourseLinkId" to updateModalLinkId,
        "exercisesLink" to CourseExercisesPage.link(courseId),
        "gradesLink" to GradeTablePage.link(courseId),
        "participantsLink" to ParticipantsPage.link(courseId),
        "exercisesIcon" to Icons.courseExercises,
        "gradesIcon" to Icons.courseGrades,
        "participantsIcon" to Icons.courseParticipants,
        "updateCourseIcon" to Icons.settings,
        "exercisesLabel" to "Ülesanded",
        "gradesLabel" to "Hinded",
        "participantsLabel" to "Osalejad",
        "updateCourseLabel" to "Kursuse sätted",
        "updateModalDst" to updateCourseModal.dstId,
    )

    override fun getActivePageItemIds() = mapOf(
        ActivePage.COURSE_EXERCISES to exercisesItemId,
        ActivePage.COURSE_GRADES to gradesItemId,
        ActivePage.COURSE_PARTICIPANTS to participantsItemId,
    )

    override fun postRender() {
        getElemByIdOrNull(updateModalLinkId)?.onVanillaClick(false) {
            val saved = updateCourseModal.openWithClosePromise().await()
            if (saved) {
                BasicCourseInfo.invalidate(courseId)
                createAndBuild()
                EzSpa.PageManager.updatePage()
                successMessage { "Kursus uuendatud" }
            }
        }
    }
}