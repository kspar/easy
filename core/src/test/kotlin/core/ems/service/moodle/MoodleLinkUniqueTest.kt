package core.ems.service.moodle

import core.db.Course
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * One Moodle course, one course (EZ-1877).
 *
 * `PUT /courses/{id}/moodle` used to write whatever short name it was given. Two courses pointing at
 * the same Moodle course is not an error anywhere downstream — it is two nightly student syncs and
 * two grade syncs against one Moodle course from two rosters, which nobody has ever meant. The
 * endpoint now refuses it, and changeset 020926-1 puts a unique constraint under it for the race
 * the endpoint's own check cannot see.
 *
 * Both halves are tested, and the constraint by a write that must fail: a guard that has never been
 * seen to refuse anything is indistinguishable from one that is not there.
 */
@IntegrationTest
class MoodleLinkUniqueTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)
    private val admin = Auth.ADMIN_ID

    private var holderId = 0L
    private var otherId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin)
            holderId = Fixtures.course("Algorithms", moodleShortName = "MOODLE-ALGO", moodleSyncStudents = true)
            otherId = Fixtures.course("Algorithms (copy)")
        }
    }

    private fun link(courseId: Long, shortName: String?, force: Boolean = false, syncStudents: Boolean = true) =
        api.put(
            "/v2/courses/$courseId/moodle",
            api.body(
                "moodle_props" to shortName?.let {
                    mapOf("moodle_short_name" to it, "sync_students" to syncStudents, "sync_grades" to false)
                },
                "force" to force,
            ),
            Auth.asAdmin(admin),
        )

    private fun shortNameOf(courseId: Long): String? = transaction {
        Course.select(Course.moodleShortName).where { Course.id eq courseId }.single()[Course.moodleShortName]
    }

    @Test
    fun `a Moodle course another course holds is refused, naming that course`() {
        val refused = link(otherId, "MOODLE-ALGO")

        assertEquals("MOODLE_COURSE_ALREADY_LINKED", refused.errorCode) { refused.body }
        // The holder is named by id and title, because the admin's next step is to go and unlink
        // *that* course, and the short name alone does not say which one it is.
        val attrs = refused.jsonOrNull?.get("attrs")
        assertEquals(holderId.toString(), attrs?.get("course_id")?.asString()) { refused.body }
        assertEquals("Algorithms", attrs?.get("course_title")?.asString()) { refused.body }

        assertEquals(null, shortNameOf(otherId)) { "The refused link was written anyway." }
        assertEquals("MOODLE-ALGO", shortNameOf(holderId)) { "The refusal touched the course that held the name." }
    }

    @Test
    fun `force does not get past it`() {
        // `force` skips the sync locks — that is all it has ever meant. A flag that also skipped the
        // data model would make the UI's "sync in progress, try again" path a way to corrupt state.
        val refused = link(otherId, "MOODLE-ALGO", force = true)
        assertEquals("MOODLE_COURSE_ALREADY_LINKED", refused.errorCode) { refused.body }
        assertEquals(null, shortNameOf(otherId))
    }

    @Test
    fun `a course may keep its own short name while its sync flags change`() {
        // The check excludes the course being written, or toggling a switch on the Moodle panel —
        // which re-sends the current short name — would refuse itself.
        val saved = link(holderId, "MOODLE-ALGO", syncStudents = false)
        assertEquals(200, saved.status) { saved.body }
        assertEquals("MOODLE-ALGO", shortNameOf(holderId))
    }

    @Test
    fun `once the holder is unlinked, the name is free`() {
        assertEquals(200, link(holderId, null).status)

        val linked = link(otherId, "MOODLE-ALGO")
        assertEquals(200, linked.status) { linked.body }
        assertEquals("MOODLE-ALGO", shortNameOf(otherId))
    }

    @Test
    fun `two unlinked courses do not collide on their NULLs`() {
        // Postgres treats NULLs as distinct in a unique constraint. If that ever stopped being true —
        // `NULLS NOT DISTINCT` is one keyword away — every second unlink in the system would fail.
        assertEquals(200, link(holderId, null).status)
        assertEquals(null, shortNameOf(holderId))
        assertEquals(null, shortNameOf(otherId))
        transaction { Fixtures.course("A third unlinked course") }
    }

    @Test
    fun `the constraint itself refuses a duplicate that bypasses the endpoint`() {
        // The positive case for changeset 020926-1. Straight into the table, the way a race between
        // two requests would arrive after both had passed the endpoint's check.
        val e = assertThrows<ExposedSQLException> {
            transaction { Fixtures.course("Smuggled in", moodleShortName = "MOODLE-ALGO") }
        }
        assertTrue(e.message?.contains("uq_course_moodle_short_name") == true) {
            "Refused, but not by the constraint this test is about: ${e.message}"
        }
    }
}
