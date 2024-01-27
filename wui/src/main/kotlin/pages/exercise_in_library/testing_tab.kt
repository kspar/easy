package pages.exercise_in_library

import EzDate
import EzDateSerializer
import Icons
import components.code_editor.CodeEditorComp
import components.form.ButtonComp
import components.text.AttrsComp
import components.text.WarningComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.course_exercise.AutogradeLoaderComp
import pages.course_exercise.ExerciseFeedbackComp
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import kotlin.js.Promise


class TestingTabComp(
    private val exerciseId: String,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    parent: Component?
) : Component(parent) {

    @Serializable
    data class LatestSubmissions(
        val count: Int,
        val submissions: List<LatestSubmission>,
    )

    @Serializable
    data class LatestSubmission(
        val id: String,
        val solution: String,
        @Serializable(with = EzDateSerializer::class)
        val created_at: EzDate,
    )


    private lateinit var warning: WarningComp
    private lateinit var attrs: AttrsComp
    private lateinit var editor: CodeEditorComp

    private val submitBtn = ButtonComp(
        ButtonComp.Type.PRIMARY, Str.doAutoAssess, Icons.robot, ::submit,
        clickedLabel = Str.autoAssessing, parent = this
    )

    private lateinit var feedback: ExerciseFeedbackComp
    private lateinit var autogradeLoader: AutogradeLoaderComp

    override val children: List<Component>
        get() = listOfNotNull(warning, attrs, editor, submitBtn, feedback, autogradeLoader)

    override fun create(): Promise<*> = doInPromise {
        val submissions =
            fetchEms("/exercises/$exerciseId/testing/autoassess/submissions${createQueryString("limit" to "1")}",
                ReqMethod.GET,
                successChecker = { http200 }
            ).await().parseTo(LatestSubmissions.serializer()).await()
        val latestSubmission = submissions.submissions.getOrNull(0)

        warning = WarningComp(parent = this)

        attrs = AttrsComp(
            buildMap {
                if (latestSubmission != null)
                    put(Str.lastTestingAttempt, latestSubmission.created_at.toHumanString(EzDate.Format.FULL))
            },
            this
        )

        editor = CodeEditorComp(
            CodeEditorComp.File(solutionFileName, latestSubmission?.solution.orEmpty()),
            placeholder = Str.solutionEditorPlaceholder, parent = this
        )

        feedback = ExerciseFeedbackComp(null, null, null, false, this)
        autogradeLoader = AutogradeLoaderComp(false, this)
    }

    override fun render() = template(
        """
            <ez-exercise-testing-tab>
                <ez-dst id="${warning.dstId}"></ez-dst>
                <ez-dst id="${attrs.dstId}"></ez-dst>
                <ez-dst id="${editor.dstId}"></ez-dst>
                <div id='${submitBtn.dstId}' style="display: flex; justify-content: center; margin-top: 3rem;"></div>
                <ez-dst id='${autogradeLoader.dstId}'></ez-dst>
                <ez-dst id='${feedback.dstId}'></ez-dst>
            </ez-exercise-testing-tab>
        """.trimIndent(),
    )

    fun setEditing(nowEditing: Boolean) {
        if (nowEditing)
            warning.setMsg(Str.testingEditedWarnMsg)
        else
            warning.setMsg(null)
    }


    private suspend fun submit() {
        try {
            editor.setFileEditable(solutionFileName, false)
            feedback.clearAll()

            var autoassessFinished = false
            val assessmentP = ExerciseDAO.autoassess(exerciseId, editor.getFileValue(solutionFileName)).then {
                autoassessFinished = true
                it
            }
            autogradeLoader.runUntil(true) { !autoassessFinished }

            val assssment = assessmentP.await()

            feedback.validGrade = CourseExercisesStudentDAO.ValidGrade(assssment.grade, ExerciseDAO.GraderType.AUTO)
            feedback.autoFeedback = assssment.feedback
            feedback.rebuild()
            attrs.attrs = mapOf(Str.lastTestingAttempt to assssment.timestamp.toHumanString(EzDate.Format.FULL))
        } finally {
            editor.setFileEditable(solutionFileName, true)
        }
    }
}