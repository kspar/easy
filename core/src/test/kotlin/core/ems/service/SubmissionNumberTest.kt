package core.ems.service

import core.db.AutoGradeStatus
import core.db.Submission
import core.ems.service.cache.CachingService
import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `submission.number` — the per-student sequence a student sees next to each of their attempts.
 *
 * It is assigned by reading the current maximum and writing `max + 1`, in one transaction at READ
 * COMMITTED, and until changeset `280826-1` nothing enforced uniqueness: `Submission` declared no
 * unique index and the only index Liquibase created on the table was the non-unique
 * `submissions_by_student_on_exercise`. So two concurrent submissions by one student to one course
 * exercise both read N and both wrote N + 1.
 *
 * **That is not hypothetical.** The dev database contains one such pair — two rows a millisecond
 * apart, consecutive ids, the same solution, the same grade, both autograded. A double-click.
 *
 * Nothing throws when it happens, which is why it survived: the consequences are ordering
 * ambiguities. `previousTeacherGrade` takes `orderBy(number DESC).limit(1)` and ties, so the grade
 * carried onto a resubmission becomes arbitrary, and two of a student's attempts are labelled the
 * same in the UI. The grade table is already defended, because its ordering falls through `number` to
 * `id` — and its comment says that tiebreaker is there in case `number` is ever wrong, which it was.
 *
 * The fix is a unique constraint on `(course_exercise_id, student_id, number)`, so the race becomes a
 * failed insert rather than silent corruption. The loser of a double-click gets an error while the
 * first submission is already saved. Not lovely, and better than two rows quietly claiming to be the
 * same attempt.
 */
@IntegrationTest
class SubmissionNumberTest(@Autowired private val caching: CachingService) {

    private val teacher = "num-teacher"
    private val alice = "num-alice"

    private var courseId = 0L
    private var ceId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(alice)
            courseId = Fixtures.course("Numbering")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, alice)
            ceId = Fixtures.courseExercise(courseId, Fixtures.exercise("Ex", teacher))
        }
    }

    private fun numbersOf(studentId: String): List<Int> = transaction {
        Submission
            .select(Submission.number)
            .where { Submission.courseExercise eq ceId and (Submission.student eq studentId) }
            .map { it[Submission.number] }
            .sorted()
    }

    /** The sequential path, which was always right: each submission takes the next number. */
    @Test
    fun `submissions in sequence are numbered one two three`() {
        repeat(3) { insertSubmission(ceId, "print($it)", alice, AutoGradeStatus.NONE, caching) }
        assertEquals(listOf(1, 2, 3), numbersOf(alice))
    }

    /**
     * The constraint itself, asserted directly rather than only through the race.
     *
     * A concurrency test can pass by luck — the threads happen to serialise — so something has to pin
     * that the *database* refuses a duplicate. This is that: a hand-written duplicate must not be
     * insertable at all, which is true whether or not any scheduler cooperates.
     */
    @Test
    fun `the database refuses two submissions with the same number for one student`() {
        transaction { Fixtures.submission(ceId, alice, number = 1) }

        assertThrows(Exception::class.java) {
            transaction { Fixtures.submission(ceId, alice, number = 1) }
        }

        assertEquals(listOf(1), numbersOf(alice)) { "the duplicate must not have landed" }
    }

    /**
     * And the same number *is* allowed where it means something different.
     *
     * Without this, the constraint could be narrowed to `(course_exercise_id, number)` or widened to
     * the whole table and both tests above would still pass — while every second student on an
     * exercise lost the ability to submit.
     */
    @Test
    fun `the same number is fine for a different student and for a different course exercise`() {
        val bob = transaction { Fixtures.student("num-bob") }
        val otherCeId = transaction {
            Fixtures.enrolStudent(courseId, bob)
            Fixtures.courseExercise(courseId, Fixtures.exercise("Ex2", teacher), orderIdx = 2)
        }

        transaction {
            Fixtures.submission(ceId, alice, number = 1)
            Fixtures.submission(ceId, bob, number = 1)
            Fixtures.submission(otherCeId, alice, number = 1)
        }

        assertEquals(listOf(1), numbersOf(alice))
        assertEquals(listOf(1), numbersOf(bob))
    }

    /**
     * **The finding.** Four submissions by one student, arriving together.
     *
     * The assertion is the invariant rather than the outcome: whatever gets stored, no two of a
     * student's submissions may share a number. Some of the four are allowed to fail — that is what
     * the constraint converts the race *into*, and failing loudly is the point — so the test counts
     * the successes and requires the stored numbers to match them exactly.
     *
     * Against the unconstrained code this fails by storing duplicates whenever two threads genuinely
     * overlap, which is most runs. As with the invite race, a spurious pass is the failure mode here,
     * never a spurious failure.
     */
    @Test
    fun `concurrent submissions never share a number`() {
        val threads = 4
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)

        val outcomes = try {
            (1..threads).map { i ->
                pool.submit<Boolean> {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        insertSubmission(ceId, "print($i)", alice, AutoGradeStatus.NONE, caching)
                        true
                    } catch (e: Exception) {
                        // A constraint violation here is the fix working, not a failure.
                        false
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val stored = numbersOf(alice)
        val succeeded = outcomes.count { it }

        assertTrue(succeeded >= 1) { "at least one submission must get through" }
        assertEquals(succeeded, stored.size) { "stored rows must match successful calls" }
        assertEquals(stored.distinct(), stored) {
            "Two of one student's submissions share a number: $stored. The number is shown to the " +
                    "student and `previousTeacherGrade` orders by it, so a tie makes the grade " +
                    "carried onto a resubmission arbitrary."
        }
    }
}
