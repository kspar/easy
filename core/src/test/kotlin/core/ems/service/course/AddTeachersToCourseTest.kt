package core.ems.service.course

import core.db.Account
import core.db.TeacherCourseAccess
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * `POST /v2/courses/{id}/teachers` when some of the addresses have no account — which is how this
 * endpoint is used, because teachers add a course's staff by pasting a list.
 *
 * It used to resolve the addresses inside a `map` and throw from the first miss, so a paste of
 * thirty lines with three unknown addresses reported one of them and said nothing about the other
 * two. A teacher reported the consequence rather than the cause (EZ-1830): eight 400s in a minute
 * while bisecting a list by hand, because each attempt could only ever name one bad line.
 *
 * The web dialog that shows this reads `attrs.emails`, so the attribute is part of the contract and
 * not a detail of the log message — `participants-add-teachers.spec.mjs` is the other half.
 *
 * The rejection stays all-or-nothing. Partially applying a batch and reporting what was skipped is
 * the other defensible design, and a worse one here: the caller cannot see which half went through
 * without re-reading the roster, and re-submitting a corrected list would then double-add.
 */
@IntegrationTest
class AddTeachersToCourseTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private var courseId = 0L

    /** Exists, and is not yet on the course. */
    private val known = "uus-opetaja@example.test"

    @BeforeEach
    fun setUp() {
        transaction {
            Fixtures.teacher(Auth.TEACHER_ID)
            Fixtures.teacher("uus-opetaja")
            courseId = Fixtures.course("Programmeerimine")
            Fixtures.enrolTeacher(courseId, Auth.TEACHER_ID)
        }
    }

    private fun add(vararg emails: String) = api.post(
        "/v2/courses/$courseId/teachers",
        api.body("teachers" to emails.map { mapOf("email" to it) }),
        Auth.asTeacher(),
    )

    private fun accessCount() = transaction {
        TeacherCourseAccess.selectAll().count()
    }

    @Test
    fun `every unknown address is reported, not just the first`() {
        val before = accessCount()

        val resp = add(known, "kadri@example.test", "urmas@example.test")

        assertEquals(400, resp.status)
        assertEquals("ACCOUNT_EMAIL_NOT_FOUND", resp.errorCode)

        val reported = resp.jsonOrNull?.get("attrs")?.get("emails")?.asString()
        assertTrue(
            reported != null && reported.contains("kadri@example.test"),
            "attrs.emails should name the first unknown address, was: $reported",
        )
        assertTrue(
            reported!!.contains("urmas@example.test"),
            "attrs.emails should name the second one too — the whole point of EZ-1830, was: $reported",
        )
        assertFalse(
            reported.contains(known),
            "the address that does resolve must not be blamed, was: $reported",
        )

        assertEquals(before, accessCount(), "a rejected batch must add nobody, not even the known address")
    }

    @Test
    fun `one unknown address still carries the singular attribute`() {
        // `attrs.email` is what the message read before there could be several, and a client that
        // only knows it still renders the address rather than a generic failure. Dropping it would
        // regress the single-address case during any window where the two halves differ in version.
        val resp = add("kadri@example.test")

        assertEquals(400, resp.status)
        assertEquals("kadri@example.test", resp.jsonOrNull?.get("attrs")?.get("email")?.asString())
        assertEquals("kadri@example.test", resp.jsonOrNull?.get("attrs")?.get("emails")?.asString())
    }

    @Test
    fun `several unknown addresses do not carry the singular attribute`() {
        // Otherwise a client preferring `email` would name one address out of three and read as if
        // the rest were fine.
        val resp = add("kadri@example.test", "urmas@example.test")

        assertNull(resp.jsonOrNull?.get("attrs")?.get("email"))
    }

    @Test
    fun `a list core can resolve is added`() {
        val resp = add(known)

        assertEquals(200, resp.status)
        assertEquals("1", resp.field("accesses_added"))
        assertEquals(2L, accessCount(), "the caller's own access, plus the one just granted")
    }

    /**
     * EZ-1863. `account.email` is only ever written lowercase (`account_checkin.kt`) and is a plain
     * `text` column, so an exact `eq` made every capitalised spelling unresolvable — a teacher
     * pasting an address as their mail client displays it was told, correctly formatted and quite
     * wrongly, that no such user exists.
     */
    @Test
    fun `an address is matched however it is capitalised`() {
        val resp = add("Uus-Opetaja@Example.Test")

        assertEquals(200, resp.status, "capitals in an address are not a different address")
        assertEquals("1", resp.field("accesses_added"))
        assertEquals(2L, accessCount())
    }

    @Test
    fun `an address pasted with surrounding whitespace is matched`() {
        val resp = add("  $known\t")

        assertEquals(200, resp.status)
        assertEquals("1", resp.field("accesses_added"))
    }

    /**
     * The other half of matching case-insensitively: two spellings of one address are one teacher.
     * Deduplicating on the raw string let both through to `TeacherCourseAccess.batchInsert`, whose
     * primary key is (course, teacher) — a 500 rather than the 400 that used to hide it.
     */
    @Test
    fun `two spellings of one address add one teacher`() {
        val resp = add(known, known.uppercase())

        assertEquals(200, resp.status)
        assertEquals("1", resp.field("accesses_added"), "the same person twice is one access")
        assertEquals(2L, accessCount(), "the caller's own access, plus the one just granted")
    }

    /**
     * The other direction, and the reason the *column* is lowered rather than only the input.
     * `account.email` has been written lowercase since 2019 (`67913654`), so a row with a capital in
     * it predates that or was written around it — and lowering only the input would make such a row
     * unreachable by every possible input, having previously been reachable by typing its stored
     * spelling exactly. Nobody knows the stored spelling; everybody knows their own address.
     */
    @Test
    fun `an address stored with capitals is found by the lowercase spelling`() {
        transaction {
            Account.update({ Account.id eq "uus-opetaja" }) { it[email] = "Uus-Opetaja@Example.Test" }
        }

        val resp = add(known)

        assertEquals(200, resp.status)
        assertEquals("1", resp.field("accesses_added"))
    }

    @Test
    fun `an unknown address is still reported as the teacher typed it`() {
        // Normalising is for the lookup, not for the message: a teacher scanning their paste for the
        // line to fix is looking for what they wrote, not a lowercased version of it.
        val resp = add("Kadri@Example.Test")

        assertEquals(400, resp.status)
        assertEquals("ACCOUNT_EMAIL_NOT_FOUND", resp.errorCode)
        assertEquals("Kadri@Example.Test", resp.jsonOrNull?.get("attrs")?.get("email")?.asString())
    }
}
