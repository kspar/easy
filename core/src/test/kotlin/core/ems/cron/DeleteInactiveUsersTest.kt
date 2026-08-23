package core.ems.cron

import core.db.Account
import core.db.CourseExercise
import core.db.DirAccessLevel
import core.db.Group
import core.db.GroupDirAccess
import core.db.Submission
import core.db.TeacherInlineComment
import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The retention cron's database stage, on data shaped like the data it will actually meet.
 *
 * **What this exists to catch is a total failure, not a partial one.** The stage is one transaction
 * over the whole batch, so a foreign key it forgets does not lose one account — it rolls back every
 * account, every night, while logging a stack trace in a scheduled job nobody reads. That is where
 * `teacher_inline_comment` had it: three foreign keys, none of them cascading, on a table added after
 * this cron was written. Removing the two statements that handle it makes every test in this class
 * fail, which is the point — the symptom is not a missing comment, it is a retention policy that has
 * silently stopped running.
 *
 * So the fixture below is not minimal. It deliberately gives every departing account the referencing
 * rows a real one has — a directory grant to its implicit group, submissions, inline comments written
 * by it and about it — because a fixture that only creates an `account` row passes against a cron
 * that cannot delete anybody. [everyReferencingRowIsAccountedFor] is the assertion that matters: the
 * accounts are gone, which can only happen if nothing blocked the transaction.
 *
 * [the implicit group and its directory grants go with the account] earns its place for the opposite
 * reason. `fk_group_exercise_dir_access_group` **does** cascade — changeset `160525-1` replaced it
 * precisely to make it — so the cron is right to leave those rows to the database, and this test is
 * what says so rather than leaving the next reader to presume it a second time. A review of this file
 * read the original constraint and not its replacement, and concluded the opposite.
 *
 * Only the database stage is called. `cron()` goes on to talk to Keycloak, which the test
 * configuration points at loopback port 1 on purpose.
 */
/** Outside every retention window the cron has or is likely to grow, and it stays that way. */
private val LONG_INACTIVE = DateTime.parse("2000-01-01T00:00:00Z")

@IntegrationTest
class DeleteInactiveUsersTest(@Autowired private val cron: DeleteInactiveUsers) {

    private val staleTeacher = "stale-teacher"
    private val staleStudent = "stale-student"
    private val activeTeacher = "active-teacher"
    private val activeStudent = "active-student"

    private var staleStudentSubmission = 0L
    private var activeStudentSubmission = 0L
    private var staleTeacherGroup = 0L
    private var activeTeacherGroup = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(staleTeacher)
            Fixtures.teacher(activeTeacher)
            Fixtures.student(staleStudent)
            Fixtures.student(activeStudent)

            // `Fixtures.account` stamps `last_seen` from the test clock, which is inside both
            // retention windows, so a qualifying account has to be aged deliberately.
            //
            // A fixed date in the distant past rather than `DateTime.now().minusYears(n)`: the cron
            // reads the wall clock, `NoWallClockInFixturesTest` forbids the test from doing the same,
            // and nothing here needs to be near a boundary. The cost is that this does not
            // distinguish the two-year student window from the five-year teacher one — both accounts
            // are far outside both — so the window arithmetic itself stays untested.
            ageOut(staleTeacher)
            ageOut(staleStudent)

            val courseId = Fixtures.course("A course")
            Fixtures.enrolTeacher(courseId, activeTeacher)
            Fixtures.enrolStudent(courseId, staleStudent)
            Fixtures.enrolStudent(courseId, activeStudent)

            val exerciseId = Fixtures.exercise("Sum of two numbers", activeTeacher)
            val courseExId = Fixtures.courseExercise(courseId, exerciseId)

            staleStudentSubmission = Fixtures.submission(courseExId, staleStudent, number = 1)
            activeStudentSubmission = Fixtures.submission(courseExId, activeStudent, number = 1)

            // Blocker 1: authoring anything in the library grants the author's implicit group access
            // to a directory, and that grant outlives the group unless the cron removes it.
            Fixtures.giveDirAccess(staleTeacher, Fixtures.dir("The stale teacher's directory"), DirAccessLevel.PRAWM)
            staleTeacherGroup = Fixtures.implicitGroupOf(staleTeacher)

