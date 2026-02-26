package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StatsSubmission
import core.db.TeacherActivity
import core.ems.service.*
import core.util.SendMailService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class TeacherPostFeedbackController(val markdownService: MarkdownService, val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.activity.merge-window.s}")
    private lateinit var mergeWindowInSeconds: String

    data class InlineCommentReq(
        @param:JsonProperty("line_start", required = true) val lineStart: Int,
        @param:JsonProperty("line_end", required = true) val lineEnd: Int,
        @param:JsonProperty("code", required = true) val code: String,
        @param:JsonProperty("text_md", required = true) @field:NotBlank val textMd: String,
        @param:JsonProperty("type", required = true) val type: String,
        @param:JsonProperty("suggested_code", required = false) val suggestedCode: String? = null,
    )

    data class Req(
        @param:JsonProperty("feedback_md", required = true)
        @field:Size(max = 300000)
        @field:NotBlank
        val feedbackMd: String,
        @param:JsonProperty("inline_comments", required = false)
        val inlineComments: List<InlineCommentReq>? = null,
        @param:JsonProperty("notify_student", required = true)
        @field:NotNull
        val notifyStudent: Boolean
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/feedback")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {

        log.info { "Set feedback by teacher ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseId,
        )

        insertOrUpdateFeedback(callerId, submissionId, req, courseExId)

        if (req.notifyStudent) {
            val titles = getCourseAndExerciseTitles(courseId, courseExId)
            val email = selectStudentEmailBySubmissionId(submissionId)
            mailService.sendStudentGotNewTeacherFeedback(
                courseId,
                courseExId,
                titles.exerciseTitle,
                titles.courseTitle,
                email
            )
        }
    }

    private fun insertOrUpdateFeedback(teacherId: String, submissionId: Long, assessment: Req, courseExId: Long) =
        transaction {
            val previousId = getIdIfShouldMerge(submissionId, teacherId, mergeWindowInSeconds.toInt())
            val time = DateTime.now()

            val feedbackJson = buildFeedbackJson(
                assessment.feedbackMd,
                assessment.inlineComments?.map {
                    InlineComment(it.lineStart, it.lineEnd, it.code, it.textMd, "", it.type, it.suggestedCode)
                },
                markdownService,
            )

            if (previousId != null) {
                TeacherActivity.update({ TeacherActivity.id eq previousId }) {
                    it[mergeWindowStart] = time
                    it[feedback] = feedbackJson
                }
            } else {
                TeacherActivity.insert {
                    it[student] = selectStudentBySubmissionId(submissionId)
                    it[courseExercise] = courseExId
                    it[submission] = submissionId
                    it[teacher] = teacherId
                    it[mergeWindowStart] = time
                    it[feedback] = feedbackJson
                }
            }

            StatsSubmission.update({ StatsSubmission.submissionId eq submissionId }) {
                it[hasEverReceivedTeacherComment] = true
                it[latestTeacherActivityUpdate] = time
                it[latestTeacherPseudonym] = selectPseudonym(teacherId)
            }
        }

    private fun getIdIfShouldMerge(submissionId: Long, teacherId: String, mergeWindow: Int): Long? =
        transaction {
            TeacherActivity
                .select(
                    TeacherActivity.id,
                    TeacherActivity.mergeWindowStart,
                    TeacherActivity.feedback,
                )
                .where { TeacherActivity.submission eq submissionId and (TeacherActivity.teacher eq teacherId) }
                .orderBy(TeacherActivity.mergeWindowStart, SortOrder.DESC)
                .firstNotNullOfOrNull {
                    val timeIsInWindow = !it[TeacherActivity.mergeWindowStart].hasSecondsPassed(mergeWindow)
                    val noFeedback = it[TeacherActivity.feedback] == null
                    if (timeIsInWindow && noFeedback) it[TeacherActivity.id].value else null
                }
        }

}
