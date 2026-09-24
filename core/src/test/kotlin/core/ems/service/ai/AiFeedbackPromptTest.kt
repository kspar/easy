package core.ems.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The prompt is the feature. A model given the wrong context gives a wrong explanation just as
 * fluently as a right one, so what goes in — and what is kept out — is asserted here, on the text,
 * before any provider sees it.
 */
class AiFeedbackPromptTest {

    private val okV3 = """{"result_type":"OK_V3","producer":"tiivad","pre_evaluate_error":null,"points":40,""" +
            """"tests":[{"title":"Sums two numbers","status":"PASS","user_inputs":["1","2"],"actual_output":"3","checks":[]},""" +
            """{"title":"Handles negatives","status":"FAIL","user_inputs":["-1","2"],"actual_output":"3",""" +
            """"exception_message":null,"checks":[{"title":"output","status":"FAIL","feedback":"Expected 1"}]}]}"""

    private fun context(
        feedback: String? = okV3,
        language: String = "et",
        solution: String = "a = int(input())\nb = int(input())\nprint(abs(a) + b)",
        text: String? = "Read two integers and print their sum.",
    ) = AiFeedbackPrompt.Context(
        exerciseTitle = "Sum of two numbers",
        exerciseText = text,
        solutionFileName = "sum.py",
        solution = solution,
        grade = 40,
        autogradeFeedback = feedback,
        language = language,
    )

    @Test
    fun `the whole feedback string goes in verbatim, passing tests included`() {
        val built = AiFeedbackPrompt.build(context())
        assertTrue(built.user.contains(okV3)) { "The grader feedback was altered on its way into the prompt:\n${built.user}" }
        assertTrue(built.user.contains("Sums two numbers")) { "Passing tests are context and belong in the prompt" }
    }

    @Test
    fun `exercise, file name, solution and points are all in the user message`() {
        val built = AiFeedbackPrompt.build(context())
        for (needle in listOf("Sum of two numbers", "Read two integers", "sum.py", "print(abs(a) + b)", "40 points")) {
            assertTrue(built.user.contains(needle)) { "Missing '$needle' in:\n${built.user}" }
        }
    }

    @Test
    fun `the system prompt forbids the solution and caps the length`() {
        val system = AiFeedbackPrompt.build(context()).system
        assertTrue(system.contains("Never give the solution"))
        assertTrue(system.contains("At most 5 sentences"))
        assertTrue(system.contains("at most one small hint"))
    }

    @Test
    fun `language switches the reply instruction and nothing else`() {
        val et = AiFeedbackPrompt.build(context(language = "et"))
        val en = AiFeedbackPrompt.build(context(language = "en"))
        assertTrue(et.system.contains("Reply in Estonian."))
        assertTrue(en.system.contains("Reply in English."))
        assertFalse(en.system.contains("Estonian"))
        assertEquals(et.user, en.user)
    }

    @Test
    fun `an unknown language is English`() {
        assertTrue(AiFeedbackPrompt.build(context(language = "xx")).system.contains("Reply in English."))
    }

    @Test
    fun `missing feedback and missing exercise text are said, not skipped`() {
        val built = AiFeedbackPrompt.build(context(feedback = null, text = null))
        assertTrue(built.user.contains("(the grader produced no feedback)"))
        assertTrue(built.user.contains("(no exercise text)"))
    }

    @Test
    fun `long feedback is cut from the middle so both ends survive`() {
        val head = "HEAD-" + "h".repeat(AiFeedbackPrompt.FEEDBACK_CAP)
        val tail = "t".repeat(AiFeedbackPrompt.FEEDBACK_CAP) + "-TAIL"
        val built = AiFeedbackPrompt.build(context(feedback = head + tail))
        assertTrue(built.user.contains("HEAD-"))
        assertTrue(built.user.contains("-TAIL"))
        assertTrue(built.user.contains(AiFeedbackPrompt.TRUNCATED_MARKER))
        assertTrue(built.user.length < head.length + tail.length) { "Nothing was cut" }
    }

    @Test
    fun `a long solution is cut from the end`() {
        val solution = "x".repeat(AiFeedbackPrompt.SOLUTION_CAP + 100) + "END"
        val built = AiFeedbackPrompt.build(context(solution = solution))
        assertFalse(built.user.contains("END"))
        assertTrue(built.user.contains(AiFeedbackPrompt.TRUNCATED_MARKER))
    }

    @Test
    fun `the audit form carries both halves`() {
        val built = AiFeedbackPrompt.build(context())
        val audit = built.forAudit()
        assertTrue(audit.contains(built.system))
        assertTrue(audit.contains(built.user))
    }
}
