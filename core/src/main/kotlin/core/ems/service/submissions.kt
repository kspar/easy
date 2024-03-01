package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.db.*
import core.ems.service.cache.CachingService
import core.ems.service.cache.countSubmissionsCache
import core.ems.service.cache.countSubmissionsInAutoAssessmentCache
import core.ems.service.moodle.MoodleGradesSyncService
import core.exception.InvalidRequestException
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

private val log = KotlinLogging.logger {}

fun submissionExists(submissionId: Long, courseExId: Long, courseId: Long): Boolean = transaction {
    (Course innerJoin CourseExercise innerJoin Submission).select {
        Course.id eq courseId and (CourseExercise.id eq courseExId) and (Submission.id eq submissionId)
    }.count() == 1L
}

fun DateTime.hasSecondsPassed(seconds: Int) = this.plusSeconds(seconds).isAfterNow

fun assertSubmissionExists(submissionId: Long, courseExId: Long, courseId: Long) {
    if (!submissionExists(submissionId, courseExId, courseId)) {
        throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
    }
}

fun selectStudentBySubmissionId(submissionId: Long) =
    transaction {
        Submission
            .slice(Submission.student)
            .select { Submission.id eq submissionId }
            .map { it[Submission.student] }
            .single()
    }


data class GradeResp(
    @JsonProperty("grade") val grade: Int,
    @JsonProperty("is_autograde") val isAutograde: Boolean,
    @JsonProperty("is_graded_directly") val isGradedDirectly: Boolean
)


/**
 * Return latest submissions for this exercise.
 */
fun selectLatestSubmissionsForExercise(courseExerciseId: Long): List<Long> {
    data class SubmissionPartial(val id: Long, val studentId: String, val createdAt: DateTime)

    // student_id -> submission
    val latestSubmissions = HashMap<String, SubmissionPartial>()

    Submission
        .slice(
            Submission.id,
            Submission.student,
            Submission.createdAt,
            Submission.courseExercise
        )
        .select { Submission.courseExercise eq courseExerciseId }
        .map {
            SubmissionPartial(
                it[Submission.id].value,
                it[Submission.student].value,
                it[Submission.createdAt]
            )
        }
        .forEach {
            val lastSub = latestSubmissions[it.studentId]
            if (lastSub == null || lastSub.createdAt.isBefore(it.createdAt)) {
                latestSubmissions[it.studentId] = it
            }
        }

    return latestSubmissions.values.map { it.id }
}

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
            insertAutoAssFailed(submissionId, caching, studentId, courseExId)
            throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
        }

        log.debug { "Starting autoassessment with auto exercise id $autoExerciseId" }
        val autoAss = try {
            autoGradeScheduler.submitAndAwait(autoExerciseId, solution, PriorityLevel.AUTHENTICATED)
        } catch (e: Exception) {
            // EZ-1214, retry autoassessment automatically once if it fails
            log.error("Autoassessment failed, retrying once more...", e)
            autoGradeScheduler.submitAndAwait(autoExerciseId, solution, PriorityLevel.AUTHENTICATED)
        }

        log.debug { "Finished autoassessment" }
        insertAutoAssessment(autoAss.grade, autoAss.feedback, submissionId, caching, courseExId, studentId)
    } catch (e: Exception) {
        log.error("Autoassessment failed", e)
        insertAutoAssFailed(submissionId, caching, studentId, courseExId)
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

        val (previousGrade, lastNumber) = (
                Submission.slice(
                    Submission.number,
                    Submission.isAutoGrade,
                    Submission.grade
                ).select {
                    (Submission.courseExercise eq courseExId) and (Submission.student eq studentId)
                }.orderBy(Submission.number, SortOrder.DESC)
                    .map {
                        it.extractGradeOrNull() to it[Submission.number]
                    }.firstOrNull() ?: (null to 0)
                )

        val time = DateTime.now()
        val setGrade = previousGrade != null && !previousGrade.isAutograde

        val submissionId = Submission.insertAndGetId {
            it[courseExercise] = courseExId
            it[student] = studentId
            it[createdAt] = time
            it[solution] = submission
            it[autoGradeStatus] = autoAss
            it[number] = lastNumber + 1
            if (setGrade) {
                it[grade] = previousGrade?.grade
                it[isAutoGrade] = false
                it[isGradedDirectly] = false
            }
        }.value

        val exerciseId = CourseExercise
            .slice(CourseExercise.exercise)
            .select { CourseExercise.id eq courseExId }
            .map { it[CourseExercise.exercise] }
            .single()
            .value

        StatsSubmission.insert {
            it[StatsSubmission.submissionId] = submissionId
            it[StatsSubmission.courseExerciseId] = courseExId
            it[StatsSubmission.exerciseId] = exerciseId
            it[StatsSubmission.createdAt] = time
            if (setGrade) it[StatsSubmission.points] = previousGrade?.grade
        }
        caching.invalidate(countSubmissionsInAutoAssessmentCache)
        caching.invalidate(countSubmissionsCache)
        submissionId
    }