            // The same grant for somebody who is staying, so that "the grant is gone" cannot pass by
            // the cron having removed every grant on the instance.
            Fixtures.giveDirAccess(activeTeacher, Fixtures.dir("The active teacher's directory"), DirAccessLevel.PRAWM)
            activeTeacherGroup = Fixtures.implicitGroupOf(activeTeacher)

            // Blocker 2, both directions: a comment *on* the departing student's submission, and a
            // comment *by* the departing teacher on somebody who is staying.
            inlineComment(courseExId, staleStudentSubmission, author = activeTeacher)
            inlineComment(courseExId, activeStudentSubmission, author = staleTeacher)
        }
    }

    private fun ageOut(accountId: String) {
        Account.update({ Account.id eq accountId }) {
            it[lastSeen] = LONG_INACTIVE
        }
    }

    private fun inlineComment(courseExId: Long, submissionId: Long, author: String) {
        TeacherInlineComment.insert {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[submission] = EntityID(submissionId, Submission)
            it[teacher] = EntityID(author, Account)
            it[createdAt] = TestClock.next()
            it[lineStart] = 1
            it[lineEnd] = 1
            it[code] = "print(a + b)"
            it[textMd] = "Check the negative case."
            it[textHtml] = "<p>Check the negative case.</p>"
        }
    }

    private fun accountIds(): List<String> = transaction {
        Account.selectAll().map { it[Account.id].value }
    }

    @Test
    fun everyReferencingRowIsAccountedFor() {
        val deleted = cron.deleteInactiveAccountsFromDb()

        assertEquals(setOf(staleTeacher, staleStudent), deleted.toSet())

        val remaining = accountIds()
        assertTrue(remaining.contains(activeTeacher)) { remaining.toString() }
        assertTrue(remaining.contains(activeStudent)) { remaining.toString() }
        assertTrue(remaining.contains(cron.defaultUser)) { "the placeholder account: $remaining" }
        assertTrue(!remaining.contains(staleTeacher)) { remaining.toString() }
        assertTrue(!remaining.contains(staleStudent)) { remaining.toString() }
    }

    @Test
    fun `the implicit group and its directory grants go with the account`() {
        cron.deleteInactiveAccountsFromDb()

        transaction {
            val groups = Group.selectAll().map { it[Group.name] }
            assertTrue(!groups.contains(staleTeacher)) { "implicit group survived: $groups" }

            val grantedTo = GroupDirAccess.selectAll().map { it[GroupDirAccess.group].value }
            assertTrue(!grantedTo.contains(staleTeacherGroup)) { "a directory grant was left behind: $grantedTo" }
            // And not by having deleted every grant on the instance, which would pass the line above
            // while breaking everyone still using the library.
            assertTrue(grantedTo.contains(activeTeacherGroup)) { "an active account lost its grant: $grantedTo" }
        }
    }

    @Test
    fun `an inline comment goes when its student goes and changes hands when its author does`() {
        cron.deleteInactiveAccountsFromDb()

        transaction {
            val comments = TeacherInlineComment.selectAll()
                .map { it[TeacherInlineComment.submission].value to it[TeacherInlineComment.teacher].value }

            assertEquals(1, comments.size) { "expected only the surviving student's comment: $comments" }
            val (submissionId, author) = comments.single()
            assertEquals(activeStudentSubmission, submissionId)
            // Written by the departing teacher, about a student who is staying: the comment is the
            // student's feedback and stays, reattributed the same way TeacherActivity is.
            assertEquals(cron.defaultUser, author)
        }
    }

    @Test
    fun `an account inside its retention window is left alone`() {
        // The control. Everything above would also pass if the cron deleted every account it saw.
        val deleted = cron.deleteInactiveAccountsFromDb()

        assertTrue(!deleted.contains(activeTeacher)) { deleted.toString() }
        assertTrue(!deleted.contains(activeStudent)) { deleted.toString() }
    }
}
