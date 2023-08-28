package pages.course_exercise

import Icons
import Str
import dao.CourseExercisesStudentDAO
import debug
import libheaders.Materialize
import pages.exercise.OkV3
import pages.exercise.V3Status
import parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.getElemsByClass
import template

class ExerciseFeedbackComp(
    var validGrade: CourseExercisesStudentDAO.ValidGrade?,
    var autoFeedback: String?,
    var teacherFeedback: String?,
    var failed: Boolean = false,
    parent: Component
) : Component(parent) {

    companion object {
        const val autogradeFailedMsg = """

           ¯\_(ツ)_/¯
           
Automaatne testimine ebaõnnestus.
Kedagi on probleemist ilmselt juba teavitatud, 
ole hea ja proovi hiljem uuesti.
        """
    }

    override fun render(): String {
        val parsedV3 = parseAutofeedback()
        val autoFeedbackV3Html = parsedV3?.renderV3Html()
        return template(
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
                    "label" to Str.gradeLabel(),
                    "points" to it.grade.toString(),
                    "icon" to it.grader_type.icon(),
                )
            },
            "hasAutoFeedback" to (autoFeedback != null || failed),
            "autoTitle" to Str.autoAssessmentLabel(),
            "failed" to failed,
            "failMsg" to autogradeFailedMsg,
            "isRaw" to (autoFeedbackV3Html == null),
            "isV3" to (autoFeedbackV3Html != null),
            "rawFeedback" to autoFeedback,
            "okV3Html" to autoFeedbackV3Html,
            "teacherFeedback" to teacherFeedback?.let {
                mapOf(
                    "title" to Str.teacherAssessmentLabel(),
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
                    </div>
                    <div class="collapsible-body">
                        {{#exception}}
                            {{msg}}
                            <pre>{{error}}</pre>
                        {{/exception}}
                        {{#checks}}
                            <ez-feedback-check class='{{#pass}}pass{{/pass}} {{#fail}}fail{{/fail}}'>
                                {{{status}}} {{feedback}}
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
                    "msg" to "Programmi käivitamisel tekkis viga:",
                    "error" to it.exception_message
                ) else null,

                "checks" to it.checks.map {
                    mapOf(
                        "pass" to (it.status == V3Status.PASS),
                        "fail" to (it.status == V3Status.FAIL),
                        "status" to it.status.mapToIcon(),
                        "feedback" to it.feedback,
                    )
                },

                "files" to if (it.created_files.isNotEmpty()) mapOf(
                    "msg" to "Enne programmi käivitamist lõin failid:",
                    "value" to it.created_files.joinToString("\n") {
                        val content = it.content.split("\n").joinToString("\n") { "  $it" }
                        "  --- ${it.name} ---\n$content"
                    },
                ) else null,

                "inputs" to if (it.user_inputs.isNotEmpty()) mapOf(
                    "msg" to "Andsin programmile sisendid:",
                    "value" to it.user_inputs.joinToString("\n") { "  $it" },
                ) else null,

                "outputs" to if (it.actual_output != null) mapOf(
                    "msg" to "Programmi täielik väljund oli:",
                    "value" to it.actual_output.trim('\n').split("\n").joinToString("\n") { "  $it" },
                ) else null,
            )
        },
        "producerIcon" to Icons.lahendus,
        "producer" to "TSL / $producer",
    )

    private fun V3Status.mapToIcon() = when (this) {
        V3Status.PASS -> Icons.check
        V3Status.FAIL -> Icons.close
        V3Status.SKIP -> Icons.dotsHorizontal
    }

    private fun OkV3.renderV3Text() = this.tests.joinToString("\n\n") {
        val testTitle = "${mapStatus(it.status)} ${it.title}"
        val testContent = it.exception_message ?: run {

            val checks = it.checks.joinToString("\n") {
                "  ${mapStatus(it.status)} ${it.feedback}"
            }

            val files = if (it.created_files.isNotEmpty())
                "  Enne programmi jooksutamist lõin need failid:\n" +
                        it.created_files.joinToString("\n") {
                            val content = it.content.split("\n").joinToString("\n") { "    $it" }
                            "    --- ${it.name} ---\n$content"
                        }
            else null

            val inputs = if (it.user_inputs.isNotEmpty())
                "  Andsin programmile need sisendid:\n" +
                        it.user_inputs.joinToString("\n") { "    $it" }
            else null

            val output = if (it.actual_output != null)
                "  Programmi täielik väljund oli:\n" + it.actual_output.split("\n").joinToString("\n") { "    $it" }
            else null

            listOfNotNull(checks, files, inputs, output).joinToString("\n\n")
        }

        "$testTitle\n$testContent"
    }

    private fun mapStatus(status: V3Status): String =
        when (status) {
            V3Status.PASS -> "[✓]"
            V3Status.FAIL -> "[x]"
            V3Status.SKIP -> "[-]"
        }
}