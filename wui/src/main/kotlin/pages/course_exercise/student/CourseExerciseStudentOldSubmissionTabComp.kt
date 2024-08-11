package pages.course_exercise.student

import components.code_editor.old.OldCodeEditorComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import pages.course_exercise.teacher.SubmissionCommentsListComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise


class CourseExerciseStudentOldSubmissionTabComp(
    private val courseId: String,
    private val courseExId: String,
    private var submission: CourseExercisesStudentDAO.StudentSubmission?,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    parent: Component
) : Component(parent) {

    private lateinit var editor: OldCodeEditorComp
    private lateinit var feedback: ExerciseAutoFeedbackHolderComp
    private var commentsList: SubmissionCommentsListComp? = null


    override val children: List<Component>
        get() = listOfNotNull(editor, feedback, commentsList)

    override fun create() = doInPromise {

        editor = OldCodeEditorComp(
            OldCodeEditorComp.File(solutionFileName, submission?.solution, OldCodeEditorComp.Edit.READONLY),
            parent = this
        )

        feedback = ExerciseAutoFeedbackHolderComp(
            submission?.auto_assessment?.feedback,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED,
            false,
            this
        )

        commentsList = SubmissionCommentsListComp(
            courseId, courseExId, null,
            onlyForSubmissionId = submission?.id,
            parent = this
        )
    }

    suspend fun setSubmission(submission: CourseExercisesStudentDAO.StudentSubmission) {
        this.submission = submission
        createAndBuild().await()
    }
}
