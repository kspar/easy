package core.ems.service.course.invite

import core.db.CourseInviteLink
import core.db.StudentCourseAccess
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A course invite link's `allowed_uses` cap, including when it is used the way invite links actually
 * get used: read out to a room and clicked by everyone at once.
 *
 * The cap used to be checked in the `SELECT` and applied by a later `UPDATE … used_count + 1`, with no
 * row lock, inside one transaction at READ COMMITTED. Concurrent joins all passed the check against the
 * same `used_count`, all inserted their own access row, and all incremented — so an `allowed_uses = 1`
 * link admitted as many students as clicked it together.
 *
 * **What this is and is not.** Everyone who got in already held a valid invite link, so nothing was
 * exposed to anyone who could not otherwise have joined: this is a limit that did not hold, not an
 * access-control bypass. It matters where a small `allowed_uses` is set deliberately —
 * `GenerateCourseInvite` bounds the field `@Min(0) @Max(1000000)`, so `1` is a supported configuration.
 *
 * The two properties the code already had right, which the fix has to keep:
 *
 * - a student re-clicking their own link does not burn a use (`insertIgnore` plus `insertedCount > 0`);
 * - expiry, cap and invite id are all decided in one query.
 */
@IntegrationTest
class CourseInviteCapTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    /**
     * Named `inviteCode`, not `inviteId`.
     *
     * Inside `CourseInviteLink.insert { }` the table is the implicit receiver, and its members win over
     * an enclosing class's properties — so `it[CourseInviteLink.inviteId] = inviteId` assigned the
     * *column* to itself. It compiles, because Exposed has a `set(Column<S>, Expression<out S>)`
     * overload, and it fails at runtime with `invalid reference to FROM-clause entry` from an INSERT.
     */
    private val inviteCode = "ABCD1234"

    private var courseId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            courseId = Fixtures.course("Invited")
            Fixtures.enrolTeacher(courseId, teacher)
        }
    }

    private fun invite(allowedUses: Int, usedCount: Int = 0) {
        transaction {
            CourseInviteLink.insert {
                it[CourseInviteLink.inviteId] = inviteCode
                it[course] = EntityID(courseId, core.db.Course)
                it[createdAt] = TestClock.fixed(0)
                it[expiresAt] = TestClock.farFuture()
                it[CourseInviteLink.allowedUses] = allowedUses
                it[CourseInviteLink.usedCount] = usedCount
            }
        }
    }

    private fun student(id: String): String = transaction { Fixtures.student(id) }

    private fun join(studentId: String) =
        api.post("/v2/courses/join/$inviteCode", null, Auth.asStudent(studentId))

    private fun enrolledCount(): Int = transaction {
        StudentCourseAccess.selectAll().where { StudentCourseAccess.course eq courseId }.count().toInt()
    }

    private fun usedCount(): Int = transaction {
        CourseInviteLink.select(CourseInviteLink.usedCount)
            .where { CourseInviteLink.course eq courseId }
            .single()[CourseInviteLink.usedCount]
    }

    // --- the sequential cases, which already worked ------------------------------------------

    /**
     * A control, and the reason the bug was invisible: used one at a time, the cap holds exactly.
     *
     * Anyone reasoning about this code by reading it, or by testing it the obvious way, gets the right
     * answer. The defect only appears when two requests overlap, which is the normal way a link handed
     * to a lecture room is used.
     */
    @Test
    fun `used one at a time, a cap of one admits one student and rejects the next`() {
        invite(allowedUses = 1)
        val first = student("inv-first")
        val second = student("inv-second")

        assertEquals(200, join(first).status)
        assertEquals(1, enrolledCount())
        assertEquals(1, usedCount())

        val rejected = join(second)
        assertEquals(400, rejected.status) { "the second student must be turned away: ${rejected.body}" }
        assertEquals(1, enrolledCount()) { "and must not be enrolled" }
        assertEquals(1, usedCount()) { "and must not have burned a use" }
    }

    /**
     * Re-clicking your own link is free, and must stay free.
     *
     * This is the property that decides the *shape* of the fix rather than merely surviving it. The
     * obvious atomic fix — reserve a use first, then join — would charge a student a use every time
     * they revisited the link, so a cap of one would lock the course after a single student refreshed
     * their browser. The access insert has to come first and the reservation second.
     */
    @Test
    fun `re-clicking the same link does not burn another use`() {
        invite(allowedUses = 2)
        val alice = student("inv-alice")

        assertEquals(200, join(alice).status)
        assertEquals(200, join(alice).status) { "a second click by the same student is not an error" }

        assertEquals(1, enrolledCount())
        assertEquals(1, usedCount()) { "the same student joining twice is one use, not two" }
    }

    // --- and the case the cap was written for ------------------------------------------------

    /**
     * **The finding. Six students, one use, all clicking at once.**
     *
     * Real threads and a barrier, because nothing smaller reproduces it: the race is between one
     * request's `SELECT` and another's `UPDATE`, and there is no seam to inject into. The barrier only
     * lines the requests up; whether they actually overlap in the database is up to the scheduler.
     *
     * So note the direction this test can fail in. Against the fixed code it must pass every time —
     * the conditional `UPDATE` takes a row lock and re-evaluates its predicate against the committed
     * row, so exactly one of any number of concurrent joins can move `used_count` from
     * `allowed_uses - 1` to `allowed_uses`. Against the broken code it fails whenever any two requests
     * genuinely overlap, which is most runs but not provably all of them. A spurious *pass* is the
     * failure mode here, never a spurious failure, which is the safe direction for a test to be
     * imperfect in.
     *
     * Six threads against a default Hikari pool of ten, so the concurrency is real and there is still
     * room for the assertions' own connections.
     */
    @Test
    fun `six students clicking at once cannot all get through a cap of one`() {
        invite(allowedUses = 1)
        val students = (1..6).map { student("inv-race-$it") }

        val barrier = CyclicBarrier(students.size)
        val pool = Executors.newFixedThreadPool(students.size)
        try {
            val statuses = students.map { id ->
                pool.submit<Int> {
                    barrier.await(10, TimeUnit.SECONDS)
                    join(id).status
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, statuses.count { it == 200 }) {
                "expected exactly one join to succeed, got statuses $statuses"
            }
            assertEquals(1, enrolledCount()) {
                "A cap of one admitted more than one student. Everyone who got in did hold a valid " +
                        "link, so this is the limit failing rather than an access bypass — but it is " +
                        "still a limit a teacher set and the application did not keep."
            }
            assertEquals(1, usedCount())
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The same race with room for everyone: a cap of six and six simultaneous joiners must all get in.
     *
     * Without this, "exactly one" above could be satisfied by a fix that serialises the joins and then
     * loses five of them — a cap that is too *strict* under concurrency is just as wrong, and it is the
     * easier mistake to make with a row lock in play.
     */
    @Test
    fun `a cap with room for everyone lets everyone in even when they arrive together`() {
        invite(allowedUses = 6)
        val students = (1..6).map { student("inv-room-$it") }

        val barrier = CyclicBarrier(students.size)
        val pool = Executors.newFixedThreadPool(students.size)
        try {
            val statuses = students.map { id ->
                pool.submit<Int> {
                    barrier.await(10, TimeUnit.SECONDS)
                    join(id).status
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(students.size, statuses.count { it == 200 }) {
                "every student had a use available; statuses were $statuses"
            }
            assertEquals(students.size, enrolledCount())
            assertEquals(students.size, usedCount())
        } finally {
            pool.shutdownNow()
        }
    }
}
