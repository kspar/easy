package pages.course_exercise.student

import components.code_editor.CodeEditorComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import pages.course_exercise.teacher.SubmissionCommentsListComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template


class CourseExerciseStudentOldSubmissionTabComp(
    private val courseId: String,
    private val courseExId: String,
    private var submission: CourseExercisesStudentDAO.StudentSubmission?,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    parent: Component
) : Component(parent) {

    private lateinit var editor: CodeEditorComp
    private lateinit var feedback: ExerciseAutoFeedbackHolderComp
    private var commentsList: SubmissionCommentsListComp? = null


    override val children: List<Component>
        get() = listOfNotNull(editor, feedback, commentsList)

    override fun create() = doInPromise {

        editor = CodeEditorComp(
            listOf(CodeEditorComp.File(solutionFileName, submission?.solution, isEditable = false)),
            parent = this
        )

        feedback = ExerciseAutoFeedbackHolderComp(
            submission?.auto_assessment?.feedback,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED,
            canRetry = false, isOpen = false,
            parent = this
        )

        commentsList = SubmissionCommentsListComp(
            courseId, courseExId, null,
            onlyForSubmissionId = submission?.id,
            parent = this
        )
    }

    override fun render() = template(
        """
            <div style="margin-top: 3rem;">
                $editor
            </div>
            $feedback
            $commentsList
        """.trimIndent()
    )

    suspend fun setSubmission(submission: CourseExercisesStudentDAO.StudentSubmission) {
        this.submission = submission
        createAndBuild().await()
    }
}
