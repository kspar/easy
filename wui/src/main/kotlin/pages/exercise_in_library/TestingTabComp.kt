package pages.exercise_in_library

import EzDate
import EzDateSerializer
import Icons
import components.code_editor.CodeEditorComp
import components.dropdown.DropdownMenuComp
import components.form.ButtonComp
import components.text.AttrsComp
import components.text.WarningComp
import dao.ExerciseDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import nowTimestamp
import pages.course_exercise.AutogradeLoaderComp
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import saveTextAsFile
import template
import translation.Str
import uploadFile
import kotlin.js.Promise


class TestingTabComp(
    private val exerciseId: String,
    private val courseId: String?,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
) : Component() {

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
        ButtonComp.Type.FILLED, Str.doAutoAssess, Icons.robot,
        clickedLabel = Str.autoAssessing,
        onClick = ::submit,
        parent = this
    )

    private lateinit var feedback: ExerciseAutoFeedbackHolderComp
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
            listOf(
                CodeEditorComp.File(solutionFileName, latestSubmission?.solution),
            ),
            menuOptions = listOf(
                DropdownMenuComp.Item(
                    Str.uploadSubmission, Icons.upload, onSelected = {
                        uploadFile { editor.setContent(it.content) }
                    }
                ),
                DropdownMenuComp.Item(
                    Str.downloadSubmission, Icons.download, onSelected = {
                        saveTextAsFile("${exerciseId}_${nowTimestamp()}_$solutionFileName", editor.getContent())
                    }
                )
            ),
            parent = this
        )

        feedback = ExerciseAutoFeedbackHolderComp(
            null,
            failed = false, canRetry = false, isOpen = true,
            parent = this
        )
        autogradeLoader = AutogradeLoaderComp(false, this)
    }

    override fun render() = template(
        """
            <ez-exercise-testing-tab>
                $warning
                $attrs
                $editor
                <div id='${submitBtn.dstId}' style="display: flex; justify-content: center; margin-top: 3rem;"></div>
                $autogradeLoader
                $feedback
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
            editor.setFileProps(editable = false)
            editor.setActionsEnabled(false)
            feedback.clear()

            var autoassessFinished = false
            val assessmentP = ExerciseDAO.autoassess(exerciseId, editor.getContent(), courseId).then {
                autoassessFinished = true
                it
            }
            autogradeLoader.runUntil(true) { !autoassessFinished }

            val assssment = assessmentP.await()

            feedback.setFeedback(assssment.feedback, false)
            attrs.attrs = mapOf(Str.lastTestingAttempt to assssment.timestamp.toHumanString(EzDate.Format.FULL))
        } finally {
            editor.setActionsEnabled(true)
            editor.setFileProps(editable = true)
        }
    }
}