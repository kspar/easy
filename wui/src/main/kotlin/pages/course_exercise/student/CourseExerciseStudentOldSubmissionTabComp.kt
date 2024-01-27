package pages.course_exercise.student

import components.code_editor.CodeEditorComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseFeedbackComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise


class CourseExerciseStudentOldSubmissionTabComp(
    private var submission: CourseExercisesStudentDAO.StudentSubmission?,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    parent: Component
) : Component(parent) {

    private lateinit var editor: CodeEditorComp
    private lateinit var feedback: ExerciseFeedbackComp


    override val children: List<Component>
        get() = listOfNotNull(editor, feedback)

    override fun create() = doInPromise {

        editor = CodeEditorComp(
            CodeEditorComp.File(solutionFileName, submission?.solution, CodeEditorComp.Edit.READONLY),
            parent = this
        )

        feedback = ExerciseFeedbackComp(
            submission?.validGrade,
            submission?.feedback_auto,
            submission?.feedback_teacher,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED,
            this
        )
    }

    suspend fun setSubmission(submission: CourseExercisesStudentDAO.StudentSubmission) {
        this.submission = submission
        createAndBuild().await()
    }
}
