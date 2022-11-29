package pages.sidenav

import Icons
import Role
import cache.BasicCourseInfo
import kotlinx.coroutines.await
import pages.course_exercises.CourseExercisesPage
import pages.grade_table.GradeTablePage
import pages.participants.ParticipantsPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import tmRender
import kotlin.js.Promise

class SidenavCourseSectionComp(
    private val activeRole: Role,
    private val courseId: String,
    parent: Component,
    dstId: String,
) : SidenavSectionComp(parent, dstId) {

    private lateinit var courseTitle: String

    private val exercisesItemId = IdGenerator.nextId()
    private val gradesItemId = IdGenerator.nextId()
    private val participantsItemId = IdGenerator.nextId()

    override val children = emptyList<Component>()

    override fun create(): Promise<*> = doInPromise {
        courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
    }

    override fun render(): String = tmRender(
        "t-c-sidenav-course-section",
        "courseTitle" to courseTitle,
        "isTeacherOrAdmin" to listOf(Role.TEACHER, Role.ADMIN).contains(activeRole),
        "exercisesId" to exercisesItemId,
        "gradesId" to gradesItemId,
        "participantsId" to participantsItemId,
        "exercisesLink" to CourseExercisesPage.link(courseId),
        "gradesLink" to GradeTablePage.link(courseId),
        "participantsLink" to ParticipantsPage.link(courseId),
        "exercisesIcon" to Icons.courseExercises,
        "gradesIcon" to Icons.courseGrades,
        "participantsIcon" to Icons.courseParticipants,
        "exercisesLabel" to "Ülesanded",
        "gradesLabel" to "Hinded",
        "participantsLabel" to "Osalejad",
    )

    override fun getActivePageItemIds() = mapOf(
        ActivePage.COURSE_EXERCISES to exercisesItemId,
        ActivePage.COURSE_GRADES to gradesItemId,
        ActivePage.COURSE_PARTICIPANTS to participantsItemId,
    )
}