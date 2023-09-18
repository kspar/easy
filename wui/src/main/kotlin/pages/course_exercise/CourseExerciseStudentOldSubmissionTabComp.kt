package pages.course_exercise

import components.code_editor.CodeEditorComp
import dao.CourseExercisesStudentDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str


class CourseExerciseStudentOldSubmissionTabComp(
    private var submission: CourseExercisesStudentDAO.StudentSubmission? = null,
    parent: Component
) : Component(parent) {

    private lateinit var editor: CodeEditorComp
    private lateinit var feedback: ExerciseFeedbackComp


    override val children: List<Component>
        get() = listOfNotNull(editor, feedback)

    override fun create() = doInPromise {

        editor = CodeEditorComp(
            CodeEditorComp.File("${Str.solutionCodeTabName}.py", submission?.solution, CodeEditorComp.Edit.READONLY),
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
