package core.ems.service

import core.db.AutogradeActivity
import core.db.StudentCourseExercise
import core.db.Submission
import core.db.TeacherActivity
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime

/**
 * EZ-1927. The one place that knows how a student's work on an exercise is summarised, and the one
 * rule for which grade counts. Everything here runs inside the caller's transaction, on purpose:
 * the row must never disagree with the submission or activity row written beside it.
 */

/** What [StudentCourseExercise] says about one student on one exercise. */
data class WorkOnExercise(
    val latestSubmissionId: Long,
    val submissionCount: Int,
    val latestSubmissionAt: DateTime,
    val autoGrade: Int?,
    val teacherGrade: Int?,
    val teacherGradedSubmissionId: Long?,
    val flagged: Boolean,
) {
    /**
     * The grade that counts. A teacher's grade wins over the autograder's, and outlives a
     * resubmission; it was graded "directly" when the teacher graded the attempt now on top, and
     * "indirectly" when the attempt on top inherits it from an earlier one.
     */
    val grade: GradeResp?
        get() = when {
            teacherGrade != null -> GradeResp(teacherGrade, false, teacherGradedSubmissionId == latestSubmissionId)
            autoGrade != null -> GradeResp(autoGrade, true, true)
            else -> null
        }
}

fun ResultRow.toWorkOnExercise() = WorkOnExercise(
    this[StudentCourseExercise.latestSubmission].value,
    this[StudentCourseExercise.submissionCount],
    this[StudentCourseExercise.latestSubmissionAt],
    this[StudentCourseExercise.autoGrade],
    this[StudentCourseExercise.teacherGrade],
    this[StudentCourseExercise.teacherGradedSubmission]?.value,
    this[StudentCourseExercise.flagged],
)

// --- writers --------------------------------------------------------------------------------------

/**
 * Serialise every write about one student on one exercise for the rest of the transaction.
 *
 * A transaction-scoped advisory lock rather than `SELECT … FOR UPDATE`, because the first submission
 * has no row to select yet — two first submissions at once would race to insert it and one would
 * die on the primary key. The lock key is the pair itself; it is released with the transaction.
 */
fun lockWork(courseExId: Long, studentId: String) {
    TransactionManager.current().exec(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        listOf(TextColumnType() to "student_course_exercise:$courseExId:$studentId"),
    )
}

/**
 * A new attempt: it becomes the latest, the count goes up, and the auto grade is cleared because
 * nothing has graded this attempt yet. The teacher's grade stays — that is the inheritance rule,
 * written once.
 */
fun recordNewSubmission(courseExId: Long, studentId: String, submissionId: Long, createdAt: DateTime) {
    val updated = StudentCourseExercise.update({ pair(courseExId, studentId) }) {
        it[latestSubmission] = submissionId
        it[latestSubmissionAt] = createdAt
        it[autoGrade] = null
        it.update(submissionCount, submissionCount + 1)
    }
    if (updated == 0) {
        StudentCourseExercise.insert {
            it[student] = studentId
            it[courseExercise] = courseExId
            it[latestSubmission] = submissionId
            it[latestSubmissionAt] = createdAt
            it[submissionCount] = 1
            it[autoGrade] = null
            it[teacherGrade] = null
            it[teacherGradedSubmission] = null
            it[flagged] = false
        }
    }
}

/**
 * The autograder graded [submissionId]. Recorded only when that is still the latest attempt: a
 * teacher's retry on an older attempt says nothing about the one now on top.
 */
fun recordAutoGrade(courseExId: Long, studentId: String, submissionId: Long, grade: Int) {
    StudentCourseExercise.update({
        pair(courseExId, studentId) and (StudentCourseExercise.latestSubmission eq submissionId)
    }) {
        it[autoGrade] = grade
    }
}

/** A teacher graded [submissionId]. Wins over any auto grade, on this attempt and the next ones. */
fun recordTeacherGrade(courseExId: Long, studentId: String, submissionId: Long, grade: Int) {
    StudentCourseExercise.update({ pair(courseExId, studentId) }) {
        it[teacherGrade] = grade
        it[teacherGradedSubmission] = submissionId
    }
}

fun setWorkFlagged(courseExId: Long, studentId: String, flagged: Boolean) {
    StudentCourseExercise.update({ pair(courseExId, studentId) }) {
        it[StudentCourseExercise.flagged] = flagged
    }
}

private fun pair(courseExId: Long, studentId: String) =
    (StudentCourseExercise.courseExercise eq courseExId) and (StudentCourseExercise.student eq studentId)

// --- readers --------------------------------------------------------------------------------------

fun selectWork(courseExId: Long, studentId: String): WorkOnExercise? =
    StudentCourseExercise.selectAll().where { pair(courseExId, studentId) }.map { it.toWorkOnExercise() }.singleOrNull()

/** One student's work across several exercises, keyed by course exercise id. */
fun selectWorkForStudent(studentId: String, courseExIds: List<Long>): Map<Long, WorkOnExercise> =
    if (courseExIds.isEmpty()) emptyMap() else StudentCourseExercise.selectAll()
        .where { (StudentCourseExercise.student eq studentId) and (StudentCourseExercise.courseExercise inList courseExIds) }
        .associate { it[StudentCourseExercise.courseExercise].value to it.toWorkOnExercise() }

/**
 * The grade each of a student's attempts on one exercise shows on its own.
 *
 * The attempt on top shows the work's effective grade — the only one a student or teacher is asked
 * about. An earlier attempt shows what it earned itself: the teacher's grade if one was posted on
 * it, else its own auto grade, else nothing. Nothing is inherited downwards; the history is what
 * happened, and the summary row is what counts.
 */
fun selectAttemptGrades(work: WorkOnExercise?, submissionIds: List<Long>): Map<Long, GradeResp?> {
    if (submissionIds.isEmpty()) return emptyMap()

    val teacherGrades: Map<Long, Int> = TeacherActivity
        .select(TeacherActivity.submission, TeacherActivity.grade)
        .where { (TeacherActivity.submission inList submissionIds) and TeacherActivity.grade.isNotNull() }
        .orderBy(TeacherActivity.mergeWindowStart to SortOrder.ASC, TeacherActivity.id to SortOrder.ASC)
        // Ascending, so the newest activity's grade is the one left in the map.
        .associate { it[TeacherActivity.submission].value to it[TeacherActivity.grade]!! }

    val autoGrades: Map<Long, Int> = AutogradeActivity
        .select(AutogradeActivity.submission, AutogradeActivity.grade)
        .where { AutogradeActivity.submission inList submissionIds }
        .orderBy(AutogradeActivity.createdAt to SortOrder.ASC, AutogradeActivity.id to SortOrder.ASC)
        .associate { it[AutogradeActivity.submission].value to it[AutogradeActivity.grade] }

    return submissionIds.associateWith { id ->
        when {
            work != null && id == work.latestSubmissionId -> work.grade
            teacherGrades[id] != null -> GradeResp(teacherGrades.getValue(id), false, true)
            autoGrades[id] != null -> GradeResp(autoGrades.getValue(id), true, true)
            else -> null
        }
    }
}

/** The student and exercise a submission belongs to, for callers that only have its id. */
fun selectSubmissionOwner(submissionId: Long): Pair<Long, String>? = Submission
    .select(Submission.courseExercise, Submission.student)
    .where { Submission.id eq submissionId }
    .map { it[Submission.courseExercise].value to it[Submission.student].value }
    .singleOrNull()
