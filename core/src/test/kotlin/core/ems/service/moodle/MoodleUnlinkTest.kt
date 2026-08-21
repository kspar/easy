package core.ems.service.moodle

import core.db.Course
import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingAccess
import core.db.StudentMoodlePendingCourseGroup
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * What unlinking a course from Moodle does to the invitations it has outstanding.
 *
 * **This is EZ-1780.** Unlinking set `moodle_short_name = null` and deleted nothing, and
 * `JoinMoodleLinkedCourseByInvite` looked its invite up by **invite id alone** — no predicate on the
 * course still being linked. So an outstanding Moodle invite kept working after an unlink: the holder
 * was enrolled, their `moodle_username` recorded on the access row, and their pending group
 * assignments copied into real ones, on a course with no Moodle link at all. A teacher who unlinked
 * in order to stop Moodle-driven enrolment had not stopped it, and nothing said so.
 *
 * It was found while writing the group-membership browser specs, not by anything failing — the state
 * those specs needed (`moodle_linked: false` **and** a populated `students_moodle_pending`) is only
 * reachable because of this bug.
 *
 * The fix is both halves the issue argues for, and the tests below are in that order:
 *
 * 1. the join and the invite-info lookup both require the course to still be linked. This is the
 *    correctness half and it stands alone — rows predating the fix are still refused;
 * 2. unlinking deletes the pending accesses and their pending group rows, which is what makes the
 *    state coherent rather than merely harmless.
 *
 * The one option the issue rules out is keeping the rows and quietly not honouring them, which is
 * today's behaviour with the hazard removed and the confusing state left in.
 */
