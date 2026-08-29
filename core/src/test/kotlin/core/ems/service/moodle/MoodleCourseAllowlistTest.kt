package core.ems.service.moodle

import core.exception.InvalidRequestException
import core.exception.ReqError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

/**
 * The allowlist that decides whether this environment may contact Moodle about a given course.
 *
 * It had no test at all, which is the wrong shape of gap for this particular class: it is the lock
 * that makes "point dev at a real Moodle" a safe thing to do, and the two configurations it exists
 * to tell apart — an empty setting meaning *unrestricted*, and a populated one meaning *these and
 * nothing else* — differ by one character in a YAML file. A guard nobody has watched say no has not
 * been shown to work, and here saying no wrongly is silent: the failure is a request that leaves for
 * a real gradebook, not an exception anybody sees.
 *
 * The fail-open default is deliberate and is pinned below rather than merely inherited. Production
 * wants it: it syncs whatever teachers have linked, and an allowlist there would be a list nobody
 * maintains. It does mean every way of writing "nothing useful" — empty, blank, a stray comma —
 * lands on *allow everything*, so those spellings are asserted too. They are the plausible typos,
 * and each one is a config that looks restrictive and is not.
 *
 * Unit, not integration: `@Value` binding is Spring's and tested by Spring. What can be got wrong
 * here is the splitting and the comparison, so the field is set directly and the parsing exercised
 * against it.
 */
class MoodleCourseAllowlistTest {

    private fun allowlistOf(raw: String) = MoodleCourseAllowlist().also {
        ReflectionTestUtils.setField(it, "raw", raw)
    }

    // --- empty means unrestricted, in every spelling ----------------------------------------------

    @Test
    fun `empty allows everything`() {
        val allowlist = allowlistOf("")
        assertTrue(allowlist.isAllowed("anything"))
        assertTrue(allowlist.isAllowed("LTAT.03.001"))
    }

    @Test
    fun `blank and comma-only are also unrestricted`() {
        // Not an endorsement — it is what the filter does, and someone who writes "," believing they
        // have restricted something should find that spelled out in a test rather than in a
        // gradebook.
        assertTrue(allowlistOf("   ").isAllowed("anything"))
        assertTrue(allowlistOf(",").isAllowed("anything"))
        assertTrue(allowlistOf(" , , ").isAllowed("anything"))
    }

    // --- populated means these and nothing else ---------------------------------------------------

    @Test
    fun `a single entry allows that course and refuses others`() {
        val allowlist = allowlistOf("throwaway-course")
        assertTrue(allowlist.isAllowed("throwaway-course"))
        assertFalse(allowlist.isAllowed("some-other-course"))
        assertFalse(allowlist.isAllowed(""))
    }

    @Test
    fun `entries are split on commas and trimmed`() {
        val allowlist = allowlistOf(" first ,second,  third  ")
        assertTrue(allowlist.isAllowed("first"))
        assertTrue(allowlist.isAllowed("second"))
        assertTrue(allowlist.isAllowed("third"))
        assertFalse(allowlist.isAllowed("fourth"))
    }

    @Test
    fun `a blank entry among real ones does not open it up`() {
        // The dangerous version of the comma case above: "course," reads as restrictive at a glance,
        // and would be unrestricted if the empty fragment survived the filter as a member.
        val allowlist = allowlistOf("throwaway-course,")
        assertTrue(allowlist.isAllowed("throwaway-course"))
        assertFalse(allowlist.isAllowed("some-other-course"))
    }

    @Test
    fun `matching is case-sensitive`() {
        // Documented rather than desired. Moodle shortnames are matched exactly against
        // `course.moodle_short_name`, so a case difference presents as "not allowed on this
        // environment" — which reads like the allowlist is missing an entry rather than like a typo.
        val allowlist = allowlistOf("Throwaway-Course")
        assertTrue(allowlist.isAllowed("Throwaway-Course"))
        assertFalse(allowlist.isAllowed("throwaway-course"))
    }

    // --- the throwing form, which is the one the call sites rely on -------------------------------

    @Test
    fun `assertAllowed passes a listed course through`() {
        allowlistOf("throwaway-course").assertAllowed("throwaway-course")
    }

    @Test
    fun `assertAllowed refuses an unlisted course`() {
        val e = assertThrows(InvalidRequestException::class.java) {
            allowlistOf("throwaway-course").assertAllowed("real-course")
        }
        assertEquals(ReqError.MOODLE_LINKING_ERROR, e.code)
        // notify=false on purpose: a refusal here is the configuration working, not an incident, and
        // the sync endpoints are reachable by hand — so an alert per click would train people to
        // ignore the alert.
        assertFalse(e.notify)
        assertTrue(e.message.contains("real-course"))
    }

    @Test
    fun `assertAllowed refuses nothing when unrestricted`() {
        allowlistOf("").assertAllowed("any-course-at-all")
    }
}
