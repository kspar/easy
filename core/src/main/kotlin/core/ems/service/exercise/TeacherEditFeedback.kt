package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherActivity
import core.db.isNull
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotNull
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherEditFeedbackController(val adocService: AdocService, val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("teacher_activity_id", required = true) @field:Size(max = 100) val teacherActivityId: String,
        @JsonProperty("feedback_adoc", required = false) @field:Size(max = 300000) val feedbackAdoc: String?,
        @JsonProperty("notify_student", required = true) @field:NotNull val notifyStudent: Boolean
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/feedback")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {

        log.info { "Update teacher ${caller.id} activity ${req.teacherActivityId} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseId,
        )

        updateTeacherActivity(callerId, submissionId, req)

        if (req.notifyStudent) {
            val titles = getCourseAndExerciseTitles(courseId, courseExId)
            val email = selectStudentBySubmissionId(submissionId).value
            mailService.sendStudentTeacherFeedbackEdited(
                courseId,
                courseExId,
                titles.exerciseTitle,
                titles.courseTitle,
                email
            )
        }
    }

    private fun updateTeacherActivity(teacherId: String, submissionId: Long, req: Req) = transaction {
        val activityId = req.teacherActivityId.idToLongOrInvalidReq()

        val updated =
            TeacherActivity.update({ (TeacherActivity.id eq activityId) and (TeacherActivity.teacher eq teacherId) }) {
                it[editedAt] = DateTime.now()
                it[feedbackAdoc] = req.feedbackAdoc
                it[feedbackHtml] = req.feedbackAdoc?.let { adoc -> adocService.adocToHtml(adoc) }
            }

        if (updated == 0) {
            throw InvalidRequestException("Activity '$activityId' not found.", ReqError.ENTITY_WITH_ID_NOT_FOUND)
        }

        // Do not leave empty assessments in the db
        TeacherActivity.deleteWhere {
            (teacher eq teacherId) and (submission eq submissionId) and grade.isNull() and feedbackAdoc.isNull()
        }
    }
}
