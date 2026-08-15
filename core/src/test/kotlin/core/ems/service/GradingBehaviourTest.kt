package core.ems.service

import core.db.CourseExerciseExceptionStudent
import core.db.StudentExerciseStatus
import core.ems.service.exercise.getStudentExerciseStatus
import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Grades: the number a student is shown, and the four counts a teacher steers by.
 *
 * `doc/testing.md`'s argument for prioritising this is that a bug here costs a student a grade, and
 * the mechanism is the one it names as the worst kind — **being wrong is invisible**. A misplaced
 * `>` shows the wrong status forever without ever throwing, and nothing in the browser suite would
 * notice, because that suite asserts against fixtures we wrote to match whatever the code does.
 *
 * The threshold boundary is the whole game: `>=` versus `>` is one character and it decides whether
 * scoring exactly the pass mark passes.
 */
@IntegrationTest
class GradingBehaviourTest {

    private val teacher = "grade-teacher"
    private val alice = "grade-alice"
    private val bob = "grade-bob"
    private var courseId = 0L
    private var ceId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(alice)
            Fixtures.student(bob)
            courseId = Fixtures.course("Grading")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, alice)
            Fixtures.enrolStudent(courseId, bob)
            ceId = Fixtures.courseExercise(courseId, Fixtures.exercise("Ex", teacher), threshold = 80)
        }
    }

    // --- the pure rule, at its boundary ------------------------------------------------------

    /**
     * Context-free, so it runs on every push regardless of anything else.
     *
     * Scoring *exactly* the threshold is a pass. That is the assertion worth having: every other
     * case in this function is obvious, and this is the one where a plausible-looking edit changes
     * who passes a course.
     */
    @Test
    fun `scoring exactly the threshold completes the exercise`() {
        assertEquals(StudentExerciseStatus.COMPLETED, getStudentExerciseStatus(true, 80, 80))
        assertEquals(StudentExerciseStatus.STARTED, getStudentExerciseStatus(true, 79, 80))
        assertEquals(StudentExerciseStatus.COMPLETED, getStudentExerciseStatus(true, 81, 80))
    }

    @Test
    fun `no submission is unstarted, and a submission with no grade is ungraded`() {
        assertEquals(StudentExerciseStatus.UNSTARTED, getStudentExerciseStatus(false, null, 80))
        assertEquals(StudentExerciseStatus.UNGRADED, getStudentExerciseStatus(true, null, 80))

        // Not-submitted beats not-graded when both are true: a student who has not submitted is
        // unstarted, never ungraded, whatever the grade column happens to hold.
        assertEquals(StudentExerciseStatus.UNSTARTED, getStudentExerciseStatus(false, 100, 80))
    }

    @Test
    fun `a zero grade is a grade, not an absent one`() {
        // 0 is falsy in the language this eventually reaches, and `grade == null` is the only
        // correct test. A `!grade` anywhere on the way to the UI turns a hard-earned zero into
        // "not graded yet".
        assertEquals(StudentExerciseStatus.STARTED, getStudentExerciseStatus(true, 0, 80))
        assertEquals(StudentExerciseStatus.COMPLETED, getStudentExerciseStatus(true, 0, 0))
    }

    // --- the same rule, through the query a teacher's page uses -----------------------------

    private fun statuses(): Map<String, StudentExerciseStatus> =
        selectAllCourseExercisesLatestSubmissions(courseId, ceId)
            .single().latestSubmissions.associate { it.accountId to it.status }

    private fun counts(): List<Int> = selectAllCourseExercisesLatestSubmissions(courseId, ceId).single()
        .let { listOf(it.completedCount, it.startedCount, it.unstartedCount, it.ungradedCount) }

    @Test
    fun `the boundary holds through the query, not just the function`() {
        transaction {
            Fixtures.submission(ceId, alice, number = 1, grade = 80)
            Fixtures.submission(ceId, bob, number = 1, grade = 79)
        }

        assertEquals(
            mapOf(alice to StudentExerciseStatus.COMPLETED, bob to StudentExerciseStatus.STARTED),
            statuses(),
        )
    }

    @Test
    fun `the four counts partition the enrolled students`() {
        transaction {
            Fixtures.submission(ceId, alice, number = 1, grade = 90) // completed
            Fixtures.submission(ceId, bob, number = 1, grade = null) // ungraded
            // nobody else has submitted, but only these two are enrolled
        }

        val (completed, started, unstarted, ungraded) = counts().let {
            listOf(it[0], it[1], it[2], it[3])
        }
        assertEquals(1, completed)
        assertEquals(0, started)
        assertEquals(0, unstarted)
        assertEquals(1, ungraded)

        // The four together account for every enrolled student, exactly once. A count that
        // double-counts or drops somebody is the kind of thing a teacher notices as "the numbers
        // do not add up" long after it started.
        assertEquals(2, completed + started + unstarted + ungraded)
    }

    @Test
    fun `a student who has not submitted counts as unstarted`() {
        transaction { Fixtures.submission(ceId, alice, number = 1, grade = 90) }
        assertEquals(listOf(1, 0, 1, 0), counts())
        assertEquals(StudentExerciseStatus.UNSTARTED, statuses().getValue(bob))
    }

    /**
     * The latest submission decides the status, not the best one.
     *
     * Worth its own assertion because "best" is the more forgiving rule and the one a reader might
     * assume: a student who scores 90 and then resubmits worse is *not* completed.
     */
    @Test
    fun `a later worse submission replaces an earlier better one`() {
        transaction {
            Fixtures.submission(ceId, alice, number = 1, grade = 90)
            Fixtures.submission(ceId, alice, number = 2, grade = 10)
        }
        assertEquals(StudentExerciseStatus.STARTED, statuses().getValue(alice))
    }

    /**
     * Compares the *instant*, not the `DateTime` object.
     *
     * Joda's `equals` compares the chronology and time zone as well as the instant, and a value that
     * has been through the database comes back in the JVM's default zone. So a fixture written as
     * `…T09:00:00Z` and read back as `…T11:00:00+02:00` — the same moment — is not `equals`. Writing
     * `assertEquals` here fails on a machine in Tallinn and passes on one in London, which is the
     * worst possible kind of test.
     */
    private fun assertSameInstant(expected: org.joda.time.DateTime?, actual: org.joda.time.DateTime?) =
        assertEquals(expected?.millis, actual?.millis) { "expected $expected, was $actual" }

    // --- per-student visibility exceptions ---------------------------------------------------

    /**
     * A per-student exception overrides the course exercise's own visibility date.
     *
     * This is the mechanism behind "give this one student an extension", and it is asymmetric on
     * purpose: the student exception wins outright rather than being combined with the default.
     */
    @Test
    fun `a student exception overrides the default visible-from date`() {
        val future = TestClock.fixed(60 * 24 * 365) // comfortably ahead of now
        val past = TestClock.fixed(0)

        transaction {
            CourseExerciseExceptionStudent.insert {
                it[courseExercise] = EntityID(ceId, core.db.CourseExercise)
                it[student] = EntityID(alice, core.db.Account)
                it[isExceptionStudentVisibleFrom] = true
                it[studentVisibleFrom] = future
                it[isExceptionSoftDeadline] = false
                it[isExceptionHardDeadline] = false
            }
        }

        val exceptions = selectCourseExerciseExceptions(ceId, alice)
        assertSameInstant(future, determineCourseExerciseVisibleFrom(exceptions, ceId, alice, past))

        // And a student with no exception still sees the course exercise's own date.
        val bobExceptions = selectCourseExerciseExceptions(ceId, bob)
        assertSameInstant(past, determineCourseExerciseVisibleFrom(bobExceptions, ceId, bob, past))
    }

    /**
     * A hard deadline in the past closes submission; absent means open.
     *
     * `null` meaning "no deadline, always open" rather than "closed" is the sort of default that is
     * obvious until someone writes `deadline.isAfterNow` and every exercise without a deadline
     * silently stops accepting work.
     */
    @Test
    fun `submission is open when there is no deadline and closed when it has passed`() {
        val exceptions = selectCourseExerciseExceptions(ceId, alice)

        assertEquals(true, isCourseExerciseOpenForSubmit(exceptions, ceId, alice, null))
        assertEquals(true, isCourseExerciseOpenForSubmit(exceptions, ceId, alice, TestClock.fixed(60 * 24 * 365)))
        assertEquals(false, isCourseExerciseOpenForSubmit(exceptions, ceId, alice, TestClock.fixed(0)))
    }
}
