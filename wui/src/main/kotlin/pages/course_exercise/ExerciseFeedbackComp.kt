package pages.course_exercise

import Str
import dao.CourseExercisesStudentDAO
import debug
import pages.exercise.OkV3
import pages.exercise.V3Status
import parseTo
import rip.kspar.ezspa.Component
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
                    {{#hasAutoFeedback}}
                        <h5>{{autoTitle}}</h5>
                        {{#failed}}
                            <pre class="feedback auto-feedback" style='background-color: unset;'>{{failMsg}}</pre>
                        {{/failed}}
                        {{#isRaw}}
                            <pre class="feedback auto-feedback">{{rawFeedback}}</pre>
                        {{/isRaw}}
                        {{#isV3}}
                            <pre class="feedback auto-feedback">{{{okV3Html}}}</pre>
                        {{/isV3}}
                    {{/hasAutoFeedback}}
                    {{#teacherFeedback}}
                        <h5>{{title}}</h5>
                        <pre class="feedback">{{feedback}}</pre>
                    {{/teacherFeedback}}
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

    private fun parseAutofeedback(): OkV3? =
        try {
            autoFeedback?.parseTo(OkV3.serializer())
                .also { debug { "Feedback is in OK_V3 format" } }
        } catch (e: Exception) {
            debug { e }
            debug { "Feedback is not in OK_V3 format, falling back to raw" }
            null
        }

    private fun OkV3.renderV3Html(): String {
        return renderV3(this)
    }

    private fun renderV3(okv3: OkV3) = okv3.tests.joinToString("\n\n") {
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

    private fun mapStatus(status: V3Status): String =
        when (status) {
            V3Status.PASS -> "[✓]"
            V3Status.FAIL -> "[x]"
            V3Status.SKIP -> "[-]"
        }
}