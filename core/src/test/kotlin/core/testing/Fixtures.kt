package core.testing

import core.db.Account
import core.db.AutoGradeStatus
import core.db.Course
import core.db.CourseExercise
import core.db.Dir
import core.db.Exercise
import core.db.ExerciseVer
import core.db.GraderType
import core.db.SolutionFileType
import core.db.StudentCourseAccess
import core.db.Submission
import core.db.TeacherCourseAccess
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.joda.time.DateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A clock for fixtures, so that "later" is a fact rather than a hope.
 *
 * Every timestamp a fixture writes comes from here, and each call is a minute after the last.
 * `DateTime.now()` is banned in the test source set — [NoWallClockInFixturesTest] enforces it —
 * because the alternative to an explicit timeline is the bug this whole thing was named for:
 * `ValidateSelectAllCourseExercisesLatestSubmissions` inserted two submissions with `DateTime.now()`
 * back to back, they landed in the same millisecond, and "the latest submission" became a coin toss
 * between grade 71 and grade 81 — 4 failures in 5 runs (EZ-1763).
 *
 * Note what this does *not* do: it does not make the production query correct. The query was
 * separately wrong, and is fixed in courses.kt. A fixture that merely spaced the rows out would
 * have hidden that rather than found it, which is the argument against reaching for a
 * `Thread.sleep` when a test like this flakes.
 *
 * Fixed seed instant, so a failure reproduces and a printed timestamp means something.
 */
object TestClock {
    private val EPOCH: DateTime = DateTime.parse("2026-01-01T09:00:00Z")
    private val tick = AtomicInteger(0)

    /** The next instant, one minute after the previous one. */
    fun next(): DateTime = EPOCH.plusMinutes(tick.getAndIncrement())

    /** The same instant every time — for asserting on ties on purpose. */
    fun fixed(minutesFromEpoch: Int): DateTime = EPOCH.plusMinutes(minutesFromEpoch)

    /** Called between tests so ids and instants do not drift across a run. */
    fun reset() = tick.set(0)
}

/**
 * Rows to test against.
 *
 * Replaces ~200 lines of inlined `Table.insert {}` that every database-backed test would otherwise
 * have grown its own copy of. Three properties it is built for, in order:
 *
 * 1. **Everything boring has a default.** An `account` row needs `pseudonym`, `idMigrationDone`,
 *    `createdAt` and `lastSeen` on every insert; a test about grading should mention none of them.
 * 2. **Ids are returned, never guessed.** The test this replaced computed `ce2Id = ce1Id + 1`,
 *    which was a coincidence held in place by insertion order.
 * 3. **It reads like the scenario.** The docblock a test writes ("student 1 has two submissions,
 *    71 then 81") should be recognisable in the code underneath it.
 *
 * Deliberately grown by use rather than designed up front — add what the next test needs.
 * Everything here assumes it is called inside a `transaction { }`.
 */
object Fixtures {

    fun student(id: String, givenName: String = "Given", familyName: String = "Family"): String =
        account(id, givenName, familyName, isStudent = true)

    fun teacher(id: String, givenName: String = "Given", familyName: String = "Family"): String =
        account(id, givenName, familyName, isTeacher = true)

    fun admin(id: String, givenName: String = "Given", familyName: String = "Family"): String =
        account(id, givenName, familyName, isTeacher = true, isAdmin = true)

    fun account(
        id: String,
        givenName: String = "Given",
        familyName: String = "Family",
        isStudent: Boolean = false,
        isTeacher: Boolean = false,
        isAdmin: Boolean = false,
    ): String {
        val at = TestClock.next()
        Account.insert {
            it[Account.id] = EntityID(id, Account)
            it[email] = "$id@example.test"
            it[Account.givenName] = givenName
            it[Account.familyName] = familyName
            it[createdAt] = at
            it[lastSeen] = at
            it[idMigrationDone] = true
            it[Account.isStudent] = isStudent
            it[Account.isTeacher] = isTeacher
            it[Account.isAdmin] = isAdmin
            it[pseudonym] = UUID.randomUUID().toString().replace("-", "")
        }
        return id
    }

    /** An exercise library directory. Implicit dirs are the ones an exercise creates for itself. */
    fun dir(name: String = "dir", implicit: Boolean = true, parent: Long? = null): Long {
        val at = TestClock.next()
        return Dir.insertAndGetId {
            it[Dir.name] = name
            it[isImplicit] = implicit
            if (parent != null) it[parentDir] = EntityID(parent, Dir)
            it[createdAt] = at
            it[modifiedAt] = at
        }.value
    }

