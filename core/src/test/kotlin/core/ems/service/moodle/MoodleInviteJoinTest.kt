package core.ems.service.moodle

import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingAccess
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Redeeming a Moodle invite when the student is already where the invite would put them.
 *
 * The join copies pending group assignments into real ones, and it used to do so with a plain
 * `StudentCourseGroup.insert` — two lines after a deliberately idempotent
 * `StudentCourseAccess.insertIgnore`. `StudentCourseGroup`'s key is `(course, student, group)`, so if
 * the student was already in a group the invite also named, the insert violated it and the request
 * failed with a 500. The same concern, answered two different ways two lines apart, which is the
 * asymmetric-duplication signature `doc/review-plan.md` treats as a defect detector.
 *
 * The sequence is ordinary: a Moodle sync issues a personal invite naming group G; before the student
 * redeems it a teacher adds them to the course and to G by hand, which does not delete the pending row;
 * the student then clicks the link in their email.
 *
 * The failure was *safe* — the transaction rolled back, so the invite was not consumed — and permanent:
 * that student could not use their link at all, and the error told them nothing. Nothing self-heals it
 * either, because the pending row survives every attempt.
 */
@IntegrationTest
class MoodleInviteJoinTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    /**
     * `studentId`, not `student`: inside `StudentCourseGroup.insert { }` the table is the implicit
     * receiver and its `student` column would win over a property of this class.
     */
    private val studentId = Auth.STUDENT_ID
    private val moodleUsername = "moodle-mari"

    private var courseId = 0L
    private var groupId = 0L
    private var inviteId = ""

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.student(studentId)
            courseId = Fixtures.course(
                "Programming", moodleShortName = "MOODLE-PROG", moodleSyncStudents = true,
            )
            groupId = Fixtures.courseGroup(courseId, "Lab group 1")
            inviteId = Fixtures.moodlePendingAccess(courseId, moodleUsername)
            Fixtures.moodlePendingGroup(courseId, moodleUsername, groupId)
        }
    }

    private fun join() = api.post("/v2/courses/moodle/join/$inviteId", null, Auth.asStudent(studentId))

    private fun groupMemberships(): Int = transaction {
        StudentCourseGroup.selectAll()
            .where { StudentCourseGroup.course eq courseId and (StudentCourseGroup.student eq studentId) }
            .count().toInt()
    }

    private fun pendingRows(): Int = transaction {
        StudentMoodlePendingAccess.selectAll()
            .where { StudentMoodlePendingAccess.course eq courseId }
            .count().toInt()
    }

    /** A control: the ordinary path, where the student is on neither the course nor the group. */
    @Test
    fun `a student not yet on the course joins and lands in the pending group`() {
        val r = join()
        assertEquals(200, r.status) { r.body }

        assertEquals(1, groupMemberships())
        assertEquals(0, pendingRows()) { "the pending access must be consumed" }
    }

    /**
     * **The finding.** The teacher got there first, by hand, into the very group the invite names.
     */
    @Test
    fun `a student already in the invited group can still redeem the invite`() {
        transaction {
            Fixtures.enrolStudent(courseId, studentId)
            StudentCourseGroup.insert {
                it[StudentCourseGroup.student] = EntityID(studentId, core.db.Account)
                it[StudentCourseGroup.course] = EntityID(courseId, core.db.Course)
                it[courseGroup] = EntityID(groupId, core.db.CourseGroup)
            }
        }

        val r = join()
        assertEquals(200, r.status) {
            "A student already in the group the invite names could not use their link at all: ${r.body}"
        }

        assertEquals(1, groupMemberships()) { "still one membership, not a duplicate" }
        assertEquals(0, pendingRows()) { "and the invite is consumed rather than left dangling" }
    }

    /**
     * Already on the course but not in the group: the access insert is skipped, the group insert is not.
     *
     * Worth separating from the case above because the two inserts are independent, and a fix applied
     * to only one of them would still leave this or that half broken.
     */
    @Test
    fun `a student already on the course but not in the group is added to the group`() {
        transaction { Fixtures.enrolStudent(courseId, studentId) }

        val r = join()
        assertEquals(200, r.status) { r.body }
        assertEquals(1, groupMemberships())

        // The pre-existing access row must keep its place — this must not have inserted a second one.
        val accessRows = transaction {
            StudentCourseAccess.selectAll().where { StudentCourseAccess.course eq courseId }.count()
        }
        assertEquals(1L, accessRows)
    }

    /**
     * And clicking the same link twice.
     *
     * The second click finds no pending row and must be a clean rejection rather than a 500 — the
     * pending access is deleted by the first, so this exercises the lookup, not the inserts.
     */
    @Test
    fun `clicking a consumed link again is refused rather than crashing`() {
        assertEquals(200, join().status)

        val second = join()
        assertEquals(400, second.status) { "expected a clean refusal, got ${second.status}: ${second.body}" }
        assertEquals(1, groupMemberships()) { "and nothing changed" }
    }
}
