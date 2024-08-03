package pages.course_exercise.student

import components.code_editor.CodeEditorComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import pages.course_exercise.teacher.SubmissionGradeComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise


class CourseExerciseStudentOldSubmissionTabComp(
    private var submission: CourseExercisesStudentDAO.StudentSubmission?,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    parent: Component
) : Component(parent) {

    private lateinit var editor: CodeEditorComp
    private lateinit var gradeComp: SubmissionGradeComp
    private lateinit var feedback: ExerciseAutoFeedbackHolderComp


    override val children: List<Component>
        get() = listOfNotNull(editor, gradeComp, feedback)

    override fun create() = doInPromise {

        editor = CodeEditorComp(
            CodeEditorComp.File(solutionFileName, submission?.solution, CodeEditorComp.Edit.READONLY),
            parent = this
        )

        gradeComp = SubmissionGradeComp(
            submission?.grade, null,
            parent = this
        )

        feedback = ExerciseAutoFeedbackHolderComp(
            submission?.auto_assessment?.feedback,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED,
            false,
            this
        )
    }

    suspend fun setSubmission(submission: CourseExercisesStudentDAO.StudentSubmission) {
        this.submission = submission
        createAndBuild().await()
    }
}