@IntegrationTest
class MoodleUnlinkTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val admin = Auth.ADMIN_ID
    private val student = Auth.STUDENT_ID

    private var courseId = 0L
    private var groupId = 0L
    private var inviteId = ""

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin)
            Fixtures.student(student)
            courseId = Fixtures.course(
                "Programming", moodleShortName = "MOODLE-PROG", moodleSyncStudents = true,
            )
            groupId = Fixtures.courseGroup(courseId, "Lab group 1")
            inviteId = Fixtures.moodlePendingAccess(courseId, "moodle-kati")
            Fixtures.moodlePendingGroup(courseId, "moodle-kati", groupId)
        }
    }

    private fun unlink(force: Boolean = false) = api.put(
        "/v2/courses/$courseId/moodle",
        api.body("moodle_props" to null, "force" to force),
        Auth.asAdmin(admin),
    )

    private fun join() = api.post("/v2/courses/moodle/join/$inviteId", caller = Auth.asStudent(student))

    private fun inviteInfo() = api.get("/v2/courses/moodle/invite/$inviteId", Auth.asStudent(student))

    private fun pendingCounts(): Pair<Long, Long> = transaction {
        StudentMoodlePendingAccess.selectAll().count() to
                StudentMoodlePendingCourseGroup.selectAll().count()
    }

    private fun enrolled(): Boolean = transaction {
        StudentCourseAccess
            .select(StudentCourseAccess.student)
            .where { StudentCourseAccess.course eq courseId }
            .any()
    }

    @Test
    fun `an invite works while the course is linked`() {
        // The positive case, first, and not a formality: every assertion below is that something is
        // refused, and all of them would also pass against an invite that never worked at all.
        assertEquals(200, inviteInfo().status) { inviteInfo().body }

        val joined = join()
        assertEquals(200, joined.status) { joined.body }
        assertTrue(enrolled()) { "The join reported success and enrolled nobody." }

        // The join consumes both pending rows and turns the pending group assignment into a real one.
        // The second half of that was missing until this change: the access was deleted and its group
        // assignment left behind, pending for somebody no longer pending. Invisible because the next
        // Moodle sync clears them all, and wrong in the window before it runs — `DeleteCourseGroup`
        // counts those rows when warning how many people a group deletion affects.
        assertEquals(0L to 0L, pendingCounts()) { "The join left pending rows behind." }
        transaction {
            val groups = StudentCourseGroup
                .select(StudentCourseGroup.courseGroup)
                .where { StudentCourseGroup.course eq courseId }
                .map { it[StudentCourseGroup.courseGroup].value }
            assertEquals(listOf(groupId), groups) { "The pending group assignment was not carried over." }
        }
    }

    @Test
    fun `after unlinking, the invite no longer enrols anyone`() {
        assertEquals(200, unlink().status) { unlink().body }

        val joined = join()

        // Refused exactly as a nonexistent invite is — the lookup returns nothing and
        // `singleOrInvalidRequest` turns that into one answer for both cases. Deliberate: an invite
        // id is a short guessable string on an endpoint any student can reach, so "this invite exists
        // but is dead" is not a distinction worth publishing.
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", joined.errorCode) { joined.body }
        assertEquals(
            api.post("/v2/courses/moodle/join/M-NOSUCHINVITE", caller = Auth.asStudent(student)).errorCode,
            joined.errorCode,
        ) { "A dead invite is distinguishable from one that never existed." }

        assertTrue(!enrolled()) { "Unlinked course still enrolled the invite holder." }
    }

    @Test
    fun `after unlinking, the invite page does not name the course either`() {
        unlink()

        // Both endpoints or neither. With only the join guarded, the page would show the course
        // title and a join button that fails — which is a worse experience than a dead link, because
        // it looks like a bug in the app rather than an expired invitation.
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", inviteInfo().errorCode) { inviteInfo().body }
    }

    @Test
    fun `unlinking drops the pending invitations and their group assignments`() {
        assertEquals(1L to 1L, pendingCounts())

        assertEquals(200, unlink().status)

        assertEquals(0L to 0L, pendingCounts()) {
            "Unlink left pending rows behind. That is the state where the participants page reports " +
                    "moodle_linked: false and a populated students_moodle_pending at the same time, " +
                    "which no part of the UI was designed for."
        }
        transaction {
            assertNull(
                Course.select(Course.moodleShortName)
                    .where { Course.id eq courseId }
                    .single()[Course.moodleShortName]
            )
        }
    }

    private fun link(shortName: String, syncStudents: Boolean = true) = api.put(
        "/v2/courses/$courseId/moodle",
        api.body(
            "moodle_props" to mapOf(
                "moodle_short_name" to shortName,
                "sync_students" to syncStudents,
                "sync_grades" to false,
            )
        ),
        Auth.asAdmin(admin),
    )

    @Test
    fun `re-pointing the course at a different Moodle course drops the old invitations too`() {
        // The same bug through the other door, and the one the first version of this fix missed:
        // an invitation names a *course*, not a Moodle course, and nothing records which Moodle
        // course it was issued under. So after PROG-2026 becomes PROG-2027 the old invite would
        // still enrol its holder into a course now populated from somewhere else — and the join's
        // `isNotNull()` guard cannot see it, because the short name is still there.
        assertEquals(200, link("MOODLE-ALGO").status)

        assertEquals(0L to 0L, pendingCounts()) {
            "Re-pointing kept invitations minted for the Moodle course being left behind."
        }
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", join().errorCode)
        assertTrue(!enrolled())
    }

    @Test
    fun `toggling only the sync flags keeps the invitations`() {
        // The half that must NOT be destructive. Turning grade sync on is not a re-point, and a
        // teacher doing it has no reason to expect their outstanding invitations to disappear — so
        // the delete compares short names rather than firing on every write to the endpoint.
        assertEquals(200, link("MOODLE-PROG", syncStudents = false).status)

        assertEquals(1L to 1L, pendingCounts()) {
            "A sync-flag change dropped the invitations; only a changed short name should."
        }
        // And the invite still works, which is the part a teacher would actually notice.
        assertEquals(200, join().status)
        assertTrue(enrolled())
    }

    @Test
    fun `unlinking one course leaves another course's invitations alone`() {
        // The delete is scoped by course id, and a `deleteWhere` with the wrong predicate is both
        // easy to write and catastrophic here — it would revoke every outstanding Moodle invitation
        // in the system for one teacher's unlink.
        val otherCourseId = transaction {
            val id = Fixtures.course("Algorithms", moodleShortName = "MOODLE-ALGO")
            val otherGroup = Fixtures.courseGroup(id, "Seminar")
            Fixtures.moodlePendingAccess(id, "moodle-juri")
            Fixtures.moodlePendingGroup(id, "moodle-juri", otherGroup)
            id
        }
        assertEquals(2L to 2L, pendingCounts())

        unlink()

        assertEquals(1L to 1L, pendingCounts()) { "Unlink deleted another course's pending rows." }
        transaction {
            val survivor = StudentMoodlePendingAccess
                .select(StudentMoodlePendingAccess.course)
                .where { StudentMoodlePendingAccess.course eq otherCourseId }
                .count()
            assertEquals(1L, survivor)
        }
    }

    @Test
    fun `re-linking is not a rollback, and says so by the invite ids`() {
        val original = inviteId
        unlink()

        val relinked = api.put(
            "/v2/courses/$courseId/moodle",
            api.body(
                "moodle_props" to mapOf(
                    "moodle_short_name" to "MOODLE-PROG",
                    "sync_students" to true,
                    "sync_grades" to false,
                )
            ),
            Auth.asAdmin(admin),
        )
        assertEquals(200, relinked.status) { relinked.body }

        // Re-linking restores the link and **not** the invitations: nothing recreates those rows but
        // a sync. So the old link stays dead even once the course is Moodle-synced again, which is
        // the property that makes a destructive unlink defensible — a mis-typed short name costs a
        // re-sync, not a silently resurrected invitation.
        assertEquals(0L to 0L, pendingCounts())
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", join().errorCode) { "Re-linking revived $original." }

        // What is deliberately *not* asserted here: that a re-sync mints a different invite id. That
        // is `generateMoodleInviteId`'s property, exercised by whatever tests the sync, and the first
        // version of this test claimed it by re-calling the fixture — whose ids are derived from the
        // username, so it reused the id and failed. An assertion about the scaffolding wearing the
        // costume of an assertion about the product.
    }
}