    /**
     * A library exercise and its current version. Returns the exercise id.
     *
     * The version row is what carries the title, so an exercise without one is invisible to every
     * read in core — they all find the current version by `valid_to IS NULL`.
     */
    fun exercise(
        title: String,
        ownerId: String,
        dirId: Long = dir(name = title),
        public: Boolean = true,
        anonymousAutoassessEnabled: Boolean = false,
    ): Long {
        val exerciseId = Exercise.insertAndGetId {
            it[dir] = EntityID(dirId, Dir)
            it[owner] = EntityID(ownerId, Account)
            it[createdAt] = TestClock.next()
            it[Exercise.public] = public
            it[Exercise.anonymousAutoassessEnabled] = anonymousAutoassessEnabled
        }.value

        ExerciseVer.insert {
            it[exercise] = EntityID(exerciseId, Exercise)
            it[author] = EntityID(ownerId, Account)
            it[validFrom] = TestClock.next()
            it[graderType] = GraderType.TEACHER
            it[ExerciseVer.title] = title
            it[textHtml] = "<p>$title</p>"
            it[solutionFileName] = "solution.py"
            it[solutionFileType] = SolutionFileType.TEXT_EDITOR
        }
        return exerciseId
    }

    fun course(title: String, alias: String? = null): Long = Course.insertAndGetId {
        it[Course.title] = title
        it[Course.alias] = alias
        it[createdAt] = TestClock.next()
        it[moodleSyncStudents] = false
        it[moodleSyncGrades] = false
        it[moodleSyncStudentsInProgress] = false
        it[moodleSyncGradesInProgress] = false
        it[archived] = false
        it[color] = "#137EF9"
    }.value

    /** Puts a library exercise on a course. Returns the course-exercise id. */
    fun courseExercise(
        courseId: Long,
        exerciseId: Long,
        threshold: Int = 100,
        titleAlias: String? = null,
        orderIdx: Int = 1,
        studentVisibleFrom: DateTime? = TestClock.fixed(0),
        assessmentsStudentVisible: Boolean = true,
    ): Long {
        val at = TestClock.next()
        return CourseExercise.insertAndGetId {
            it[course] = EntityID(courseId, Course)
            it[exercise] = EntityID(exerciseId, Exercise)
            it[createdAt] = at
            it[modifiedAt] = at
            it[gradeThreshold] = threshold
            it[CourseExercise.studentVisibleFrom] = studentVisibleFrom
            it[CourseExercise.orderIdx] = orderIdx
            it[CourseExercise.assessmentsStudentVisible] = assessmentsStudentVisible
            it[CourseExercise.titleAlias] = titleAlias
        }.value
    }

    fun enrolStudent(courseId: Long, studentId: String) {
        StudentCourseAccess.insert {
            it[student] = EntityID(studentId, Account)
            it[course] = EntityID(courseId, Course)
            it[createdAt] = TestClock.next()
        }
    }

    fun enrolTeacher(courseId: Long, teacherId: String) {
        TeacherCourseAccess.insert {
            it[teacher] = EntityID(teacherId, Account)
            it[course] = EntityID(courseId, Course)
            it[createdAt] = TestClock.next()
        }
    }

    /**
     * A student submission. Returns its id.
     *
     * [number] is the per-student sequence within a course exercise and is the intended meaning of
     * "latest"; callers pass it explicitly so that a test about ordering says what it means.
     * [createdAt] defaults to the next tick, so consecutive calls are genuinely ordered — pass an
     * explicit value to build a tie on purpose.
     */
    fun submission(
        courseExerciseId: Long,
        studentId: String,
        number: Int,
        grade: Int? = null,
        createdAt: DateTime = TestClock.next(),
        solution: String = "print('hello')",
        isAutoGrade: Boolean = false,
        isGradedDirectly: Boolean = true,
        seen: Boolean = false,
        autoGradeStatus: AutoGradeStatus = AutoGradeStatus.NONE,
    ): Long = Submission.insertAndGetId {
        it[courseExercise] = EntityID(courseExerciseId, CourseExercise)
        it[student] = EntityID(studentId, Account)
        it[Submission.createdAt] = createdAt
        it[Submission.solution] = solution
        it[Submission.autoGradeStatus] = autoGradeStatus
        it[Submission.grade] = grade
        it[Submission.isAutoGrade] = if (grade == null) null else isAutoGrade
        it[Submission.isGradedDirectly] = if (grade == null) null else isGradedDirectly
        it[Submission.seen] = seen
        it[Submission.number] = number
    }.value
}
