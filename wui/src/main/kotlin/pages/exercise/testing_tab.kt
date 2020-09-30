package pages.exercise

import Str
import components.CodeEditorComp
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import onSingleClickWithDisabled
import org.w3c.dom.HTMLButtonElement
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdAs
import tmRender
import kotlin.js.Promise


class TestingTabComp(
        private val exerciseId: String,
        parent: Component?
) : Component(parent) {

    @Serializable
    data class AutoAssessmentDTO(
            val grade: Int,
            val feedback: String?
    )

    private val editorTabName = "${Str.solutionCodeTabName()}.py"
    private val assessmentId = IdGenerator.nextId()

    private lateinit var editor: CodeEditorComp
    private var assessment: AssessmentViewComp? = null

    override val children: List<Component>
        get() = listOfNotNull(editor, assessment)

    override fun create(): Promise<*> = doInPromise {
        // TODO: get last submission
        editor = CodeEditorComp(CodeEditorComp.File(editorTabName, null, "python"), this)
    }

    override fun render(): String = tmRender("t-c-exercise-tab-testing",
            "assessId" to assessmentId,
            "editorId" to editor.dstId,
            "doTestLabel" to Str.doAutoAssess()
    )

    override fun postRender() {
        getElemByIdAs<HTMLButtonElement>("testing-submit").onSingleClickWithDisabled(Str.autoAssessing()) {
            editor.setFileEditable(editorTabName, false)
            assessment = AssessmentViewComp(null, Str.autoAssessing(), true, this, assessmentId)
            assessment?.createAndBuild()?.await()

            val a = submitCheck(editor.getFileValue(editorTabName))
            assessment = AssessmentViewComp(a.grade, a.feedback, true, this, assessmentId)
            assessment?.createAndBuild()?.await()
            editor.setFileEditable(editorTabName, true)
        }
    }

    private suspend fun submitCheck(solution: String): AutoAssessmentDTO {
        return fetchEms("/exercises/$exerciseId/testing/autoassess", ReqMethod.POST, mapOf("solution" to solution),
                successChecker = { http200 }).await()
                .parseTo(AutoAssessmentDTO.serializer()).await()
    }
}


class AssessmentViewComp(
        private val grade: Int?,
        private val feedback: String?,
        private val isAuto: Boolean,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    override fun render(): String = tmRender("t-c-assessment-view",
            "title" to if (isAuto) Str.autoAssessmentLabel() else Str.teacherAssessmentLabel(),
            "auto" to isAuto,
            "gradeLabel" to if (isAuto) Str.autoGradeLabel() else Str.teacherGradeLabel(),
            "grade" to grade?.toString(),
            "feedback" to feedback
    )
}