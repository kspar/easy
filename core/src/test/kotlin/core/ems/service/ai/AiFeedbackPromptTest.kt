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
    fun `every test goes in, and the failing ones go in whole`() {
        val built = AiFeedbackPrompt.build(context())
        assertTrue(built.user.contains("Sums two numbers")) { "Passing tests are context and belong in the prompt" }
        assertTrue(built.user.contains("\"status\":\"PASS\""))
        // The failing test keeps everything the model needs to explain it.
        for (needle in listOf("Handles negatives", "\"user_inputs\":[\"-1\",\"2\"]", "\"actual_output\":\"3\"", "Expected 1")) {
            assertTrue(built.user.contains(needle)) { "Missing '$needle' in:\n${built.user}" }
        }
    }

    @Test
    fun `passing tests lose their output, the rest is untouched`() {
        val trimmed = AiFeedbackPrompt.trimPassingTests(okV3)
        // The passing test's output is gone, the failing test's output is not.
        assertFalse(trimmed.contains("\"actual_output\":\"3\",\"checks\":[]")) { trimmed }
        assertTrue(trimmed.contains("\"title\":\"Sums two numbers\",\"status\":\"PASS\",\"user_inputs\":[\"1\",\"2\"],\"checks\":[]")) { trimmed }
        assertTrue(trimmed.contains("\"status\":\"FAIL\",\"user_inputs\":[\"-1\",\"2\"],\"actual_output\":\"3\"")) { trimmed }
        assertTrue(trimmed.contains("\"points\":40"))

        // A passing test with the large fields present loses all three.
        val heavy = """{"result_type":"OK_V3","tests":[{"title":"t","status":"PASS","actual_output":"OUT","converted_submission":"CONV","created_files":[{"name":"f","content":"FILE"}]}]}"""
        val light = AiFeedbackPrompt.trimPassingTests(heavy)
        for (gone in listOf("OUT", "CONV", "FILE")) assertFalse(light.contains(gone)) { light }
        assertTrue(light.contains("\"title\":\"t\",\"status\":\"PASS\""))
    }

    @Test
    fun `anything that is not OK_V3 goes in as it is`() {
        for (raw in listOf("Test 1: FAIL\nExpected 1, got 3\n", "{not json", """{"result_type":"OTHER","tests":[{"status":"PASS","actual_output":"x"}]}""", """{"result_type":"OK_V3"}""")) {
            assertEquals(raw, AiFeedbackPrompt.trimPassingTests(raw))
        }
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
