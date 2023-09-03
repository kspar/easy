package pages.exercise_in_library

import DateSerializer
import Icons
import components.code_editor.CodeEditorComp
import components.form.ButtonComp
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.course_exercise.AutogradeLoaderComp
import pages.course_exercise.ExerciseFeedbackComp
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import kotlin.js.Date
import kotlin.js.Promise


class TestingTabComp(
    private val exerciseId: String,
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
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
    )

    @Serializable
    data class AutoAssessmentDTO(
        val grade: Int,
        val feedback: String?
    )

    private val editorTabName = "${Str.solutionCodeTabName}.py"
    private val assessmentId = IdGenerator.nextId()

    private lateinit var editor: CodeEditorComp

    private val submitBtn = ButtonComp(
        ButtonComp.Type.PRIMARY, Str.doAutoAssess, Icons.robot, ::submit,
        clickedLabel = Str.autoAssessing, parent = this
    )

    private lateinit var feedback: ExerciseFeedbackComp
    private lateinit var autogradeLoader: AutogradeLoaderComp

    override val children: List<Component>
        get() = listOfNotNull(editor, submitBtn, feedback, autogradeLoader)

    override fun create(): Promise<*> = doInPromise {
        val submissions =
            fetchEms("/exercises/$exerciseId/testing/autoassess/submissions${createQueryString("limit" to "1")}",
                ReqMethod.GET,
                successChecker = { http200 }
            ).await().parseTo(LatestSubmissions.serializer()).await()
        val latestSubmission = submissions.submissions.getOrNull(0)?.solution

        editor = CodeEditorComp(
            CodeEditorComp.File(editorTabName, latestSubmission),
            placeholder = Str.solutionEditorPlaceholder, parent = this
        )

        feedback = ExerciseFeedbackComp(null, null, null, false, this)
        autogradeLoader = AutogradeLoaderComp(false, this)
    }

    override fun render() = template(
        """
            <ez-dst id="$assessmentId"></ez-dst>
            <ez-dst id="${editor.dstId}"></ez-dst>
            <div id='${submitBtn.dstId}' style="display: flex; justify-content: center; margin-top: 3rem;"></div>
            <ez-dst id='${autogradeLoader.dstId}'></ez-dst>
            <ez-dst id='${feedback.dstId}'></ez-dst>
        """.trimIndent(),
    )


    private suspend fun submit() {
        try {
            editor.setFileEditable(editorTabName, false)

            feedback.clearAll()
            feedback.rebuild()
            autogradeLoader.isActive = true
            autogradeLoader.rebuild()

            val a = submitCheck(editor.getFileValue(editorTabName))

            autogradeLoader.isActive = false
            autogradeLoader.rebuild()
            feedback.autoFeedback = a.feedback
            feedback.rebuild()
        } finally {
            editor.setFileEditable(editorTabName, true)
        }
    }

    private suspend fun submitCheck(solution: String): AutoAssessmentDTO {
        return fetchEms("/exercises/$exerciseId/testing/autoassess", ReqMethod.POST, mapOf("solution" to solution),
            successChecker = { http200 }).await()
            .parseTo(AutoAssessmentDTO.serializer()).await()
    }
}