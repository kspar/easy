package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoAssessStatusObserver
import core.aas.AutoGradeScheduler
import core.aas.ObserverCallerType
import core.conf.security.EasyUser
import core.db.AutoGradeStatus
import core.db.GraderType
import core.ems.service.access_control.*
import core.ems.service.autoAssessAsync
import core.ems.service.cache.CachingService
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.insertSubmission
import core.ems.service.moodle.MoodleGradesSyncService
import core.ems.service.selectGraderType
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class TeacherSubmitBehalfStudent(
    private val autoAssessStatusObserver: AutoAssessStatusObserver,
    private val cachingService: CachingService,
    private val autoGradeScheduler: AutoGradeScheduler,
    private val moodleGradesSyncService: MoodleGradesSyncService,
    private val mailService: SendMailService,
) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("solution", required = true) @field:Size(max = 500000) val solution: String,
        @param:JsonProperty("student_id", required = true) @field:Size(max = 100) val studentId: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Creating new submission by ${caller.id} on behalf of ${req.studentId} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId, RequireStudentVisible(req.studentId))

        if (!canStudentAccessCourse(req.studentId, courseId))
            throw InvalidRequestException("Student ${req.studentId} not on course.", ReqError.STUDENT_NOT_ON_COURSE)

        submitOnBehalfOf(courseExId, req.solution, req.studentId, caller.id)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun submitOnBehalfOf(courseExId: Long, solution: String, studentId: String, teacherId: String) {
        if (selectGraderType(courseExId) == GraderType.TEACHER) {
            log.debug { "Creating new submission to teacher-graded exercise $courseExId for $studentId by $teacherId" }
            insertSubmission(courseExId, solution, studentId, AutoGradeStatus.NONE, cachingService)

        } else if (selectGraderType(courseExId) == GraderType.AUTO) {
            log.debug { "Creating new submission to autograded exercise $courseExId for $studentId by $teacherId" }
            val submissionId =
                insertSubmission(courseExId, solution, studentId, AutoGradeStatus.IN_PROGRESS, cachingService)

            val deferred: Job = GlobalScope.launch {
                autoAssessAsync(
                    courseExId,
                    solution,
                    submissionId,
                    studentId,
                    cachingService,
                    autoGradeScheduler,
                    mailService,
                    moodleGradesSyncService
                )
            }
            // add deferred to autoAssessStatusObserver
            autoAssessStatusObserver.put(submissionId, ObserverCallerType.STUDENT, deferred)
        }
    }
}
