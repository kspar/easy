package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.db.*
import core.ems.service.cache.CachingService
import core.ems.service.cache.countSubmissionsCache
import core.ems.service.cache.countSubmissionsInAutoAssessmentCache
import core.ems.service.moodle.MoodleGradesSyncService
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime

private val log = KotlinLogging.logger {}

fun submissionExists(submissionId: Long, courseExId: Long, courseId: Long): Boolean = transaction {
    (Course innerJoin CourseExercise innerJoin Submission).selectAll()
        .where { Course.id eq courseId and (CourseExercise.id eq courseExId) and (Submission.id eq submissionId) }
        .count() == 1L
}

fun DateTime.hasSecondsPassed(seconds: Int) = this.plusSeconds(seconds).isBeforeNow

fun assertSubmissionExists(submissionId: Long, courseExId: Long, courseId: Long) {
    if (!submissionExists(submissionId, courseExId, courseId)) {
        throw InvalidRequestException(
            "No submission $submissionId found on course exercise $courseExId on course $courseId",
            ReqError.ENTITY_WITH_ID_NOT_FOUND
        )
    }
}

fun selectStudentBySubmissionId(submissionId: Long) = transaction {
    Submission
        .select(Submission.student)
        .where { Submission.id eq submissionId }
        .map { it[Submission.student] }
        .single()
}

fun selectStudentEmailBySubmissionId(submissionId: Long) = transaction {
    (Submission innerJoin Account)
        .select(Account.email)
        .where { Submission.id eq submissionId }
        .map { it[Account.email] }
        .single()
}


data class GradeResp(
    @get:JsonProperty("grade") val grade: Int,
    @get:JsonProperty("is_autograde") val isAutograde: Boolean,
    @get:JsonProperty("is_graded_directly") val isGradedDirectly: Boolean
)


/**
 * The id of each student's latest submission on this course exercise, one per student.
 *
 * **This is [selectAllCourseExercisesLatestSubmissions]'s question asked a second time**, and the two
 * answered it differently until now — the EZ-1763 fix reached one copy and not its sibling. This one
 * resolved the winner in Kotlin with `lastSub.createdAt.isBefore(it.createdAt)`. `isBefore` is strict,
 * so when two of a student's submissions shared a `created_at` **neither replaced the other** and the
 * winner was whichever row Postgres emitted first, out of a query with no `ORDER BY` at all.
 *
 * `created_at` is millisecond-resolution and a tie is ordinary: a double-click, a retry, an autograde
 * write landing beside a manual grade. EZ-1763 is the same tie found in the other implementation, as a
 * test that failed four runs in five.
 *
 * It is worse here. The only caller is the Moodle grade push — a one-way write into the university's
 * own gradebook. A grade that flickers in this application is visible and self-correcting; a wrong
 * grade written to Moodle is neither.
 *
 * So the ordering now lives in SQL and is **total**, and matches the sibling query's exactly:
 * `created_at`, then `number` — the per-student sequence, which is what "latest" is supposed to mean —
 * then `id` as a backstop. Ascending, so that `associate` keeping the last value for a repeated key
 * lands on the latest row per student, which is a documented stdlib guarantee rather than a hopeful
 * one.
 *
 * Not routed through the sibling query instead, tempting as deleting a duplicate is: that one is built
 * around a course, needs the whole student roster mapped up front, and returns grades, statuses and
 * group memberships. This caller wants submission ids for one course exercise. The duplication is the
 * two of them disagreeing, and pinning both against the same fixture is what fixes that — see
 * `ValidateSelectAllCourseExercisesLatestSubmissions`.
 */
fun selectLatestSubmissionsForExercise(courseExerciseId: Long): List<Long> =
    Submission
        .select(Submission.id, Submission.student)
        .where { Submission.courseExercise eq courseExerciseId }
        .orderBy(
            Submission.student to SortOrder.ASC,
            Submission.createdAt to SortOrder.ASC,
            Submission.number to SortOrder.ASC,
            Submission.id to SortOrder.ASC,
        )
        .associate { it[Submission.student].value to it[Submission.id].value }
        .values
        .toList()

