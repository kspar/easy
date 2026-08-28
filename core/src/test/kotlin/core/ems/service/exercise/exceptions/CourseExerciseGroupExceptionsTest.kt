package core.ems.service.exercise.exceptions

import core.db.CourseExerciseExceptionGroup
import core.db.CourseExerciseExceptionStudent
import core.db.StudentCourseGroup
import core.ems.service.determineCourseExerciseVisibleFrom
import core.ems.service.determineSoftDeadline
import core.ems.service.isCourseExerciseOpenForSubmit
import core.ems.service.selectCourseExerciseExceptions
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
import org.joda.time.DateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Course exercise exceptions, for a student who belongs to more than one course group.
 *
 * **The exception model is deliberately three-state, and that is the whole subject of this file.**
 * `ExceptionValue?` being null means *no exception for this field*; `ExceptionValue(null)` means *an
 * exception is set and its value is nothing*. That second state is the only reason the wrapper class
 * exists at all — a plain `DateTime?` could not tell the two apart — and `PutCourseExerciseExceptions`
 * lets a teacher post exactly it, because the `is_exception_*` boolean and the value column are
 * separate columns in both exception tables.
 *
 * What "nothing" means per field is not arbitrary. In every case it is the **far end of the
 * timeline**, never the near one:
 *
 * | field | `ExceptionValue(null)` means | because |
 * |---|---|---|
 * | `hardDeadline` | no deadline, submission always open | `studentException.value?.isAfterNow ?: true` |
 * | `softDeadline` | no late marking, ever | a null soft deadline is not a deadline |
 * | `studentVisibleFrom` | never visible | `isHidden = visibleFrom == null \|\| isAfterNow` |
 *
 * So a null value behaves as **positive infinity** for all three, and the group aggregation's rule —
 * farthest in the future wins — is the same rule for all three. That is why one shared helper can
 * serve them, and why the bug this file was written for was a one-line one.
 *
 * A student in several groups on one course is a supported state, not a corner: `StudentCourseGroup`'s
 * primary key is `(student, course, courseGroup)` and the teacher UI manages membership. Nothing here
 * had a test before — the neighbouring single-student paths are covered in `GradingBehaviourTest`, and
 * the group aggregation, which is where the arithmetic actually lives, had none.
 */
