package pages.exercise

import DateSerializer
import Str
import components.code_editor.CodeEditorComp
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import onSingleClickWithDisabled
import org.w3c.dom.HTMLButtonElement
import parseTo
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdAs
import tmRender
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

    private val editorTabName = "${Str.solutionCodeTabName()}.py"
    private val assessmentId = IdGenerator.nextId()

    private lateinit var editor: CodeEditorComp
    private var assessment: AssessmentViewComp? = null

    override val children: List<Component>
        get() = listOfNotNull(editor, assessment)

    override fun create(): Promise<*> = doInPromise {
        val submissions =
            fetchEms("/exercises/$exerciseId/testing/autoassess/submissions${createQueryString("limit" to "1")}",
                ReqMethod.GET,
                successChecker = { http200 }
            ).await().parseTo(LatestSubmissions.serializer()).await()
        val latestSubmission = submissions.submissions.getOrNull(0)?.solution

        editor = CodeEditorComp(
            CodeEditorComp.File(editorTabName, latestSubmission),
            placeholder = "Kirjuta või lohista lahendus siia...", parent = this
        )
    }

    override fun render(): String = tmRender(
        "t-c-exercise-tab-testing",
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

    override fun render(): String = tmRender(
        "t-c-assessment-view",
        "title" to if (isAuto) Str.autoAssessmentLabel() else Str.teacherAssessmentLabel(),
        "auto" to isAuto,
        "gradeLabel" to if (isAuto) Str.autoGradeLabel() else Str.teacherGradeLabel(),
        "grade" to grade?.toString(),
        "feedback" to feedback?.let { formatFeedback(it) },
    )
}


@Serializable
data class OkV3(
    val result_type: String,
    val producer: String,
    val points: Double, // TODO: Int
    val pre_evaluate_error: String? = null,
    val tests: List<V3Test>,
)

@Serializable
data class V3Test(
    val title: String,
    val status: V3Status,
    val exception_message: String? = null,
    val user_inputs: List<String>,
    val created_files: List<V3File>,
    val actual_output: String? = null,
    val checks: List<V3Check>,
)

enum class V3Status {
    PASS, FAIL, SKIP
}

@Serializable
data class V3File(
    val name: String,
    val content: String,
)

@Serializable
data class V3Check(
    val title: String,
    val feedback: String,
    val status: V3Status,
)

fun formatFeedback(rawFeedback: String): String {
    val okv3 = try {
        rawFeedback.parseTo(OkV3.serializer())
    } catch (e: Exception) {
        debug { e }
        debug { "Feedback is not in OK_V3 format, falling back to raw" }
        return rawFeedback
    }

    debug { "Feedback parsed to OK_V3 format" }

    if (okv3.pre_evaluate_error != null) {
        debug { "Showing only feedback pre-evaluate error" }
        return okv3.pre_evaluate_error
    }

    return okv3.tests.joinToString("\n\n") {
        val testTitle = "${mapStatus(it.status)} ${it.title}"
        val testContent = it.exception_message ?: run {
            val checks = it.checks.map {
                val checkTitle = "  ${mapStatus(it.status)} ${it.title}"
                val checkFeedback = "      ${it.feedback}"
                "$checkTitle\n$checkFeedback"
            }.joinToString("\n\n")

            val inputs = if (it.user_inputs.isNotEmpty())
                "  Programmile antud sisendid:\n" +
                        it.user_inputs.joinToString("\n") { "    $it" }
            else null

            val files = if (it.created_files.isNotEmpty())
                "  Loodud failid:\n" +
                        it.created_files.joinToString("\n") {
                            val content = it.content.split("\n").joinToString("\n") { "    $it" }
                            "    --- ${it.name} ---\n$content"
                        }
            else null

            val output = if (it.actual_output != null)
                "  Programmi väljund:\n" + it.actual_output.split("\n").joinToString("\n") { "    $it" }
            else null

            listOfNotNull(checks, inputs, files, output).joinToString("\n\n")
        }

        "$testTitle\n$testContent"
    }
}

private fun mapStatus(status: V3Status): String =
    when (status) {
        V3Status.PASS -> "[✓]"
        V3Status.FAIL -> "[x]"
        V3Status.SKIP -> "[-]"
    }