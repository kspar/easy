package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoAssessStatusObserver
import core.aas.AutoGradeScheduler
import core.aas.ObserverCallerType
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertCourseExerciseIsOnCourse
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.cache.CachingService
import core.ems.service.cache.countSubmissionsCache
import core.ems.service.cache.countSubmissionsInAutoAssessmentCache
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.moodle.MoodleGradesSyncService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentSubmitCont(
    private val autoAssessStatusObserver: AutoAssessStatusObserver,
    private val cachingService: CachingService,
    private val autoGradeScheduler: AutoGradeScheduler,
    private val moodleGradesSyncService: MoodleGradesSyncService
) {

    data class Req(@JsonProperty("solution", required = true) @field:Size(max = 300000) val solution: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody solutionBody: Req, caller: EasyUser
    ) {

        log.debug { "Creating new submission by ${caller.id} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        submitSolution(courseExId, solutionBody.solution, caller.id)
    }

    private fun submitSolution(courseExId: Long, solution: String, studentId: String) {
        when (selectGraderType(courseExId)) {
            GraderType.TEACHER -> {
                log.debug { "Creating new submission to teacher-graded exercise $courseExId by $studentId" }
                insertSubmission(courseExId, solution, studentId, AutoGradeStatus.NONE, cachingService)
            }
            GraderType.AUTO -> {
                log.debug { "Creating new submission to autograded exercise $courseExId by $studentId" }
                val submissionId =
                    insertSubmission(courseExId, solution, studentId, AutoGradeStatus.IN_PROGRESS, cachingService)

                val deferred: Job =
                    GlobalScope.launch {
                        autoAssessAsync(courseExId, solution, submissionId)
                    }
                // add deferred to autoAssessStatusObserver
                autoAssessStatusObserver.put(submissionId, ObserverCallerType.STUDENT, deferred)
            }
        }
    }

    suspend fun autoAssessAsync(courseExId: Long, solution: String, submissionId: Long) {
        try {
            val autoExerciseId = selectAutoExId(courseExId)
            if (autoExerciseId == null) {
                insertAutoAssFailed(submissionId, cachingService, courseExId)
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
            insertAutoAssessment(autoAss.grade, autoAss.feedback, submissionId, cachingService, courseExId)
        } catch (e: Exception) {
            log.error("Autoassessment failed", e)
            insertAutoAssFailed(submissionId, cachingService, courseExId)
            return
        }
        moodleGradesSyncService.syncSingleGradeToMoodle(submissionId)
    }

}


private fun selectGraderType(courseExId: Long): GraderType {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .slice(ExerciseVer.graderType)
            .select { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.graderType] }
            .single()
    }
}

private fun selectAutoExId(courseExId: Long): Long? {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .slice(ExerciseVer.autoExerciseId)
            .select { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId] }
            .single()?.value
    }
}

private fun insertSubmission(
    courseExId: Long,
    submission: String,
    studentId: String,
    autoAss: AutoGradeStatus,
    cachingService: CachingService
): Long {
    val id = transaction {
        Submission.insertAndGetId {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[student] = EntityID(studentId, Student)
            it[createdAt] = DateTime.now()
            it[solution] = submission
            it[autoGradeStatus] = autoAss
        }.value
    }

    cachingService.invalidate(countSubmissionsCache)
    cachingService.evictSelectLatestValidGrades(courseExId)
    return id
}

private fun insertAutoAssessment(
    newGrade: Int,
    newFeedback: String?,
    submissionId: Long,
    cachingService: CachingService,
    courseExId: Long
) {
    transaction {
        AutomaticAssessment.insert {
            it[submission] = EntityID(submissionId, Submission)
            it[createdAt] = DateTime.now()
            it[grade] = newGrade
            it[feedback] = newFeedback
        }

        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.COMPLETED
        }

        cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
        cachingService.evictSelectLatestValidGrades(courseExId)
    }
}

private fun insertAutoAssFailed(submissionId: Long, cachingService: CachingService, courseExId: Long) {
    transaction {
        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.FAILED
        }
    }

    cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
    cachingService.evictSelectLatestValidGrades(courseExId)
}
