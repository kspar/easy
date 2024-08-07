package pages.course_exercise.teacher

import EzDate
import Icons
import components.ButtonComp
import components.IconButtonComp
import components.MissingContentPlaceholderComp
import components.code_editor.CodeEditorComp
import components.text.WarningComp
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesTeacherDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template
import translation.Str

class TeacherCourseExerciseStudentTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val exerciseId: String,
    private val deadline: EzDate?,
    private val solutionFileName: String,
    var studentId: String,
    var studentName: String,
    var submissionId: String?,
    var latestSubmissionId: String?,
    private val onNextStudent: suspend (currentStudentId: String) -> Unit,
    private val onPrevStudent: suspend (currentStudentId: String) -> Unit,
    private val onNewSubmissionOpened: suspend () -> Unit,
    parent: Component
) : Component(parent) {

    private var submission: CourseExercisesTeacherDAO.StudentSubmissionDetails? = null
    private var isOldSubmission: Boolean = false  // set in create

    private lateinit var prevStudentBtn: IconButtonComp
    private lateinit var nextStudentBtn: IconButtonComp
    private var allSubsBtn: ButtonComp? = null
    private var allSubsModal: AllSubmissionsModalComp? = null

    private var oldSubmissionWarning: WarningComp? = null

    private var solutionCodeEditor: CodeEditorComp? = null
    private var missingSolutionPlaceholder: MissingContentPlaceholderComp? = null

    private var gradeComp: SubmissionGradeComp? = null
    private lateinit var autoFeedbackComp: ExerciseAutoFeedbackHolderComp
    private var commentsSection: SubmissionCommentsListComp? = null

    override val children: List<Component>
        get() = listOfNotNull(
            prevStudentBtn,
            nextStudentBtn,
            allSubsBtn,
            allSubsModal,
            oldSubmissionWarning,
            solutionCodeEditor,
            missingSolutionPlaceholder,
            gradeComp,
            autoFeedbackComp,
            commentsSection,
        )

    override fun create() = doInPromise {
        submission = submissionId?.let {
            CourseExercisesTeacherDAO.getSubmissionDetails(courseId, courseExId, it).await()
        }

        isOldSubmission = submissionId != latestSubmissionId

        prevStudentBtn = IconButtonComp(
            Icons.previous, null,
            onClick = { onPrevStudent(studentId) },
            parent = this
        )
        nextStudentBtn = IconButtonComp(
            Icons.next, null,
            onClick = { onNextStudent(studentId) },
            parent = this
        )
        allSubsBtn = submission?.let {
            ButtonComp(
                if (isOldSubmission) ButtonComp.Type.OUTLINED else ButtonComp.Type.TEXT,
                Str.submission + " #" + it.submission_number,
                Icons.history,
                onClick = ::openAllSubsModal, parent = this
            )
        }
        allSubsModal = submissionId?.let {
            AllSubmissionsModalComp(courseId, courseExId, studentId, studentName, it, this)
        }

        // TODO: link to last submission if not too difficult
        oldSubmissionWarning = if (isOldSubmission)
            WarningComp(Str.oldSubmissionNote, parent = this)
        else null

        // TODO:
        //  - code editor menu items:
        //    - download as file
        //    - edit and submit
        //    - check similarity? (new tab with similarity page and filtered out results for only this student (new dropdown needed probably))
        solutionCodeEditor = submission?.let {
            CodeEditorComp(
                CodeEditorComp.File(solutionFileName, it.solution, CodeEditorComp.Edit.READONLY),
                parent = this
            )
        }

        missingSolutionPlaceholder = if (submission == null)
            MissingContentPlaceholderComp(Str.missingSolution, true, this)
        else null

        val s = submission
        gradeComp = if (s != null && !isOldSubmission)
            SubmissionGradeComp(
                s.grade,
                if (!isOldSubmission)
                    SubmissionGradeComp.GradeEdit(courseId, courseExId, s.id,
                        onGradeSaved = { createAndBuild().await() }
                    ) else null,
                parent = this
            )
        else null

        val autoassessFeedback = submission?.auto_assessment?.feedback
        val autoassessFailed = submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED
        autoFeedbackComp = ExerciseAutoFeedbackHolderComp(
            autoassessFeedback, autoassessFailed, !isOldSubmission, parent = this
        )

        commentsSection = latestSubmissionId?.let {
            SubmissionCommentsListComp(
                courseId, courseExId,
                SubmissionCommentsListComp.TeacherConf(studentId, it, !isOldSubmission),
                parent = this
            )
        }
    }

    override fun render() = template(
        """
            <ez-sub-header style='display: flex; justify-content: space-between; flex-wrap: wrap;'>
                <ez-sub-title style='display: flex; align-items: center;'>
                    $prevStudentBtn
                    $nextStudentBtn
                    <ez-sub-student-name style='font-size: 1.2em; margin-left: 1rem; margin-right: 2rem;'>{{name}}</ez-sub-student-name>
                </ez-sub-title>
                {{#hasSubmission}}
                    <ez-sub-title-secondary style='display: flex; align-items: center;'>
                        <ez-sub-time class='{{#overDeadline}}over-deadline{{/overDeadline}}'>
                            {{{deadlineIcon}}}
                            <span style='padding: .2rem 1rem;'>{{subTime}}</span>
                        </ez-sub-time>                    
                        $allSubsBtn
                    </ez-sub-title-secondary>
                {{/hasSubmission}}
                
            </ez-sub-header>
            {{#isOld}}
                <div style='margin-top: 2rem;'>
                    $oldSubmissionWarning
                </div>
            {{/isOld}}
            
            ${solutionCodeEditor.dstIfNotNull()}
            ${missingSolutionPlaceholder.dstIfNotNull()}
            
            $autoFeedbackComp
            
            ${gradeComp.dstIfNotNull()}
            ${commentsSection.dstIfNotNull()}
            
            ${allSubsModal.dstIfNotNull()}
            
        """.trimIndent(),
        "name" to studentName,
        "hasSubmission" to (submission != null),
        "isOld" to isOldSubmission,
        "timeIcon" to Icons.pending,
        "overDeadline" to submission?.let { deadline != null && deadline < it.created_at },
        "deadlineIcon" to Icons.alarmClock,
        "subTime" to submission?.created_at?.toHumanString(EzDate.Format.FULL),
    )

    suspend fun setStudent(id: String, name: String, submissionIdd: String?, latestSubmissionIdd: String?) {
        studentId = id
        studentName = name
        submissionId = submissionIdd
        latestSubmissionId = latestSubmissionIdd
        createAndBuild().await()
    }

    fun setPrevNextBtns(prevStudentName: String?, nextStudentName: String?) {
        prevStudentBtn.label = prevStudentName
        prevStudentBtn.enabled = prevStudentName != null
        prevStudentBtn.rebuild()
        nextStudentBtn.label = nextStudentName
        nextStudentBtn.enabled = nextStudentName != null
        nextStudentBtn.rebuild()
    }

    private suspend fun openAllSubsModal() {
        val selectedSubmissionId = allSubsModal?.open()
        if (selectedSubmissionId != null) {
            submissionId = selectedSubmissionId
            createAndBuild().await()
            onNewSubmissionOpened()
        }
    }
}