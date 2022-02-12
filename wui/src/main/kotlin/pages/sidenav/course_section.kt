package pages.sidenav

import Icons
import Role
import Str
import cache.BasicCourseInfo
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import libheaders.Materialize
import onSingleClickWithDisabled
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import pages.OldParticipantsPage
import pages.course_exercises.CourseExercisesPage
import pages.exercise.ExercisePage
import pages.grade_table.GradeTablePage
import pages.participants.ParticipantsPage
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.*
import tmRender
import kotlin.js.Promise

class SidenavCourseSectionComp(
    private val activeRole: Role,
    private val courseId: String,
    parent: Component,
    dstId: String,
) : SidenavSectionComp(parent, dstId) {

    @Serializable
    private data class NewExerciseDTO(val id: String)

    private lateinit var courseTitle: String

    private val exercisesItemId = IdGenerator.nextId()
    private val gradesItemId = IdGenerator.nextId()
    private val participantsItemId = IdGenerator.nextId()
    private val oldParticipantsItemId = IdGenerator.nextId()

    override fun create(): Promise<*> = doInPromise {
        courseTitle = BasicCourseInfo.get(courseId).await().title
    }

    override fun render(): String = tmRender(
        "t-c-sidenav-course-section",
        "courseTitle" to courseTitle,
        "isTeacherOrAdmin" to listOf(Role.TEACHER, Role.ADMIN).contains(activeRole),
        "isAdmin" to (activeRole == Role.ADMIN),
        "exercisesId" to exercisesItemId,
        "gradesId" to gradesItemId,
        "participantsId" to participantsItemId,
        "oldParticipantsId" to oldParticipantsItemId,
        "exercisesLink" to CourseExercisesPage.link(courseId),
        "gradesLink" to GradeTablePage.link(courseId),
        "participantsLink" to ParticipantsPage.link(courseId),
        "oldParticipantsLink" to OldParticipantsPage.link(courseId),
        "exercisesIcon" to Icons.courseExercises,
        "gradesIcon" to Icons.courseGrades,
        "participantsIcon" to Icons.courseParticipants,
        "addExerciseIcon" to Icons.addExerciseToCourse,
        "newExerciseIcon" to Icons.newExercise,
        "exercisesLabel" to "Ülesanded",
        "gradesLabel" to "Hinded",
        "participantsLabel" to "Osalejad",
        "oldParticipantsLabel" to "Osalejad",
        "newExerciseLabel" to "Uus ülesanne",
        "addExerciseLabel" to "Lisa ülesanne kogust",
    )

    override fun postRender() {
        // TODO: replace with modal comp
        initNewExerciseModal()
    }

    private fun initNewExerciseModal() {
        val modal = Materialize.Modal.init(getElemById("new-exercise-modal"))
        getElemByIdAs<HTMLButtonElement>("new-exercise-btn").onSingleClickWithDisabled(Str.saving()) {
            val exerciseTitle = getElemByIdAs<HTMLInputElement>("new-exercise-name-input").value
            debug { "Saving new exercise with title $exerciseTitle" }
            val exerciseId = fetchEms("/exercises",
                ReqMethod.POST,
                mapOf("title" to exerciseTitle, "public" to false, "grader_type" to "TEACHER"),
                successChecker = { http200 }).await().parseTo(NewExerciseDTO.serializer()).await().id
            debug { "Saved new exercise with id $exerciseId" }
            modal.close()
            EzSpa.PageManager.navigateTo(ExercisePage.link(exerciseId))
        }
    }

    override fun getActivePageItemIds() = mapOf(
        ActivePage.COURSE_EXERCISES to exercisesItemId,
        ActivePage.COURSE_GRADES to gradesItemId,
        ActivePage.COURSE_PARTICIPANTS to participantsItemId,
        ActivePage.COURSE_PARTICIPANTS_OLD to oldParticipantsItemId,
    )
}