suspend fun autoAssessAsync(
    courseExId: Long,
    solution: String,
    submissionId: Long,
    studentId: String,
    caching: CachingService,
    autoGradeScheduler: AutoGradeScheduler,
    mailService: SendMailService,
    moodleGradesSyncService: MoodleGradesSyncService
) {
    try {
        val autoExerciseId = selectAutoExId(courseExId)
        if (autoExerciseId == null) {
            insertAutoAssFailed(submissionId, caching)
            throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
        }

        log.debug { "Starting autoassessment with auto exercise id $autoExerciseId" }
        val autoAss = try {
            autoGradeScheduler.submitAndAwait(autoExerciseId, solution, PriorityLevel.AUTHENTICATED)
        } catch (e: Exception) {
            // EZ-1214, retry autoassessment automatically once if it fails
            log.error { "Autoassessment failed, retrying once more... ${e.message}" }
            autoGradeScheduler.submitAndAwait(autoExerciseId, solution, PriorityLevel.AUTHENTICATED)
        }

        log.debug { "Finished autoassessment" }
        insertAutogradeActivity(autoAss.grade, autoAss.feedback, submissionId, caching, courseExId, studentId)
    } catch (e: Exception) {
        log.error { "Autoassessment failed ${e.message}" }
        insertAutoAssFailed(submissionId, caching)
        val notification = """
                Autoassessment failed
                
                Course exercise id: $courseExId
                Submission id: $submissionId
                Solution:
                
                $solution
            """.trimIndent()
        mailService.sendSystemNotification(notification)
    }
    moodleGradesSyncService.syncSingleGradeToMoodle(submissionId)
}


fun insertSubmission(
    courseExId: Long,
    submission: String,
    studentId: String,
    autoAss: AutoGradeStatus,
    caching: CachingService
): Long =
    transaction {
        data class Grade(val grade: Int, val isAutograde: Boolean)

        fun ResultRow.extractGradeOrNull(): Grade? {
            val grade = this[Submission.grade]
            val isAuto = this[Submission.isAutoGrade]
            return if (grade != null && isAuto != null) (Grade(grade, isAuto)) else null
        }

        val lastNumber = Submission
            .select(Submission.number)
            .where {
                (Submission.courseExercise eq courseExId) and (Submission.student eq studentId)
            }
            .orderBy(Submission.number, SortOrder.DESC)
            .map { it[Submission.number] }
            .firstOrNull() ?: 0


        val previousTeacherGrade = Submission
            .select(Submission.isAutoGrade, Submission.grade)
            .where {
                (Submission.courseExercise eq courseExId) and
                        (Submission.student eq studentId) and
                        (Submission.isAutoGrade.eq(false)) and
                        (Submission.grade.isNotNull())
            }
            .orderBy(Submission.number, SortOrder.DESC)
            .limit(1)
            .map { it.extractGradeOrNull() }
            .firstOrNull()


        val time = DateTime.now()
        val submissionId = Submission.insertAndGetId {
            it[courseExercise] = courseExId
            it[student] = studentId
            it[createdAt] = time
            it[solution] = submission
            it[autoGradeStatus] = autoAss
            it[number] = lastNumber + 1
            if (previousTeacherGrade != null) {
                it[grade] = previousTeacherGrade.grade
                it[isAutoGrade] = false
                it[isGradedDirectly] = false
            }
        }.value

        val ceRow = CourseExercise
            .select(CourseExercise.exercise, CourseExercise.course)
            .where { CourseExercise.id eq courseExId }
            .single()

        val exerciseId = ceRow[CourseExercise.exercise].value
        val courseId = ceRow[CourseExercise.course].value

        Course.update({ Course.id eq courseId }) {
            it[lastSubmissionAt] = time
        }

        StatsSubmission.insert {
            it[StatsSubmission.submissionId] = submissionId
            it[StatsSubmission.courseExerciseId] = courseExId
            it[StatsSubmission.exerciseId] = exerciseId
            it[StatsSubmission.createdAt] = time
            it[StatsSubmission.studentPseudonym] = selectPseudonym(studentId)
            it[StatsSubmission.solutionLength] = submission.length
            it[StatsSubmission.hasEverReceivedTeacherComment] = false
        }
        caching.invalidate(countSubmissionsInAutoAssessmentCache)
        caching.invalidate(countSubmissionsCache)
        submissionId
    }

data class AutomaticAssessmentResp(
    @get:JsonProperty("grade") val grade: Int,
    @get:JsonProperty("feedback") val feedback: String?
)

fun getLatestAutomaticAssessmentRespOrNull(submissionId: Long) = transaction {
    AutogradeActivity.select(AutogradeActivity.grade, AutogradeActivity.feedback)
        .where { AutogradeActivity.submission eq submissionId }
        .orderBy(AutogradeActivity.createdAt to SortOrder.DESC)
        .limit(1)
        .map { AutomaticAssessmentResp(it[AutogradeActivity.grade], it[AutogradeActivity.feedback]) }
        .firstOrNull()
}