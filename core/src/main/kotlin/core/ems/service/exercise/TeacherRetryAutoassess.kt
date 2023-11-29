package core.ems.service.exercise

import core.aas.AutoGradeScheduler
import core.conf.security.EasyUser
import core.db.GraderType
import core.db.PriorityLevel
import core.db.Submission
import core.ems.service.*
import core.ems.service.cache.CachingService
import core.ems.service.moodle.MoodleGradesSyncService
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class TeacherRetryAutoassessCont(
    private val cachingService: CachingService,
    private val autoGradeScheduler: AutoGradeScheduler,
    private val moodleGradesSyncService: MoodleGradesSyncService,
    private val mailService: SendMailService
) {
    private val log = KotlinLogging.logger {}


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/retry-autoassess")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        caller: EasyUser
    ) {

        log.info { "${caller.id} is retrying autoassessment for submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        retryAutograde(submissionId, courseExId)
    }

    private fun retryAutograde(submissionId: Long, courseExId: Long) {
        if (selectGraderType(courseExId) != GraderType.AUTO) {
            throw InvalidRequestException(
                "Course exercise $courseExId not autoassessable.",
                ReqError.EXERCISE_NOT_AUTOASSESSABLE
            )
        }

        GlobalScope.launch {
            autoAssessAsync(courseExId, submissionId)
        }
    }

    suspend fun autoAssessAsync(courseExId: Long, submissionId: Long) {
        val (solution, studentId) = transaction {
            Submission.slice(Submission.solution, Submission.student)
                .select { (Submission.id eq submissionId) }
                .map { it[Submission.solution] to it[Submission.student].value }
                .singleOrInvalidRequest()
        }

        try {
            val autoExerciseId = selectAutoExId(courseExId)
            if (autoExerciseId == null) {
                insertAutoAssFailed(submissionId, cachingService, studentId, courseExId)
                throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
            }

            log.debug { "Starting autoassessment with auto exercise id $autoExerciseId" }
            val autoAss = autoGradeScheduler.submitAndAwait(autoExerciseId, solution, PriorityLevel.AUTHENTICATED)
            log.debug { "Finished autoassessment" }
            insertAutoAssessment(autoAss.grade, autoAss.feedback, submissionId, cachingService, courseExId, studentId)
        } catch (e: Exception) {
            log.error("Autoassessment failed", e)
            insertAutoAssFailed(submissionId, cachingService, studentId, courseExId)
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
}

