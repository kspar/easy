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
class TeacherEditFeedbackController(val markdownService: MarkdownService, val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    data class InlineCommentReq(
        @JsonProperty("line_start", required = true) val lineStart: Int,
        @JsonProperty("line_end", required = true) val lineEnd: Int,
        @JsonProperty("code", required = true) val code: String,
        @JsonProperty("text_md", required = true) val textMd: String,
        @JsonProperty("type", required = true) val type: String,
        @JsonProperty("suggested_code", required = false) val suggestedCode: String? = null,
    )

    data class Req(
        @JsonProperty("teacher_activity_id", required = true) @field:Size(max = 100) val teacherActivityId: String,
        @JsonProperty("feedback_md", required = false) @field:Size(max = 300000) val feedbackMd: String?,
        @JsonProperty("inline_comments", required = false) val inlineComments: List<InlineCommentReq>? = null,
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
            val email = selectStudentEmailBySubmissionId(submissionId)
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

        val feedbackJson = req.feedbackMd?.let { md ->
            buildFeedbackJson(
                md,
                req.inlineComments?.map {
                    InlineComment(it.lineStart, it.lineEnd, it.code, it.textMd, "", it.type, it.suggestedCode)
                },
                markdownService,
            )
        }

        val updated =
            TeacherActivity.update({ (TeacherActivity.id eq activityId) and (TeacherActivity.teacher eq teacherId) }) {
                it[editedAt] = DateTime.now()
                it[feedback] = feedbackJson
            }

        if (updated == 0) {
            throw InvalidRequestException("Activity '$activityId' not found.", ReqError.ENTITY_WITH_ID_NOT_FOUND)
        }

        // Do not leave empty assessments in the db
        TeacherActivity.deleteWhere {
            (teacher eq teacherId) and (submission eq submissionId) and grade.isNull() and feedback.isNull()
        }
    }
}
