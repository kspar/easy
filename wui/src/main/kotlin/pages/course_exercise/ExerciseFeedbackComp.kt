package pages.course_exercise

import EzDate
import EzDateSerializer
import Icons
import dao.CourseExercisesStudentDAO
import debug
import kotlinx.serialization.Serializable
import libheaders.Materialize
import parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.getElemsByClass
import template
import translation.Str

class ExerciseFeedbackComp(
    var validGrade: CourseExercisesStudentDAO.ValidGrade?,
    var autoFeedback: String?,
    var teacherFeedback: String?,
    var failed: Boolean = false,
    parent: Component?, // temp null for non-wui3
    dstId: String = IdGenerator.nextId(), // temp
) : Component(parent, dstId) {

    @Serializable
    data class OkV3(
        val result_type: String,
        val producer: String,
        @Serializable(with = EzDateSerializer::class)
        val finished_at: EzDate,
        val points: Double, // TODO: Int
        val pre_evaluate_error: String? = null,
        val tests: List<V3Test>,
    )

    @Serializable
    data class V3Test(
        val title: String,
        val status: V3Status,
        val exception_message: String? = null,
        val user_inputs: List<String>? = null,
        val created_files: List<V3File>? = null,
        val actual_output: String? = null,
        val converted_submission: String? = null,
        val checks: List<V3Check>,
    )

    @Serializable
    data class V3File(
        val name: String,
        val content: String,
    )

    @Serializable
    data class V3Check(
        val title: String? = null,
        val feedback: String? = null,
        val status: V3Status,
    )

    enum class V3Status {
        PASS, FAIL, SKIP
    }


    override fun render(): String {
        val parsedV3 = parseAutofeedback()
        val autoFeedbackV3Html = parsedV3?.renderV3Html()
        return if (validGrade == null && autoFeedback == null && teacherFeedback == null && !failed) "" else
            template(
                """
                <ez-feedback>
                    {{#grade}}
                        <ez-grade>{{label}}: <ez-grade-no class="grade-number">{{points}}</ez-grade-no>/100 {{{icon}}}</ez-grade>
                    {{/grade}}
                    {{#teacherFeedback}}
                        <h5>{{title}}</h5>
                        <pre class="feedback">{{feedback}}</pre>
                    {{/teacherFeedback}}
                    {{#hasAutoFeedback}}
                        <h5>{{autoTitle}}</h5>
                        {{#failed}}
                            <pre class="feedback auto-feedback" style='background-color: unset;'>{{failMsg}}</pre>
                        {{/failed}}
                        {{#isRaw}}
                            <pre class="feedback auto-feedback">{{rawFeedback}}</pre>
                        {{/isRaw}}
                        {{#isV3}}
                            {{{okV3Html}}}
                        {{/isV3}}
                    {{/hasAutoFeedback}}
                </ez-feedback>
            """.trimIndent(),
                "grade" to validGrade?.let {
                    mapOf(
                        "label" to Str.gradeLabel,
                        "points" to it.grade.toString(),
                        "icon" to it.grader_type.icon(),
                    )
                },
                "hasAutoFeedback" to (autoFeedback != null || failed),
                "autoTitle" to Str.autoAssessmentLabel,
                "failed" to failed,
                "failMsg" to Str.autogradeFailedMsg,
                "isRaw" to (autoFeedbackV3Html == null),
                "isV3" to (autoFeedbackV3Html != null),
                "rawFeedback" to autoFeedback,
                "okV3Html" to autoFeedbackV3Html,
                "teacherFeedback" to teacherFeedback?.let {
                    mapOf(
                        "title" to Str.teacherAssessmentLabel,
                        "feedback" to it,
                    )
                },
            )
    }

    override fun postRender() {
        val collapsibleElem = getElemById(dstId).getElemsByClass("ez-collapsible").singleOrNull()
        if (collapsibleElem != null)
            Materialize.Collapsible.init(collapsibleElem)
    }

    fun clearAll() {
        validGrade = null
        autoFeedback = null
        teacherFeedback = null
        failed = false
        rebuild()
    }

    private fun parseAutofeedback(): OkV3? =
        try {
            autoFeedback?.parseTo(OkV3.serializer())
                .also { debug { "Feedback is in OK_V3 format" } }
        } catch (e: Exception) {
            debug { e }
            debug { "Feedback is not in OK_V3 format, falling back to raw" }
            null
        }

    private fun OkV3.renderV3Html() = template(
        """
        {{#preEvalError}}<pre>{{preEvalError}}</pre>{{/preEvalError}}
        <ul class="ez-collapsible collapsible" style='margin-top: 1rem;'>
            {{#tests}}
                <li>
                    <div class="collapsible-header">
                        <ez-feedback-test-header class='icon-med {{#pass}}pass{{/pass}} {{#fail}}fail{{/fail}}' 
                            style='display: flex; align-items: center;'>{{{status}}} {{title}}</ez-feedback-test-header>
                        <ez-icon-action class='collapsible-dropdown-icon'>{{{expandIcon}}}</ez-icon-action>
                    </div>
                    <div class="collapsible-body">
                        {{#exception}}
                            {{msg}}
                            <pre>{{error}}</pre>
                        {{/exception}}
                        {{#checks}}
                            <ez-feedback-check class='{{#pass}}pass{{/pass}} {{#fail}}fail{{/fail}}'>
                                {{{status}}}
                                {{title}}{{#title}}<br>{{/title}}
                                {{feedback}}
                                {{^title}}{{^feedback}}¯\_(ツ)_/¯{{/feedback}}{{/title}}
                            </ez-feedback-check>
                        {{/checks}}
                        <ez-feedback-data>
                            {{#files}}
                                {{msg}}
                                <pre>{{value}}</pre>
                            {{/files}}
                            {{#inputs}}
                                {{msg}}
                                <pre>{{value}}</pre>
                            {{/inputs}}
                            {{#outputs}}
                                {{msg}}
                                <pre>{{value}}</pre>
                            {{/outputs}}
                        </ez-feedback-data>
                    </div>
                </li>
            {{/tests}}
        </ul>
        <ez-feedback-producer>{{{producerIcon}}}{{producer}}</ez-feedback-producer>
    """.trimIndent(),
        "preEvalError" to this.pre_evaluate_error,
        "tests" to tests.map {
            mapOf(
                "pass" to (it.status == V3Status.PASS),
                "fail" to (it.status == V3Status.FAIL),
                "status" to it.status.mapToIcon(),
                "title" to it.title,
                "exception" to if (it.exception_message != null) mapOf(
                    "msg" to Str.autogradeException,
                    "error" to it.exception_message
                ) else null,

                "checks" to if (it.checks.isEmpty() && it.exception_message == null)
                    mapOf(
                        "pass" to true,
                        "status" to V3Status.PASS.mapToIcon(),
                        "title" to Str.autogradeNoChecksInTest,
                    )
                else
                    it.checks.map {
                        mapOf(
                            "pass" to (it.status == V3Status.PASS),
                            "fail" to (it.status == V3Status.FAIL),
                            "status" to it.status.mapToIcon(),
                            "title" to it.title,
                            "feedback" to it.feedback,
                        )
                    },

                "files" to if (!it.created_files.isNullOrEmpty()) mapOf(
                    "msg" to Str.autogradeCreatedFiles,
                    "value" to it.created_files.joinToString("\n") {
                        val content = it.content.split("\n").joinToString("\n") { "  $it" }
                        "  --- ${it.name} ---\n$content"
                    },
                ) else null,

                "inputs" to if (!it.user_inputs.isNullOrEmpty()) mapOf(
                    "msg" to Str.autogradeStdIn,
                    "value" to it.user_inputs.joinToString("\n") { "  $it" },
                ) else null,

                "outputs" to if (!it.actual_output.isNullOrBlank()) mapOf(
                    "msg" to Str.autogradeStdOut,
                    "value" to it.actual_output.trim('\n').split("\n").joinToString("\n") { "  $it" },
                ) else null,
            )
        },
        "expandIcon" to Icons.expandCaret,
        "producerIcon" to Icons.lahendus,
        "producer" to producer,
    )

    private fun V3Status.mapToIcon() = when (this) {
        V3Status.PASS -> Icons.check
        V3Status.FAIL -> Icons.close
        V3Status.SKIP -> Icons.dotsHorizontal
    }
}