@IntegrationTest
class CourseExerciseGroupExceptionsTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val alice = "exc-alice"

    private var courseId = 0L
    private var ceId = 0L
    private var groupA = 0L
    private var groupB = 0L

    /** In the past relative to a real `isAfterNow`, because [Fixtures] counts from 2026-01-01. */
    private val past = TestClock.fixed(0)
    private val future = TestClock.farFuture()

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(alice)
            courseId = Fixtures.course("Exceptions")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, alice)
            ceId = Fixtures.courseExercise(courseId, Fixtures.exercise("Ex", teacher))

            groupA = Fixtures.courseGroup(courseId, "Group A")
            groupB = Fixtures.courseGroup(courseId, "Group B")
            // Alice is in both. This is the state the whole file is about.
            joinGroup(alice, groupA)
            joinGroup(alice, groupB)
        }
    }

    private fun joinGroup(studentId: String, courseGroupId: Long) {
        StudentCourseGroup.insert {
            it[student] = EntityID(studentId, core.db.Account)
            it[course] = EntityID(courseId, core.db.Course)
            it[courseGroup] = EntityID(courseGroupId, core.db.CourseGroup)
        }
    }

    /**
     * One group exception row.
     *
     * The `isException*` flag and the value are passed separately on purpose, so a test can express
     * "an exception is set and its value is nothing" — which is the state under test and which no
     * single nullable parameter can say.
     */
    private fun groupException(
        courseGroupId: Long,
        isExceptionHard: Boolean = false,
        hard: DateTime? = null,
        isExceptionSoft: Boolean = false,
        soft: DateTime? = null,
        isExceptionVisible: Boolean = false,
        visibleFrom: DateTime? = null,
    ) {
        transaction {
            CourseExerciseExceptionGroup.insert {
                it[courseExercise] = EntityID(ceId, core.db.CourseExercise)
                it[courseGroup] = EntityID(courseGroupId, core.db.CourseGroup)
                it[isExceptionHardDeadline] = isExceptionHard
                it[hardDeadline] = hard
                it[isExceptionSoftDeadline] = isExceptionSoft
                it[softDeadline] = soft
                it[isExceptionStudentVisibleFrom] = isExceptionVisible
                it[studentVisibleFrom] = visibleFrom
            }
        }
    }

    // --- an unbounded exception must not be defeated by a dated one ---------------------------

    /**
     * The finding this file exists for.
     *
     * Group A's exception says "no hard deadline" — unlimited time, the most generous thing a teacher
     * can grant. Group B's says "the deadline was in the past". Alice is in both, so the aggregation
     * decides, and its rule is *farthest in the future wins*.
     *
     * The old helper reduced a null value to `DateTime(0)` before comparing, so "no deadline" was
     * ranked as **1970** — the far past, the exact opposite of what it means. It therefore lost to
     * every dated exception, and could not win even against one that had already expired. A teacher
     * who had granted a student unlimited time had locked them out instead, and nothing anywhere said
     * so: no exception, no log line, just a closed submission box.
     */
    @Test
    fun `an unlimited hard deadline is not defeated by another group's expired one`() {
        groupException(groupA, isExceptionHard = true, hard = null)
        groupException(groupB, isExceptionHard = true, hard = past)

        val exceptions = selectCourseExerciseExceptions(ceId, alice)
        assertTrue(isCourseExerciseOpenForSubmit(exceptions, ceId, alice, past)) {
            "an exception granting unlimited time must keep submission open"
        }
    }

    /**
     * The same defect on the soft deadline, where the cost is quieter: not a locked-out student but a
     * student marked late who had been excused from being late.
     */
    @Test
    fun `an unlimited soft deadline is not defeated by another group's expired one`() {
        groupException(groupA, isExceptionSoft = true, soft = null)
        groupException(groupB, isExceptionSoft = true, soft = past)

        val exceptions = selectCourseExerciseExceptions(ceId, alice)
        assertNull(determineSoftDeadline(exceptions, ceId, alice, past)) {
            "an exception setting no soft deadline must not be overruled by a dated one"
        }
    }

    /**
     * **And the same one-line bug pointing the other way.**
     *
     * For visibility, `null` means *never visible*, so the far end of the timeline is the restrictive
     * end rather than the generous one. Ranking it as 1970 therefore did not lock a student out — it
     * let them in, and the exercise content a teacher had withheld from Group A was shown because
     * Group B had a date in the past.
     *
     * This direction is the reason the finding proposed splitting the helper in two. It does not need
     * splitting: both directions want "a null value wins", because in both the null is `+infinity`.
     * What the two fields disagree about is whether that is generous or strict, and the aggregation
     * does not need to know.
     */
    @Test
    fun `a never-visible exception is not defeated by another group's past date`() {
        groupException(groupA, isExceptionVisible = true, visibleFrom = null)
        groupException(groupB, isExceptionVisible = true, visibleFrom = past)

        val exceptions = selectCourseExerciseExceptions(ceId, alice)
        val visibleFrom = determineCourseExerciseVisibleFrom(exceptions, ceId, alice, past)

        assertNull(visibleFrom) { "an exception withholding the exercise must not be overruled" }
        // Spelled out, because `null` is the input to this rule as well as the output of the one above
        // and it is worth pinning which way round it reads.
        assertTrue(visibleFrom == null || visibleFrom.isAfterNow) { "so the exercise stays hidden" }
    }

    // --- and the ordinary case still behaves as documented ------------------------------------

    /**
     * The control. With two dated exceptions and no nulls in sight, the later one still wins, which
     * is what the KDoc on all three `determine*` functions promises.
     *
     * Without this, "a null value wins outright" could be implemented as "null always wins, and so
     * does anything else I happen to return first", and both tests above would still pass.
     */
    @Test
    fun `with two dated group exceptions the later one wins`() {
        // One row per group: the table's key is (course exercise, group), so a second insert for the
        // same group is a constraint violation rather than a second exception.
        groupException(groupA, isExceptionHard = true, hard = past, isExceptionSoft = true, soft = past)
        groupException(groupB, isExceptionHard = true, hard = future)

        val exceptions = selectCourseExerciseExceptions(ceId, alice)
        assertTrue(isCourseExerciseOpenForSubmit(exceptions, ceId, alice, past)) {
            "the later of two dated deadlines must win"
        }
        assertEquals(past.millis, determineSoftDeadline(exceptions, ceId, alice, null)?.millis) {
            "the only soft-deadline exception set must be used"
        }
    }

    /**
     * A group with no exception on a field must not be read as an exception with no value — the
     * difference between the two states, from the other side.
     *
     * `mapNotNull` in each `determine*` function is what does this, and it is one character away from
     * `map`, which would turn every group that has *any* exception into an unbounded exception on
     * *every* field. That mistake would make the three tests above pass for the wrong reason.
     */
    @Test
    fun `a group exception on one field does not become an exception on the others`() {
        // A hard-deadline exception only. Nothing is said about visibility.
        groupException(groupA, isExceptionHard = true, hard = null)

        val exceptions = selectCourseExerciseExceptions(ceId, alice)
        assertEquals(past.millis, determineCourseExerciseVisibleFrom(exceptions, ceId, alice, past)?.millis) {
            "with no visibility exception the course exercise's own date must be used"
        }
        assertNull(determineSoftDeadline(exceptions, ceId, alice, null))
    }

    // --- removing a group exception -----------------------------------------------------------

    /**
     * **A second bug, found while setting the fixtures above up.**
     *
     * `RemoveCourseExerciseExceptions` deleted the group exceptions like this:
     *
     * ```kotlin
     * CourseExerciseExceptionStudent.deleteWhere {
     *     CourseExerciseExceptionGroup.courseExercise eq courseExId and (…)
     * }
     * ```
     *
     * The receiver is the **student** table and every column in the predicate belongs to the **group**
     * table, so the SQL is a `DELETE FROM course_exercise_exception_student` whose `WHERE` names a
     * table that is not in the statement. Postgres answers `missing FROM-clause entry`, the transaction
     * rolls back and the teacher gets a 500 — a group exception could not be removed at all.
     *
     * It is the copy-paste shape `doc/review-plan.md` calls out as a defect detector: the private
     * method here is still called `insertOrUpdateCourseExerciseExceptions`, because it was copied from
     * the controller that does the inserting. The two branches were meant to be the same shape and one
     * of them was edited incompletely.
     */
    @Test
    fun `removing a group exception removes it`() {
        groupException(groupA, isExceptionHard = true, hard = past)

        val r = api.deleteWithBody(
            "/v2/courses/$courseId/exercises/$ceId/exception",
            api.body("exception_groups" to listOf(groupA)),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, r.status) { "removing a group exception must not fail: ${r.body}" }

        assertEquals(0, groupExceptionCount(groupA)) { "the exception row must be gone" }
    }

    /**
     * And it must remove only the group named.
     *
     * The obvious fix is one word — change the receiver table — and a plausible wrong version of it
     * drops the group predicate along with the wrong table name, wiping every group's exception on the
     * course exercise. A teacher removing one group's extension would silently remove the others'.
     */
    @Test
    fun `removing one group's exception leaves another group's alone`() {
        groupException(groupA, isExceptionHard = true, hard = past)
        groupException(groupB, isExceptionHard = true, hard = future)

        val r = api.deleteWithBody(
            "/v2/courses/$courseId/exercises/$ceId/exception",
            api.body("exception_groups" to listOf(groupA)),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, r.status) { r.body }

        assertEquals(0, groupExceptionCount(groupA))
        assertEquals(1, groupExceptionCount(groupB)) { "the other group's exception must survive" }
    }

    /**
     * The two halves must stay independent: a request naming only students must not touch group rows.
     *
     * This is the same asymmetry from the other direction, and it is worth pinning because the fix
     * makes the two branches look alike for the first time — at which point merging them is tempting.
     */
    @Test
    fun `removing a student exception leaves group exceptions alone`() {
        groupException(groupA, isExceptionHard = true, hard = past)
        transaction {
            CourseExerciseExceptionStudent.insert {
                it[courseExercise] = EntityID(ceId, core.db.CourseExercise)
                it[student] = EntityID(alice, core.db.Account)
                it[isExceptionHardDeadline] = true
                it[hardDeadline] = past
                it[isExceptionSoftDeadline] = false
                it[isExceptionStudentVisibleFrom] = false
            }
        }

        val r = api.deleteWithBody(
            "/v2/courses/$courseId/exercises/$ceId/exception",
            api.body("exception_students" to listOf(alice)),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, r.status) { r.body }

        assertEquals(1, groupExceptionCount(groupA)) { "no group was named, so no group row may go" }
        val studentRows = transaction {
            CourseExerciseExceptionStudent.selectAll()
                .where { CourseExerciseExceptionStudent.courseExercise eq ceId }
                .count()
        }
        assertEquals(0L, studentRows)
    }

    /** A teacher on another course must not be able to remove these at all. */
    @Test
    fun `a teacher who is not on the course cannot remove an exception`() {
        groupException(groupA, isExceptionHard = true, hard = past)
        val outsider = "exc-outsider"
        transaction { Fixtures.teacher(outsider) }

        val r = api.deleteWithBody(
            "/v2/courses/$courseId/exercises/$ceId/exception",
            api.body("exception_groups" to listOf(groupA)),
            Auth.asTeacher(outsider),
        )
        assertFalse(r.status == 200) { "an outsider got a 200" }
        assertEquals(1, groupExceptionCount(groupA))
    }

    private fun groupExceptionCount(courseGroupId: Long): Int = transaction {
        CourseExerciseExceptionGroup.selectAll()
            .where {
                CourseExerciseExceptionGroup.courseExercise eq ceId and
                        (CourseExerciseExceptionGroup.courseGroup eq courseGroupId)
            }
            .count()
            .toInt()
    }
}
