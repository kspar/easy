package pages.course_exercise

import EzDate
import EzDateSerializer
import Icons
import components.ButtonComp
import components.IconButtonComp
import debug
import hide
import kotlinx.serialization.Serializable
import libheaders.Materialize
import parseTo
import rip.kspar.ezspa.*
import show
import template
import translation.Str

class ExerciseAutoFeedbackComp(
    var autoFeedback: String?,
    var failed: Boolean,
    var canRetry: Boolean,
    parent: Component,
) : Component(parent) {


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

    private var rerunTestsBtn: IconButtonComp? = null
    private var expandTestsBtn: ButtonComp? = null
    private var shrinkTestsBtn: ButtonComp? = null


    override val children
        get() = listOfNotNull(rerunTestsBtn, expandTestsBtn, shrinkTestsBtn)

    // TODO: if tests are added to the exercise after submission, we should be able to run them here
    override fun create() = doInPromise {
        val isV3 = parseAutofeedback() != null

        // TODO: retry not implemented
        canRetry = false

        if (canRetry)
            rerunTestsBtn = IconButtonComp(
                Icons.replay, Str.runAutoTests,
                size = IconButtonComp.Size.SMALL,
                onClick = {
                    // TODO: run, await, update (+ also in students list)
                },
                parent = this
            )

        if (isV3) {

            expandTestsBtn = ButtonComp(
                ButtonComp.Type.TEXT, Str.showTestDetails, Icons.expandCaret, trailingIcon = true,
                onClick = {
                    toggleTests(true)
                },
                parent = this
            )
            shrinkTestsBtn = ButtonComp(
                ButtonComp.Type.TEXT, Str.hideTestDetails, Icons.shrinkCaret, trailingIcon = true,
                onClick = {
                    toggleTests(false)
                },
                parent = this
            )
        }
//        }
    }

    override fun render(): String {
        val parsedV3 = parseAutofeedback()
        val autoFeedbackV3Html = parsedV3?.renderV3Html()
        return template(
            """
                <ez-feedback>
                    <ez-tests-heading>
                        <h5 style='margin: 1.5rem .5rem 1.5rem 0;'>{{autoTitle}}</h5>
                        <ez-run-tests-btn>${rerunTestsBtn.dstIfNotNull()}</ez-run-tests-btn>
                    </ez-tests-heading>
                    
                    {{#isV3}}
                        <ez-flex style='column-gap: 1rem;'>
                            {{#tests}}
                                <ez-test-part style='height: .5rem; margin-bottom: 1.5rem; flex-grow: 1;' class='{{class}}'></ez-test-part>
                            {{/tests}}
                        </ez-flex>
                        {{{okV3Html}}}
                        <div style='display: flex; align-items: center; justify-content: center;'>
                            $expandTestsBtn
                            $shrinkTestsBtn
                        </div>
                    {{/isV3}}
                    
                    {{#failed}}
                        <pre class="feedback auto-feedback" style='background-color: unset;'>{{failMsg}}</pre>
                    {{/failed}}
                    {{#isRaw}}
                        <pre class="feedback auto-feedback">{{rawFeedback}}</pre>
                    {{/isRaw}}
                </ez-feedback>
            """.trimIndent(),
            "autoTitle" to Str.autoAssessmentLabel,
            "failed" to failed,
            "failMsg" to Str.autogradeFailedMsg,
            "isRaw" to (parsedV3 == null),
            "isV3" to (parsedV3 != null),
            "rawFeedback" to autoFeedback,
            "okV3Html" to autoFeedbackV3Html,
            "tests" to parsedV3?.tests?.map {
                mapOf(
                    "class" to (when (it.status) {
                        V3Status.PASS -> "pass"
                        V3Status.FAIL -> "fail"
                        V3Status.SKIP -> "skip"
                    })
                )
            },
        )
    }

    override fun postRender() {
        toggleTests(false)

        // If there's an error or we don't have expandable tests for some other reason, then don't show toggle at all
        parseAutofeedback()?.let {
            if (it.tests.isEmpty())
                showAllWithoutToggle()
        }

        val collapsibleElem = rootElement.getElemsByClass("ez-collapsible").singleOrNull()
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
            <ez-feedback-v3>
                {{#preEvalError}}
                    <pre style='margin: 0; padding: 1rem; background: var(--ez-bg-light-contrast); '>{{preEvalError}}</pre>
                {{/preEvalError}}
                {{^preEvalError}}
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
                {{/preEvalError}}
                <ez-feedback-producer>{{{producerIcon}}}{{producer}}</ez-feedback-producer>
            </ez-feedback-v3>
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

    private fun toggleTests(showExpanded: Boolean) {
        expandTestsBtn?.show(!showExpanded)
        shrinkTestsBtn?.show(showExpanded)
        rootElement.getElemBySelectorOrNull("ez-feedback-v3")?.show(showExpanded)
    }

    private fun showAllWithoutToggle() {
        toggleTests(true)
        shrinkTestsBtn?.hide()
    }
}