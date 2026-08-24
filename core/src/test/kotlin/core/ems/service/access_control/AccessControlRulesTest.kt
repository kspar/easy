package core.ems.service.access_control

import core.conf.security.EasyRole
import core.db.DirAccessLevel
import core.db.SolutionFileType
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.testing.Auth
import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Who may reach which course, exercise and directory — the 306 lines that decide it, against rows.
 *
 * This is the highest-consequence untested logic in the backend, and the reason is that **being
 * wrong here is silent**. A broken access rule does not throw a stack trace or return a 500; it
 * returns somebody else's data, or refuses somebody their own, and both look like working software
 * until a person notices.
 *
 * It is also the half `EndpointAuthorizationMatrixTest` structurally cannot cover. That test proves
 * no endpoint admits the wrong *role*; almost all real authorization here is finer than a role —
 * this teacher, on this course, for this exercise — and lives in these functions.
 */
@IntegrationTest
class AccessControlRulesTest {

    private val teacher = "acl-teacher"
    private val otherTeacher = "acl-other-teacher"
    private val student = "acl-student"
    private val otherStudent = "acl-other-student"
    private val admin = "acl-admin"

    private var courseId = 0L
    private var otherCourseId = 0L
    private var exerciseId = 0L
    private var courseExId = 0L
    private var dirId = 0L

    private fun asTeacher() = Auth.easyUser(teacher, EasyRole.TEACHER)
    private fun asOtherTeacher() = Auth.easyUser(otherTeacher, EasyRole.TEACHER)
    private fun asStudent() = Auth.easyUser(student, EasyRole.STUDENT)
    private fun asOtherStudent() = Auth.easyUser(otherStudent, EasyRole.STUDENT)
    private fun asAdmin() = Auth.easyUser(admin, EasyRole.ADMIN)

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.teacher(otherTeacher)
            Fixtures.student(student)
            Fixtures.student(otherStudent)
            Fixtures.admin(admin)

