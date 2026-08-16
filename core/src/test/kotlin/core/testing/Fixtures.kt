package core.testing

import core.db.Account
import core.db.AccountGroup
import core.db.Asset
import core.db.AutoExercise
import core.db.AutoGradeStatus
import core.db.ContainerImage
import core.db.Course
import core.db.CourseExercise
import core.db.Dir
import core.db.DirAccessLevel
import core.db.Executor
import core.db.ExecutorContainerImage
import core.db.Exercise
import core.db.ExerciseVer
import core.db.GraderType
import core.db.Group
import core.db.GroupDirAccess
import core.db.SolutionFileType
import core.db.StudentCourseAccess
import core.db.Submission
import core.db.TeacherCourseAccess
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
        parentDirId: Long? = null,
        public: Boolean = true,
        anonymousAutoassessEnabled: Boolean = false,
    ): Long {
        // Mirrors CreateExercise: an exercise owns an *implicit* dir whose **name is the exercise
        // id**, and `getImplicitDirFromExercise` finds it by that name rather than by following
        // `Exercise.dir`. A fixture that merely pointed the exercise at some dir therefore looked
        // right and made every library-access check throw NoSuchElementException on `single()`.
        //
        // The placeholder-then-rename is production's own chicken-and-egg dance: the name needs an
        // id that does not exist until the row is inserted.
        val at = TestClock.next()
        val dirId = Dir.insertAndGetId {
            it[name] = "fixture-placeholder"
            it[isImplicit] = true
            if (parentDirId != null) it[parentDir] = EntityID(parentDirId, Dir)
            it[createdAt] = at
            it[modifiedAt] = at
        }.value

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

        // The rename that makes getImplicitDirFromExercise able to find it.
        Dir.update({ Dir.id eq dirId }) { it[name] = exerciseId.toString() }

        // And the grant that makes the owner able to *reach* it — CreateExercise ends with exactly
        // this, and leaving it out built exercises nobody owned in any useful sense. It made the
        // most-travelled branch of `libraryExercise` — an author opening their own exercise —
        // impossible to test, and quietly weakened every "no access before the grant" assertion,
        // which held because nobody had access rather than because ownership was being checked.
        grantDirAccess(implicitGroupOf(ownerId), dirId, DirAccessLevel.PRAWM)
        return exerciseId
    }

    /**
     * An auto-graded exercise, its `automatic_exercise` row and the container image it names.
     * Returns the exercise id.
     *
     * Four rows and a join table, because "which executor can grade this?" is answered by
     * `AutoExercise ⋈ ContainerImage ⋈ ExecutorContainerImage ⋈ Executor` — an exercise names an
     * image, an executor declares the images it can run, and an executor with no matching image is
     * invisible to the scheduler. That indirection is easy to half-build: leave out the join row and
     * `chooseOptimalExecutor` throws `NoExecutorsException`, which the submit path swallows into a
     * FAILED status, so the test sees the same symptom as a genuinely broken executor.
     */
    fun autoExercise(
        title: String,
        ownerId: String,
        gradingScript: String = "print('grading')",
        containerImageId: String = "test-image",
        anonymousAutoassessEnabled: Boolean = false,
        anonymousAutoassessTemplate: String = "",
    ): Long {
        ContainerImage.insertIgnore { it[id] = EntityID(containerImageId, ContainerImage) }

        val autoExerciseId = AutoExercise.insertAndGetId {
            it[AutoExercise.gradingScript] = gradingScript
            it[containerImage] = EntityID(containerImageId, ContainerImage)
            it[maxTime] = 6
            it[maxMem] = 300
        }.value

        val exerciseId = exercise(
            title, ownerId,
            anonymousAutoassessEnabled = anonymousAutoassessEnabled,
        )

        Exercise.update({ Exercise.id eq exerciseId }) {
            it[Exercise.anonymousAutoassessTemplate] = anonymousAutoassessTemplate
        }
        ExerciseVer.update({ ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }) {
            it[graderType] = GraderType.AUTO
            it[ExerciseVer.autoExerciseId] = EntityID(autoExerciseId, AutoExercise)
        }
        return exerciseId
    }

    /** A file the grading script is handed alongside the submission. */
    fun asset(exerciseId: Long, fileName: String, fileContent: String) {
        val autoExerciseId = (Exercise innerJoin ExerciseVer)
            .select(ExerciseVer.autoExerciseId)
            .where { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId]!!.value }
            .single()

        Asset.insert {
            it[autoExercise] = EntityID(autoExerciseId, AutoExercise)
            it[Asset.fileName] = fileName
            it[Asset.fileContent] = fileContent
        }
    }

    /**
     * An executor able to run [containerImageId], pointed at [baseUrl].
     *
     * Note what registering one does *not* do: `AutoGradeScheduler` keeps its queues in memory and
     * learns about rows on a 60-second timer, so a test that inserts this must call
     * `syncExecutorsFromDB()` before submitting or the scheduler will not know it exists.
     */
    fun executor(
        name: String,
        baseUrl: String,
        maxLoad: Int = 4,
        drain: Boolean = false,
        containerImageId: String = "test-image",
    ): Long {
        ContainerImage.insertIgnore { it[id] = EntityID(containerImageId, ContainerImage) }

        val executorId = Executor.insertAndGetId {
            it[Executor.name] = name
            it[Executor.baseUrl] = baseUrl
            it[Executor.maxLoad] = maxLoad
            it[Executor.drain] = drain
        }.value

        ExecutorContainerImage.insert {
            it[executor] = EntityID(executorId, Executor)
            it[containerImage] = EntityID(containerImageId, ContainerImage)
        }
        return executorId
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

    /**
     * A group, and the account-to-group and group-to-dir rows that make library access work.
     *
     * Dir access is never granted to an account directly — it is granted to a *group*, and every
     * account has an implicit group of its own. So "give this teacher read access to that dir"
     * is three rows, which is exactly the sort of thing a test should not have to remember.
     */
    fun group(name: String, implicit: Boolean = false): Long = Group.insertAndGetId {
        it[Group.name] = name
        it[isImplicit] = implicit
        it[createdAt] = TestClock.next()
    }.value

    fun addToGroup(accountId: String, groupId: Long, isManager: Boolean = false) {
        AccountGroup.insert {
            it[account] = EntityID(accountId, Account)
            it[group] = EntityID(groupId, Group)
            it[AccountGroup.isManager] = isManager
            it[createdAt] = TestClock.next()
        }
    }

    fun grantDirAccess(groupId: Long, dirId: Long, level: DirAccessLevel) {
        GroupDirAccess.insert {
            it[group] = EntityID(groupId, Group)
            it[dir] = EntityID(dirId, Dir)
            it[GroupDirAccess.level] = level
            it[createdAt] = TestClock.next()
        }
    }

    /**
     * The account's implicit group, created once and reused.
     *
     * Production gives every account **exactly one** implicit group, named the account id
     * (`account_checkin.kt`), and `getImplicitGroupFromAccount` finds it with
     * `name eq accountId and isImplicit` followed by `.single()`. An earlier version of this file
     * minted a fresh group per grant with a decorated name, which broke that invariant two ways: no
     * fixture account had the group production guarantees, and a second grant produced a second
     * group — so any test driving `CreateExercise`, `CreateDir` or `PutDirAccess` would have died on
     * that `.single()` rather than exercising the endpoint.
     */
    fun implicitGroupOf(accountId: String): Long {
        val existing = Group
            .select(Group.id)
            .where { Group.name eq accountId and Group.isImplicit }
            .map { it[Group.id].value }
            .singleOrNull()
        if (existing != null) return existing

        val id = group(accountId, implicit = true)
        addToGroup(accountId, id)
        return id
    }

    /** The common case: give one account one access level to one dir, via its implicit group. */
    fun giveDirAccess(accountId: String, dirId: Long, level: DirAccessLevel) =
        grantDirAccess(implicitGroupOf(accountId), dirId, level)

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
