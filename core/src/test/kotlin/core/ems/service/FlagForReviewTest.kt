package core.ems.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EZ-1926. The one bit core reads out of a grader's result besides the grade.
 *
 * The integration test proves the flag lands on the submission; this pins what counts as asking
 * for it. The refusals matter more than the acceptance: a stored feedback string is whatever the
 * container printed, so this is parsing untrusted text, and the wrong answer here is a flag on a
 * student that no grader and no teacher set.
 */
class FlagForReviewTest {

    private fun okV3(extra: String = "") =
        """{"result_type":"OK_V3","producer":"t","pre_evaluate_error":null,"points":0,"tests":[]$extra}"""

    @Test
    fun `true raises it`() {
        assertTrue(feedbackFlagsForReview(okV3(""","flag_for_review":true""")))
    }

    @Test
    fun `absent and false do not`() {
        assertFalse(feedbackFlagsForReview(okV3()))
        assertFalse(feedbackFlagsForReview(okV3(""","flag_for_review":false""")))
        assertFalse(feedbackFlagsForReview(okV3(""","flag_for_review":null""")))
    }

    @Test
    fun `only the JSON boolean counts`() {
        // A grader that prints "true" has not met the format. bin/okv3-check tells it so; core
        // does not guess.
        assertFalse(feedbackFlagsForReview(okV3(""","flag_for_review":"true"""")))
        assertFalse(feedbackFlagsForReview(okV3(""","flag_for_review":1""")))
    }

    @Test
    fun `not an OK_V3 document, not a flag`() {
        assertFalse(feedbackFlagsForReview(null))
        assertFalse(feedbackFlagsForReview(""))
        assertFalse(feedbackFlagsForReview("3 of 3 tests passed"))
        assertFalse(feedbackFlagsForReview("""{"flag_for_review":true}"""))
        assertFalse(feedbackFlagsForReview("""{"result_type":"OK_LEGACY","flag_for_review":true}"""))
        assertFalse(feedbackFlagsForReview("""[true]"""))
        assertFalse(feedbackFlagsForReview("""{"result_type":"OK_V3","flag_for_review":true"""))
    }

    @Test
    fun `the field is top-level, not on a test`() {
        assertFalse(feedbackFlagsForReview(
            """{"result_type":"OK_V3","points":0,"tests":[{"title":"t","status":"FAIL","flag_for_review":true}]}"""
        ))
    }
}