            courseId = Fixtures.course("Course under test")
            otherCourseId = Fixtures.course("Someone else's course")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, student)
            Fixtures.enrolTeacher(otherCourseId, otherTeacher)
            Fixtures.enrolStudent(otherCourseId, otherStudent)

            dirId = Fixtures.dir("acl-dir", implicit = false)
            exerciseId = Fixtures.exercise("ACL exercise", teacher, parentDirId = dirId)
            courseExId = Fixtures.courseExercise(courseId, exerciseId)
        }
    }

    // --- teacherOnCourse -------------------------------------------------------------------

    @Test
    fun `a teacher reaches their own course and not another`() {
        asTeacher().assertAccess { teacherOnCourse(courseId) }

        val e = assertThrows(ForbiddenException::class.java) {
            asTeacher().assertAccess { teacherOnCourse(otherCourseId) }
        }
        assertEquals(core.exception.ReqError.NO_COURSE_ACCESS, e.code)
    }

    @Test
    fun `a student never reaches a course as a teacher, not even their own`() {
        assertThrows(ForbiddenException::class.java) {
            asStudent().assertAccess { teacherOnCourse(courseId) }
        }
    }

    /**
     * Admin bypasses the access row but **not** the existence check.
     *
     * Worth pinning separately: it would be easy to "simplify" `teacherOnCourse` into an early
     * `if (isAdmin) return`, and the difference is that a typo'd course id would then read as
     * allowed rather than as not-found.
     */
    @Test
    fun `an admin reaches any course that exists, and no course that does not`() {
        asAdmin().assertAccess { teacherOnCourse(courseId) }
        asAdmin().assertAccess { teacherOnCourse(otherCourseId) }

        assertThrows(InvalidRequestException::class.java) {
            asAdmin().assertAccess { teacherOnCourse(999_999) }
        }
    }

    // --- userOnCourse / studentOnCourse ----------------------------------------------------

    @Test
    fun `userOnCourse admits an enrolled student and an enrolled teacher, and refuses outsiders`() {
        asStudent().assertAccess { userOnCourse(courseId) }
        asTeacher().assertAccess { userOnCourse(courseId) }

        assertThrows(ForbiddenException::class.java) {
            asOtherStudent().assertAccess { userOnCourse(courseId) }
        }
        assertThrows(ForbiddenException::class.java) {
            asOtherTeacher().assertAccess { userOnCourse(courseId) }
        }
    }

    /**
     * `studentOnCourse` asks only for a student-access row, so a *teacher* on the course is refused.
     *
     * That is deliberate and easy to get backwards. A teacher is not a student on their own course,
     * and endpoints under `/student/` serve the caller's own submissions.
     */
    @Test
    fun `studentOnCourse refuses a teacher of that same course`() {
        asStudent().assertAccess { studentOnCourse(courseId) }

        assertThrows(ForbiddenException::class.java) {
            asTeacher().assertAccess { studentOnCourse(courseId) }
        }
    }

    /**
     * `studentOnCourse` has **no admin bypass**, unlike `teacherOnCourse` and `userOnCourse`.
     *
     * The asymmetry is deliberate and worth pinning, because it looks like an oversight: these three
     * sit next to each other and two of them special-case admins. It is correct — endpoints under
     * `/student/` serve *the caller's own* submissions and drafts, so "admin" is not a meaningful
     * caller there; an admin with no student-access row has no submissions to fetch.
     *
     * Found by deliberately adding the bypass and watching nothing fail: no test had ever called
     * this function as an admin, so the hole was open in exactly the direction nobody was looking.
     */
    @Test
    fun `studentOnCourse has no admin bypass`() {
        assertThrows(ForbiddenException::class.java) {
            asAdmin().assertAccess { studentOnCourse(courseId) }
        }
    }

    /**
     * `userOnCourse`'s admin branch, and the half of it that is easy to lose.
     *
     * It is not "admins skip the check": it still calls `assertCourseExists`, so an admin on a stale
     * course id gets a 400 rather than being waved through to a handler that then reads no rows and
     * answers an empty page. The note above `studentOnCourse` says this function has an admin bypass;
     * until now nothing called it as an admin, so the claim was documentation rather than behaviour.
     */
    @Test
    fun `userOnCourse admits an admin to any course that exists, and refuses one that does not`() {
        asAdmin().assertAccess { userOnCourse(courseId) }
        asAdmin().assertAccess { userOnCourse(otherCourseId) }

        assertThrows(InvalidRequestException::class.java) {
            asAdmin().assertAccess { userOnCourse(999_999) }
        }
    }

    // --- the course exercise, and the student-visibility gate --------------------------------

    @Test
    fun `assertCourseExerciseIsOnCourse refuses a course exercise that is on another course`() {
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        assertThrows(InvalidRequestException::class.java) {
            assertCourseExerciseIsOnCourse(courseExId, otherCourseId)
        }
        assertThrows(InvalidRequestException::class.java) {
            assertCourseExerciseIsOnCourse(999_999, courseId)
        }
    }

    /**
     * The visibility gate: five `/student/` endpoints pass `RequireStudentVisible(caller.id)` here,
     * and this function is the whole of what stops a student reading an exercise before its
     * visible-from date — the details, the drafts, the teacher's activities on it.
     *
     * Three states, and the third is the one worth having a test for. `student_visible_from = null`
     * means "not scheduled", which must read as hidden and not as "no restriction" — the failure
     * would be silent, permissive, and indistinguishable from correct behaviour on any course whose
     * exercises are all published.
     */
    @Test
    fun `a course exercise is hidden from a student until its visible-from date`() {
        // Published at the start of the timeline: visible.
        assertCourseExerciseIsOnCourse(courseExId, courseId, RequireStudentVisible(student))

        val scheduled = transaction {
            Fixtures.courseExercise(courseId, exerciseId, orderIdx = 2, studentVisibleFrom = TestClock.farFuture())
        }
        assertThrows(InvalidRequestException::class.java) {
            assertCourseExerciseIsOnCourse(scheduled, courseId, RequireStudentVisible(student))
        }

        val unscheduled = transaction {
            Fixtures.courseExercise(courseId, exerciseId, orderIdx = 3, studentVisibleFrom = null)
        }
        assertThrows(InvalidRequestException::class.java) {
            assertCourseExerciseIsOnCourse(unscheduled, courseId, RequireStudentVisible(student))
        }

        // And without the flag, visibility is not consulted at all — this is a teacher's view.
        assertCourseExerciseIsOnCourse(scheduled, courseId)
        assertCourseExerciseIsOnCourse(unscheduled, courseId)
    }

    /**
     * `assertExerciseHasTextEditorSubmission` guards the two anonymous endpoints — reading an
     * exercise's details and submitting to it — both of which are `permitAll`. So this runs for
     * callers off the internet, and its two refusals are different exceptions on purpose: an
     * exercise that does not exist must not be distinguishable from one you cannot reach.
     */
    @Test
    fun `the anonymous surface accepts only a text-editor exercise, and does not confirm the others exist`() {
        assertExerciseHasTextEditorSubmission(exerciseId)

        val uploadId = transaction {
            Fixtures.exercise("Upload only", teacher, solutionFileType = SolutionFileType.TEXT_UPLOAD)
        }
        assertThrows(InvalidRequestException::class.java) {
            assertExerciseHasTextEditorSubmission(uploadId)
        }

        val e = assertThrows(ForbiddenException::class.java) {
            assertExerciseHasTextEditorSubmission(999_999)
        }
        assertEquals(ReqError.NO_EXERCISE_ACCESS, e.code)
    }

    // --- exerciseViaCourse -----------------------------------------------------------------

    @Test
    fun `exerciseViaCourse needs both course access and the exercise being on that course`() {
        asTeacher().assertAccess { exerciseViaCourse(exerciseId, courseId) }

        // Right exercise, wrong course. Still 403: this teacher has no access to that course, so the
        // role gate refuses before coherence is even considered.
        assertThrows(ForbiddenException::class.java) {
            asTeacher().assertAccess { exerciseViaCourse(exerciseId, otherCourseId) }
        }
        // Right course, exercise that is not on it. 400, because this is the ids not matching rather
        // than the caller lacking anything — the same distinction the two exception types draw.
        assertThrows(InvalidRequestException::class.java) {
            asTeacher().assertAccess { exerciseViaCourse(999_999, courseId) }
        }
        // Students have no business here at all, whatever the ids.
        assertThrows(ForbiddenException::class.java) {
            asStudent().assertAccess { exerciseViaCourse(exerciseId, courseId) }
        }
    }

    @Test
    fun `an admin is still bound by the exercise being on the course`() {
        // The admin arm used to be an empty `{}`, so this whole test had nothing to catch: an admin
        // passed for any pair of ids, coherent or not. The coherence half of the rule is not a
        // permission and does not get a role bypass — an exercise that is not on the named course is
        // not on it, whoever is asking.
        asAdmin().assertAccess { exerciseViaCourse(exerciseId, courseId) }

        // The pair the empty branch used to wave through. `InvalidRequestException` — a 400 — and not
        // `ForbiddenException`, matching the sibling rules: an exercise that is not on the named
        // course is an incoherent pair of ids, not a permission the caller lacks. It also keeps this
        // off the sysadmin's mail, since handleForbiddenException notifies unconditionally while
        // InvalidRequestException carries the `notify` flag this one sets to false.
        assertThrows(InvalidRequestException::class.java) {
            asAdmin().assertAccess { exerciseViaCourse(exerciseId, otherCourseId) }
        }
        assertThrows(InvalidRequestException::class.java) {
            asAdmin().assertAccess { exerciseViaCourse(999_999, courseId) }
        }
    }

    // --- the public surface ----------------------------------------------------------------

    /**
     * The only thing standing between the internet and an arbitrary exercise.
     *
     * `POST /v2/unauth/exercises/{id}/anonymous/autoassess` is `permitAll`, so this function is the
     * entire authorization for it. If it ever answered permissively for an exercise whose author had
     * not opted in, every exercise in the library would be publicly runnable.
     */
    @Test
    fun `anonymous access is refused unless the exercise opted in`() {
        assertThrows(ForbiddenException::class.java) { assertUnauthAccessToExercise(exerciseId) }
        assertThrows(ForbiddenException::class.java) { assertUnauthAccessToExercise(999_999) }

        val openId = transaction {
            Fixtures.exercise("Opted in", teacher, anonymousAutoassessEnabled = true)
        }
        assertUnauthAccessToExercise(openId)
    }

    // --- the library directory hierarchy ----------------------------------------------------

    @Test
    fun `dir access is refused without a grant and granted through a group`() {
        assertThrows(ForbiddenException::class.java) {
            asOtherTeacher().assertAccess { libraryDir(dirId, DirAccessLevel.PR) }
        }

        transaction { Fixtures.giveDirAccess(otherTeacher, dirId, DirAccessLevel.PR) }
        asOtherTeacher().assertAccess { libraryDir(dirId, DirAccessLevel.PR) }
    }

    /**
     * The levels are ordered, and a grant satisfies everything at or below it.
     *
     * `DirAccessLevel` is compared with `>=`, so the enum's *declaration order* is load-bearing —
     * reordering the constants would silently change who can write to what. This pins that.
     */
    @Test
    fun `a higher grant satisfies a lower requirement but not the reverse`() {
        transaction { Fixtures.giveDirAccess(otherTeacher, dirId, DirAccessLevel.PRAW) }
        val caller = asOtherTeacher()

        caller.assertAccess { libraryDir(dirId, DirAccessLevel.P) }
        caller.assertAccess { libraryDir(dirId, DirAccessLevel.PR) }
        caller.assertAccess { libraryDir(dirId, DirAccessLevel.PRA) }
        caller.assertAccess { libraryDir(dirId, DirAccessLevel.PRAW) }

        assertThrows(ForbiddenException::class.java) {
            caller.assertAccess { libraryDir(dirId, DirAccessLevel.PRAWM) }
        }
    }

    /**
     * Access inherits down the tree — a grant on a parent reaches its children.
     *
     * This is what makes a library usable (grant once at the top) and it is also the rule most
     * likely to be wrong in the dangerous direction, since a mistake grants *more* than intended.
     */
    @Test
    fun `access on a parent dir reaches a child dir`() {
        val childId = transaction { Fixtures.dir("child", implicit = false, parent = dirId) }

        assertThrows(ForbiddenException::class.java) {
            asOtherTeacher().assertAccess { libraryDir(childId, DirAccessLevel.PR) }
        }

        transaction { Fixtures.giveDirAccess(otherTeacher, dirId, DirAccessLevel.PR) }
        asOtherTeacher().assertAccess { libraryDir(childId, DirAccessLevel.PR) }
    }

    @Test
    fun `an admin reaches every dir without any grant`() {
        asAdmin().assertAccess { libraryDir(dirId, DirAccessLevel.PRAWM) }
        assertTrue(core.ems.service.hasAccountDirAccess(asAdmin(), dirId, DirAccessLevel.PRAWM))
        assertFalse(core.ems.service.hasAccountDirAccess(asOtherTeacher(), dirId, DirAccessLevel.P))
    }

    /**
     * The author of an exercise can open it — the most-travelled branch of `libraryExercise`, and
     * until the fixture was corrected it was untestable.
     *
     * `CreateExercise` ends by granting the creator `PRAWM` on the exercise's implicit dir. The
     * fixture omitted that, so every exercise it built was owned by somebody who could not read it,
     * and a regression removing owner access entirely would have passed the whole suite. Found in
     * review, and it also means the "no access before the grant" assertions below now hold because
     * *this* teacher lacks access rather than because nobody has any.
     */
    @Test
    fun `the author of an exercise has full access to it`() {
        asTeacher().assertAccess { libraryExercise(exerciseId, DirAccessLevel.PRAWM) }
        asTeacher().assertAccess { libraryExercise(exerciseId, DirAccessLevel.PR) }
    }

    @Test
    fun `a library exercise is reached through its own dir`() {
        assertThrows(ForbiddenException::class.java) {
            asOtherTeacher().assertAccess { libraryExercise(exerciseId, DirAccessLevel.PR) }
        }

        transaction { Fixtures.giveDirAccess(otherTeacher, dirId, DirAccessLevel.PR) }
        asOtherTeacher().assertAccess { libraryExercise(exerciseId, DirAccessLevel.PR) }
    }
}
