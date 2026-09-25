package core.ems.service

import core.db.AutogradeActivity
import core.db.StudentCourseExercise
import core.db.Submission
import core.db.TeacherActivity
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * EZ-1927. The summary row, driven through the endpoints that write it, read back through the
 * endpoints that show it — and then checked against the history it summarises.
 *
 * ### The rule, stated once
 *
 * A teacher's grade wins over the autograder's and outlives a resubmission; it is "direct" when the
 * teacher graded the attempt now on top and "indirect" when that attempt inherits it. The autograder's
 * grade belongs to one attempt and is forgotten when the next arrives. The flag marks the work.
 *
 * That rule used to be spread over three writers and five readers, and the writers disagreed: the
 * dev copy of production had 44 pairs where an auto grade had displaced a teacher's. So the last
 * test here derives every pair's answer from `submission`, `teacher_activity` and
 * `autograde_activity` the way the backfill does, and requires the table to agree — and the one
 * after it corrupts a row to prove that the comparison can say no.
 */
@IntegrationTest
class StudentCourseExerciseTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)
    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID

    private var courseId = 0L
    private var ceId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            courseId = Fixtures.course("Summary rows")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, student)
            // A teacher-graded exercise: no executor, so the grade side is entirely the teacher's.
            ceId = Fixtures.courseExercise(courseId, Fixtures.exercise("Essay", teacher), threshold = 50)
        }
    }

    // --- the endpoints ----------------------------------------------------------------------------

    private fun submit(solution: String): Long {
        val resp = api.post(
            "/v2/student/courses/$courseId/exercises/$ceId/submissions",
            api.body("solution" to solution),
            Auth.asStudent(student),
        )
        assertEquals(200, resp.status) { resp.body }
        return transaction { work().latestSubmissionId }
    }

    private fun grade(submissionId: Long, grade: Int) {
        val resp = api.post(
            "/v2/teacher/courses/$courseId/exercises/$ceId/submissions/$submissionId/grade",
            api.body("grade" to grade, "notify_student" to false),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, resp.status) { resp.body }
    }

    private fun flag(submissionId: Long, flagged: Boolean) {
        val resp = api.post(
            "/v2/teacher/courses/$courseId/exercises/$ceId/submissions/flagged",
            api.body("submissions" to listOf(mapOf("id" to submissionId.toString())), "flagged" to flagged),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, resp.status) { resp.body }
    }

    private fun work(): WorkOnExercise =
        transaction { selectWork(ceId, student) } ?: error("no summary row for the student")

    private fun json(body: String): JsonNode = jacksonObjectMapper().readTree(body)

    /** The teacher's students list, this student's row on this exercise. */
    private fun teacherListRow(): JsonNode {
        val resp = api.get("/v2/teacher/courses/$courseId/exercises", Auth.asTeacher(teacher))
        assertEquals(200, resp.status) { resp.body }
        return json(resp.body).get("exercises").single { it.get("course_exercise_id").asString() == ceId.toString() }
            .get("latest_submissions").single { it.get("student_id").asString() == student }
            .get("submission")
    }

    /** What the student sees on their course page for this exercise. */
    private fun studentExercise(): JsonNode {
        val resp = api.get("/v2/student/courses/$courseId/exercises", Auth.asStudent(student))
        assertEquals(200, resp.status) { resp.body }
        return json(resp.body).get("exercises").single { it.get("id").asString() == ceId.toString() }
    }

    /** The student's own attempt list, newest first. */
    private fun studentAttempts(): List<JsonNode> {
        val resp = api.get("/v2/student/courses/$courseId/exercises/$ceId/submissions/all", Auth.asStudent(student))
        assertEquals(200, resp.status) { resp.body }
        return json(resp.body).get("submissions").toList()
    }

    /** The teacher's view of every attempt by this student, newest first. */
    private fun teacherAttempts(): List<JsonNode> {
        val resp = api.get(
            "/v2/teacher/courses/$courseId/exercises/$ceId/submissions/all/students/$student", Auth.asTeacher(teacher)
        )
        assertEquals(200, resp.status) { resp.body }
        return json(resp.body).get("submissions").toList()
    }

    private fun JsonNode.gradeValue(): Int? = get("grade")?.takeUnless { it.isNull }?.get("grade")?.asInt()
    private fun JsonNode.gradedDirectly(): Boolean? = get("grade")?.takeUnless { it.isNull }?.get("is_graded_directly")?.asBoolean()

    // --- the rule, through the endpoints ------------------------------------------------------------

    @Test
    fun `the first submission creates the row, ungraded`() {
        val first = submit("draft 1")

        val w = work()
        assertEquals(first, w.latestSubmissionId)
        assertEquals(1, w.submissionCount)
        assertNull(w.grade)
        assertFalse(w.flagged)

        assertNull(teacherListRow().gradeValue())
        assertEquals("UNGRADED", studentExercise().get("status").asString())
    }

    @Test
    fun `a teacher's grade is direct on the attempt graded and indirect on the next one`() {
        val first = submit("draft 1")
        grade(first, 70)

        assertEquals(70, work().grade!!.grade)
        assertEquals(true, teacherListRow().gradedDirectly())
        assertEquals(70, studentExercise().gradeValue())

        val second = submit("draft 2")

        val w = work()
        assertEquals(second, w.latestSubmissionId)
        assertEquals(2, w.submissionCount)
        assertEquals(70, w.grade!!.grade) { "the teacher's grade must outlive a resubmission" }
        assertEquals(false, w.grade!!.isGradedDirectly)
        assertEquals(false, teacherListRow().gradedDirectly())
        assertEquals(70, studentExercise().gradeValue())
        assertEquals("COMPLETED", studentExercise().get("status").asString())
    }

    @Test
    fun `each attempt shows what it earned, and the one on top shows what counts`() {
        val first = submit("draft 1")
        grade(first, 70)
        submit("draft 2")

        // Newest first. The second attempt was never graded itself but is the one on top, so it
        // shows the grade that counts; the first shows the 70 it was given.
        val mine = studentAttempts()
        assertEquals(listOf(70, 70), mine.map { it.gradeValue() })
        assertEquals(listOf(false, true), mine.map { it.gradedDirectly() })

        val theirs = teacherAttempts()
        assertEquals(listOf(70, 70), theirs.map { it.gradeValue() })
        assertEquals(listOf(false, true), theirs.map { it.gradedDirectly() })

        submit("draft 3")
        // The middle attempt earned nothing of its own, and now no longer inherits either.
        assertEquals(listOf(70, null, 70), studentAttempts().map { it.gradeValue() })
    }

    @Test
    fun `grading an older attempt after a resubmission still changes the grade that counts`() {
        val first = submit("draft 1")
        grade(first, 40)
        submit("draft 2")

        // The teacher goes back to attempt 1 and raises it. Under the old row-based rule the second
        // attempt kept its stale copy of 40 — the 44 pairs found on dev. The work has one grade.
        grade(first, 90)

        assertEquals(90, work().grade!!.grade)
        assertEquals(false, work().grade!!.isGradedDirectly)
        assertEquals(90, teacherListRow().gradeValue())
        assertEquals(90, studentExercise().gradeValue())
    }

    @Test
    fun `the flag marks the work, survives a resubmission, and clears when cleared`() {
        val first = submit("draft 1")
        flag(first, true)
        assertTrue(work().flagged)

        val second = submit("draft 2")
        assertTrue(work().flagged)
        assertTrue(teacherListRow().get("flagged").asBoolean())

        flag(second, false)
        assertFalse(work().flagged)
        assertFalse(teacherListRow().get("flagged").asBoolean())
    }

    @Test
    fun `an auto grade belongs to its attempt and is forgotten by the next`() {
        // No executor here, so the autograder's side is written the way insertAutogradeActivity
        // writes it, directly. AutoGradeIntegrationTest covers the wire.
        val first = submit("draft 1")
        transaction {
            Fixtures.autogradeActivity(ceId, student, first, 55, "ok")
            recordAutoGrade(ceId, student, first, 55)
        }
        assertEquals(55, work().grade!!.grade)
        assertEquals(true, work().grade!!.isAutograde)

        val second = submit("draft 2")
        assertNull(work().grade) { "a new attempt has not been graded; the previous auto grade is not its grade" }

        // A retry on the *older* attempt does not touch the summary: it is not the one on top.
        transaction { recordAutoGrade(ceId, student, first, 99) }
        assertNull(work().grade)
        transaction { recordAutoGrade(ceId, student, second, 60) }
        assertEquals(60, work().grade!!.grade)
    }

    @Test
    fun `a teacher's grade wins over the autograder's, before and after`() {
        val first = submit("draft 1")
        transaction { recordAutoGrade(ceId, student, first, 55) }
        grade(first, 80)
        assertEquals(80, work().grade!!.grade)

        val second = submit("draft 2")
        transaction { recordAutoGrade(ceId, student, second, 100) }
        assertEquals(80, work().grade!!.grade) { "an auto grade on a later attempt must not displace the teacher's" }
        assertEquals(false, work().grade!!.isGradedDirectly)
    }

    // --- the table against the history it summarises --------------------------------------------------

    /**
     * What every pair's row *should* say, derived from the three history tables the same way the
     * Liquibase backfill (250926-1) derives it. Independent of the writers, on purpose: it is the
     * writers being checked.
     */
    private fun deriveFromHistory(): Map<Pair<String, Long>, WorkOnExercise> = transaction {
        val attempts = Submission
            .select(Submission.id, Submission.student, Submission.courseExercise, Submission.number, Submission.createdAt, Submission.flagged)
            .orderBy(Submission.number to SortOrder.ASC, Submission.id to SortOrder.ASC)
            .groupBy { it[Submission.student].value to it[Submission.courseExercise].value }

        attempts.mapValues { (pair, rows) ->
            val latest = rows.last()
            val latestId = latest[Submission.id].value

            val newestTeacher = TeacherActivity
                .select(TeacherActivity.grade, TeacherActivity.submission)
                .where {
                    (TeacherActivity.student eq pair.first) and (TeacherActivity.courseExercise eq pair.second) and
                            TeacherActivity.grade.isNotNull()
                }
                .orderBy(TeacherActivity.mergeWindowStart to SortOrder.DESC, TeacherActivity.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            val newestAuto = AutogradeActivity
                .select(AutogradeActivity.grade)
                .where { AutogradeActivity.submission eq latestId }
                .orderBy(AutogradeActivity.createdAt to SortOrder.DESC, AutogradeActivity.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            WorkOnExercise(
                latestSubmissionId = latestId,
                submissionCount = rows.size,
                latestSubmissionAt = latest[Submission.createdAt],
                autoGrade = newestAuto?.get(AutogradeActivity.grade),
                teacherGrade = newestTeacher?.get(TeacherActivity.grade),
                teacherGradedSubmissionId = newestTeacher?.get(TeacherActivity.submission)?.value,
                flagged = rows.any { it[Submission.flagged] },
            )
        }
    }

    private fun stored(): Map<Pair<String, Long>, WorkOnExercise> = transaction {
        StudentCourseExercise.selectAll().associate {
            (it[StudentCourseExercise.student].value to it[StudentCourseExercise.courseExercise].value) to it.toWorkOnExercise()
        }
    }

    /** Every pair, or a message naming the first one that disagrees. */
    private fun disagreement(): String? {
        val derived = deriveFromHistory()
        val table = stored()
        if (derived.keys != table.keys) return "pairs differ: history has ${derived.keys}, table has ${table.keys}"
        return derived.entries.firstNotNullOfOrNull { (pair, expected) ->
            val actual = table.getValue(pair)
            // The timestamp is compared to the millisecond the history has, nothing finer.
            if (expected.copy(latestSubmissionAt = actual.latestSubmissionAt) != actual || expected.latestSubmissionAt.millis != actual.latestSubmissionAt.millis)
                "$pair: history says $expected, table says $actual"
            else null
        }
    }

    @Test
    fun `after a full scenario the table agrees with the history on every pair`() {
        val other = "other-student"
        val ce2 = transaction {
            Fixtures.student(other)
            Fixtures.enrolStudent(courseId, other)
            Fixtures.courseExercise(courseId, Fixtures.exercise("Second", teacher), orderIdx = 2)
        }

        val first = submit("draft 1")
        transaction { recordAutoGrade(ceId, student, first, 55); Fixtures.autogradeActivity(ceId, student, first, 55, null) }
        grade(first, 80)
        val second = submit("draft 2")
        transaction { recordAutoGrade(ceId, student, second, 100); Fixtures.autogradeActivity(ceId, student, second, 100, null) }
        grade(first, 85)
        flag(second, true)
        submit("draft 3")
        transaction {
            Fixtures.submission(ce2, other, number = 1, grade = 30, isAutoGrade = true)
            Fixtures.submission(ce2, other, number = 2)
        }

        assertNull(disagreement())
        assertEquals(2, stored().size)
    }

    /**
     * The check has to be able to fail, or a green run says nothing. One row is put wrong by hand
     * and the comparison must name it.
     */
    @Test
    fun `the consistency check catches a row that lies`() {
        val first = submit("draft 1")
        grade(first, 70)
        assertNull(disagreement())

        transaction {
            StudentCourseExercise.update({ StudentCourseExercise.student eq student }) { it[teacherGrade] = 71 }
        }
        val found = disagreement()
        assertTrue(found != null && "71" in found) { "expected the corrupted grade to be reported, got: $found" }
    }
}
