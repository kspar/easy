package core.ems.service

import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [selectAllCourseExercisesLatestSubmissions] and [selectStudentsOnCourse], against a real
 * PostgreSQL.
 *
 * The scenario, built fresh for each test:
 *
 * | | EX1 (threshold 90) | EX2 (threshold 80) |
 * | --- | --- | --- |
 * | student1 | 71, then **81** | 91, then **99** |
 * | student2 | *(no submission)* | **51** |
 *
 * Bold is what "latest" should select.
 *
 * This class used to be `@Tag("db")`-excluded, `PER_CLASS`, and set up by dropping and recreating
 * the whole schema in `@BeforeAll` — with a comment noting that another class sharing the context
 * might have dropped it first, which is the pattern noticing it does not survive a second test
 * class. It now uses the shared `@IntegrationTest` context and a TRUNCATE between tests, so the
 * tests are independent and it runs on every push.
 */
@IntegrationTest
class ValidateSelectAllCourseExercisesLatestSubmissions {

    private val student1Id = "student1"
    private val student2Id = "student2"
    private val teacherId = "teacher1"

    private var courseId = 0L
    private var ce1Id = 0L
    private var ce2Id = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacherId, "Bob", "Smith")
            Fixtures.student(student1Id, "John", "Doe")
            Fixtures.student(student2Id, "Jane", "Doe")

            courseId = Fixtures.course("Test Course", alias = "TC")
            Fixtures.enrolTeacher(courseId, teacherId)
            Fixtures.enrolStudent(courseId, student1Id)
            Fixtures.enrolStudent(courseId, student2Id)

            // Ids are returned rather than derived. The version of this test that computed
            // `ce2Id = ce1Id + 1` was relying on insertion order to hold that arithmetic up.
            ce1Id = Fixtures.courseExercise(
                courseId, Fixtures.exercise("Exercise 1", teacherId), threshold = 90, orderIdx = 1
            )
            ce2Id = Fixtures.courseExercise(
                courseId, Fixtures.exercise("Exercise 2", teacherId), threshold = 80, orderIdx = 2
            )

            Fixtures.submission(ce1Id, student1Id, number = 1, grade = 71)
            Fixtures.submission(ce1Id, student1Id, number = 2, grade = 81)
            Fixtures.submission(ce2Id, student1Id, number = 1, grade = 91)
            Fixtures.submission(ce2Id, student1Id, number = 2, grade = 99)
            Fixtures.submission(ce2Id, student2Id, number = 1, grade = 51)
        }
    }

    @Test
    fun `returns one latest submission per student per exercise`() {
        val submissions = selectAllCourseExercisesLatestSubmissions(courseId)
            .flatMap { it.latestSubmissions }
            .map { it.latestSubmission }

        assertEquals(4, submissions.size)
    }

    @Test
    fun `student 1 gets the later of each pair, 81 and 99`() {
        val byExercise = selectAllCourseExercisesLatestSubmissions(courseId)

        val ex1 = byExercise.single { it.courseExerciseId.toLong() == ce1Id }
        val ex2 = byExercise.single { it.courseExerciseId.toLong() == ce2Id }

        val ex1Sub = ex1.latestSubmissions.single { it.accountId == student1Id }
        assertEquals(81, ex1Sub.latestSubmission!!.grade!!.grade)
        assertEquals(false, ex1Sub.latestSubmission.grade.isAutograde)

        val ex2Sub = ex2.latestSubmissions.single { it.accountId == student1Id }
        assertEquals(99, ex2Sub.latestSubmission!!.grade!!.grade)
        assertEquals(false, ex2Sub.latestSubmission.grade.isAutograde)
    }

    @Test
    fun `student 2 gets a null for the exercise never attempted and 51 for the other`() {
        val byExercise = selectAllCourseExercisesLatestSubmissions(courseId)

        val ex1 = byExercise.single { it.courseExerciseId.toLong() == ce1Id }
        val ex2 = byExercise.single { it.courseExerciseId.toLong() == ce2Id }

        assertNull(ex1.latestSubmissions.single { it.accountId == student2Id }.latestSubmission)

        val ex2Sub = ex2.latestSubmissions.single { it.accountId == student2Id }
        assertEquals(51, ex2Sub.latestSubmission!!.grade!!.grade)
        assertEquals(false, ex2Sub.latestSubmission.grade.isAutograde)
    }

    @Test
    fun `narrowing to one course exercise returns only that one`() {
        val only = selectAllCourseExercisesLatestSubmissions(courseId, ce2Id)

        assertEquals(1, only.size)
        val ex2Sub = only.single().latestSubmissions.single { it.accountId == student2Id }
        assertEquals(51, ex2Sub.latestSubmission!!.grade!!.grade)
    }

    @Test
    fun `narrowing to the other course exercise still picks the later submission`() {
        val only = selectAllCourseExercisesLatestSubmissions(courseId, ce1Id)

        assertEquals(1, only.size)
        val ex1Sub = only.single().latestSubmissions.single { it.accountId == student1Id }
        assertEquals(81, ex1Sub.latestSubmission!!.grade!!.grade)
    }

    /**
     * EX1 threshold 90: student1 scored 81 (started), student2 never submitted (unstarted).
     * EX2 threshold 80: student1 scored 99 (completed), student2 scored 51 (started).
     */
    @Test
    fun `counts completed, started, unstarted and ungraded against each threshold`() {
        val byExercise = selectAllCourseExercisesLatestSubmissions(courseId)

        assertEquals(setOf(ce1Id, ce2Id), byExercise.map { it.courseExerciseId.toLong() }.toSet())

        val ex1 = byExercise.single { it.courseExerciseId.toLong() == ce1Id }
        val ex2 = byExercise.single { it.courseExerciseId.toLong() == ce2Id }

        assertEquals(0, ex1.completedCount)
        assertEquals(1, ex1.startedCount)
        assertEquals(1, ex1.unstartedCount)
        assertEquals(0, ex1.ungradedCount)

        assertEquals(1, ex2.completedCount)
        assertEquals(1, ex2.startedCount)
        assertEquals(0, ex2.unstartedCount)
        assertEquals(0, ex2.ungradedCount)
    }

    @Test
    fun `selectStudentsOnCourse returns both enrolled students`() {
        assertEquals(
            setOf(student1Id, student2Id),
            selectStudentsOnCourse(courseId).map { it.id }.toSet()
        )
    }

    /**
     * The regression test for EZ-1763, and the reason that issue was a production bug rather than a
     * flaky fixture.
     *
     * `created_at` is millisecond-resolution, so two submissions can genuinely share one — a
     * double-click, a retry, an autograde write landing beside a manual grade. When they do, an
     * ordering on `created_at` alone is not total, and `DISTINCT ON` keeps whichever row the plan
     * happened to emit first. The student sees a grade that changes on refresh.
     *
     * So this builds the tie on purpose, with `TestClock.fixed`, and asserts that the higher
     * `number` — the per-student submission sequence, which is what "latest" is supposed to mean —
     * wins. Before the tiebreakers were added to the query in courses.kt, this failed most runs.
     */
    @Test
    fun `submissions sharing a created_at are broken by submission number, not by luck`() {
        val tie = TestClock.fixed(500)
        transaction {
            Fixtures.submission(ce1Id, student2Id, number = 1, grade = 10, createdAt = tie)
            Fixtures.submission(ce1Id, student2Id, number = 2, grade = 20, createdAt = tie)
        }

        val latest = selectAllCourseExercisesLatestSubmissions(courseId, ce1Id)
            .single()
            .latestSubmissions
            .single { it.accountId == student2Id }
            .latestSubmission

        assertEquals(20, latest!!.grade!!.grade) {
            "Expected the submission with the higher `number` to win a created_at tie. Getting 10 " +
                    "means the ordering in selectAllCourseExercisesLatestSubmissions is no longer total."
        }
    }
}
