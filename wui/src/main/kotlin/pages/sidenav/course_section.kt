package pages.sidenav

import Icons
import Role
import cache.BasicCourseInfo
import dao.CourseExercisesStudentDAO
import kotlinx.coroutines.await
import kotlinx.dom.removeClass
import pages.about.SimilarityAnalysisPage
import pages.course_exercise.ExerciseSummaryPage
import pages.course_exercises_list.CourseExercisesPage
import pages.grade_table.GradeTablePage
import pages.participants.ParticipantsPage
import rip.kspar.ezspa.*
import successMessage
import template
import translation.Str
import kotlin.js.Promise

class SidenavCourseSectionComp(
    private val activeRole: Role,
    private val courseId: String,
    parent: Component,
    dstId: String,
) : SidenavSectionComp(parent, dstId) {

    private lateinit var courseTitle: String
    private lateinit var updateCourseModal: UpdateCourseModalComp

    // populated if student
    private var studentExercises: List<CourseExercisesStudentDAO.Exercise> = emptyList()
    private val studentExerciseIdPrefix = IdGenerator.nextId()

    private val updateModalLinkId = IdGenerator.nextId()
    private val exercisesItemId = IdGenerator.nextId()
    private val gradesItemId = IdGenerator.nextId()
    private val participantsItemId = IdGenerator.nextId()
    private val similarityItemId = IdGenerator.nextId()

    override val children
        get() = listOf(updateCourseModal)

    override fun create(): Promise<*> = doInPromise {
        val info = BasicCourseInfo.get(courseId).await()
        if (activeRole == Role.STUDENT) {
            studentExercises = CourseExercisesStudentDAO.getCourseExercises(courseId).await()
        }
        courseTitle = info.effectiveTitle
        updateCourseModal = UpdateCourseModalComp(courseId, info.title, info.alias, activeRole == Role.ADMIN, this)
    }

    override fun render(): String = template(
        """
            <li><div class="divider"></div></li>
            <li title="{{courseTitle}}"><a class="subheader truncate">{{courseTitle}}</a></li>
            <li id="{{exercisesId}}"><a href="{{exercisesLink}}" class="sidenav-close">{{{exercisesIcon}}}{{exercisesLabel}}</a></li>
            {{#isTeacherOrAdmin}}
                <li id="{{gradesId}}"><a href="{{gradesLink}}" class="sidenav-close">{{{gradesIcon}}}{{gradesLabel}}</a></li>
                <li id="{{participantsId}}"><a href="{{participantsLink}}" class="sidenav-close">{{{participantsIcon}}}{{participantsLabel}}</a></li>
                <li id="$similarityItemId"><a href="{{similarityLink}}" class="sidenav-close">{{{similarityIcon}}}{{similarityLabel}}</a></li>
                <li><a id="{{updateCourseLinkId}}" href='#!' class="sidenav-close">{{{updateCourseIcon}}}{{updateCourseLabel}}</a></li>
            {{/isTeacherOrAdmin}}
            {{#studentExercises}}
                <li id='{{id}}'><a href="{{link}}" class="student-course-item {{#green}}circle-green{{/green}} {{#yellow}}circle-yellow{{/yellow}} {{#blue}}circle-blue{{/blue}} {{#grey}}circle-grey{{/grey}} sidenav-close">{{{icon}}}<span class='truncate'>{{title}}</span></a></li>
            {{/studentExercises}}
            <ez-dst id="{{updateModalDst}}"></ez-dst>
        """.trimIndent(),
        "courseTitle" to courseTitle,
        "isTeacherOrAdmin" to listOf(Role.TEACHER, Role.ADMIN).contains(activeRole),
        "exercisesId" to exercisesItemId,
        "gradesId" to gradesItemId,
        "participantsId" to participantsItemId,
        "updateCourseLinkId" to updateModalLinkId,
        "exercisesLink" to CourseExercisesPage.link(courseId),
        "gradesLink" to GradeTablePage.link(courseId),
        "participantsLink" to ParticipantsPage.link(courseId),
        "similarityLink" to SimilarityAnalysisPage.link(courseId),
        "exercisesIcon" to Icons.courseExercises,
        "gradesIcon" to Icons.courseGrades,
        "participantsIcon" to Icons.courseParticipants,
        "similarityIcon" to Icons.compareSimilarity,
        "updateCourseIcon" to Icons.settings,
        "exercisesLabel" to if (activeRole == Role.STUDENT) Str.allExercises else Str.exercises,
        "gradesLabel" to Str.gradesLabel,
        "participantsLabel" to Str.participants,
        "similarityLabel" to Str.similarityAnalysis,
        "updateCourseLabel" to Str.courseSettings,
        "updateModalDst" to updateCourseModal.dstId,
        "studentExercises" to studentExercises.map {
            mapOf(
                "id" to (studentExerciseIdPrefix + it.id),
                "link" to ExerciseSummaryPage.link(courseId, it.id),
                "icon" to when (it.grade?.grade) {
                    100 -> Icons.awardWithCheck
                    else -> Icons.circle
                },
                "green" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED),
                "yellow" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.STARTED),
                "blue" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.UNGRADED),
                "grey" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED),
                "title" to it.effective_title,
            )
        }
    )

    override fun clearAndSetActivePage(activePage: ActivePage?) {
        super.clearAndSetActivePage(activePage)
        // This is not handled by general impl
        studentExercises.forEach {
            getElemByIdOrNull(studentExerciseIdPrefix + it.id)?.removeClass("active")
        }
        if (activePage == ActivePage.STUDENT_EXERCISE) {
            paintItemActive(studentExerciseIdPrefix + ExerciseSummaryPage.courseExerciseId)
        }
    }

    override fun getActivePageItemIds() = mapOf(
        ActivePage.COURSE_EXERCISES to exercisesItemId,
        ActivePage.COURSE_GRADES to gradesItemId,
        ActivePage.COURSE_PARTICIPANTS to participantsItemId,
        ActivePage.COURSE_SIMILARITY_ANALYSIS to similarityItemId,
    )

    override fun postRender() {
        getElemByIdOrNull(updateModalLinkId)?.onVanillaClick(true) {
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