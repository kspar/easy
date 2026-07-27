package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Submission
import core.db.TeacherInlineComment
import core.ems.service.*
import core.ems.service.access_control.RequireStudentVisible
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.util.SendMailService
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.http.HttpStatus
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherInlineCommentCrudController(val markdownService: MarkdownService, val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    data class CreateReq(
        @JsonProperty("line_start", required = true) val lineStart: Int,
        @JsonProperty("line_end", required = true) val lineEnd: Int,
        @JsonProperty("code", required = true) val code: String,
        @JsonProperty("text_md", required = true) @field:NotBlank @field:Size(max = 300000) val textMd: String,
        @JsonProperty("type", required = true) val type: String,
        @JsonProperty("suggested_code", required = false) val suggestedCode: String? = null,
        @JsonProperty("notify_student", required = false) val notifyStudent: Boolean = false,
    )

    data class UpdateReq(
        @JsonProperty("line_start", required = true) val lineStart: Int,
        @JsonProperty("line_end", required = true) val lineEnd: Int,
        @JsonProperty("code", required = true) val code: String,
        @JsonProperty("text_md", required = true) @field:NotBlank @field:Size(max = 300000) val textMd: String,
        @JsonProperty("type", required = true) val type: String,
        @JsonProperty("suggested_code", required = false) val suggestedCode: String? = null,
        @JsonProperty("notify_student", required = false) val notifyStudent: Boolean = false,
    )

    data class InlineCommentsResp(
        @JsonProperty("inline_comments") val inlineComments: List<InlineCommentResp>,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/inline-comments")
    fun createComment(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody req: CreateReq,
        caller: EasyUser
    ): InlineCommentResp {
        log.info { "Create inline comment by ${caller.id} on submission $submissionIdString" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller, submissionIdString, courseExerciseIdString, courseId,
        )

        val result = transaction {
            val time = DateTime.now()
            val textHtml = markdownService.mdToHtml(req.textMd)

            val id = TeacherInlineComment.insertAndGetId {
                it[courseExercise] = courseExId
                it[submission] = submissionId
                it[teacher] = callerId
                it[createdAt] = time
                it[lineStart] = req.lineStart
                it[lineEnd] = req.lineEnd
                it[code] = req.code
                it[textMd] = req.textMd
                it[TeacherInlineComment.textHtml] = textHtml
                it[type] = req.type
                it[suggestedCode] = req.suggestedCode
            }

            val subNumber = Submission.select(Submission.number)
                .where { Submission.id eq submissionId }
                .single()[Submission.number]

            InlineCommentResp(
                id = id.value.toString(),
                submissionId = submissionId.toString(),
                submissionNumber = subNumber,
                teacher = selectTeacher(callerId),
                createdAt = time,
                editedAt = null,
                lineStart = req.lineStart,
                lineEnd = req.lineEnd,
                code = req.code,
                textMd = req.textMd,
                textHtml = textHtml,
                type = req.type,
                suggestedCode = req.suggestedCode,
            )
        }

        if (req.notifyStudent) {
            val titles = getCourseAndExerciseTitles(courseId, courseExId)
            val email = selectStudentEmailBySubmissionId(submissionId)
            mailService.sendStudentGotNewTeacherFeedback(courseId, courseExId, titles.exerciseTitle, titles.courseTitle, email)
        }

        return result
    }

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/inline-comments/{commentId}")
    fun updateComment(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @PathVariable("commentId") commentIdString: String,
        @Valid @RequestBody req: UpdateReq,
        caller: EasyUser
    ): InlineCommentResp {
        log.info { "Update inline comment $commentIdString by ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller, submissionIdString, courseExerciseIdString, courseId,
        )
        val commentId = commentIdString.idToLongOrInvalidReq()

        val result = transaction {
            val time = DateTime.now()
            val textHtml = markdownService.mdToHtml(req.textMd)

            val updated = TeacherInlineComment.update({
                (TeacherInlineComment.id eq commentId) and
                        (TeacherInlineComment.teacher eq callerId) and
                        (TeacherInlineComment.submission eq submissionId)
            }) {
                it[editedAt] = time
                it[lineStart] = req.lineStart
                it[lineEnd] = req.lineEnd
                it[code] = req.code
                it[textMd] = req.textMd
                it[TeacherInlineComment.textHtml] = textHtml
                it[type] = req.type
                it[suggestedCode] = req.suggestedCode
            }

            if (updated == 0) {
                throw InvalidRequestException(
                    "Inline comment '$commentId' not found or not owned by you.",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )
            }

            val row = (TeacherInlineComment innerJoin Submission)
                .selectAll()
                .where { TeacherInlineComment.id eq commentId }
                .single()

            InlineCommentResp(
                id = commentId.toString(),
                submissionId = submissionId.toString(),
                submissionNumber = row[Submission.number],
                teacher = selectTeacher(callerId),
                createdAt = row[TeacherInlineComment.createdAt],
                editedAt = time,
                lineStart = req.lineStart,
                lineEnd = req.lineEnd,
                code = req.code,
                textMd = req.textMd,
                textHtml = textHtml,
                type = req.type,
                suggestedCode = req.suggestedCode,
            )
        }

        if (req.notifyStudent) {
            val titles = getCourseAndExerciseTitles(courseId, courseExId)
            val email = selectStudentEmailBySubmissionId(submissionId)
            mailService.sendStudentTeacherFeedbackEdited(courseId, courseExId, titles.exerciseTitle, titles.courseTitle, email)
        }

        return result
    }

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/inline-comments/{commentId}")
    fun deleteComment(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @PathVariable("commentId") commentIdString: String,
        caller: EasyUser
    ) {
        log.info { "Delete inline comment $commentIdString by ${caller.id}" }

        val (callerId, _, submissionId) = assertAssessmentControllerChecks(
            caller, submissionIdString, courseExerciseIdString, courseIdString,
        )
        val commentId = commentIdString.idToLongOrInvalidReq()

        transaction {
            val deleted = TeacherInlineComment.deleteWhere {
                (id eq commentId) and (teacher eq callerId) and (submission eq submissionId)
            }
            if (deleted == 0) {
                throw InvalidRequestException(
                    "Inline comment '$commentId' not found or not owned by you.",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )
            }
        }
    }

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/students/{studentId}/inline-comments")
    fun getTeacherStudentComments(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("studentId") studentId: String,
        caller: EasyUser
    ): InlineCommentsResp {
        log.info { "Get inline comments for student $studentId by ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }

        return InlineCommentsResp(selectInlineComments(courseExId, studentId))
    }
}

@RestController
@RequestMapping("/v2")
class StudentInlineCommentController {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/inline-comments")
    fun getStudentComments(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        caller: EasyUser
    ): TeacherInlineCommentCrudController.InlineCommentsResp {
        log.info { "Get own inline comments for ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
        caller.assertAccess { studentOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId, RequireStudentVisible(caller.id))

        return TeacherInlineCommentCrudController.InlineCommentsResp(selectInlineComments(courseExId, caller.id))
    }
}

private fun selectInlineComments(courseExId: Long, studentId: String): List<InlineCommentResp> = transaction {
    // Join with Submission to get submission_number and filter by student
    (TeacherInlineComment innerJoin Submission)
        .selectAll()
        .where {
            (TeacherInlineComment.courseExercise eq courseExId) and
                    (Submission.student eq studentId)
        }
        .orderBy(TeacherInlineComment.createdAt, SortOrder.ASC)
        .map {
            InlineCommentResp(
                id = it[TeacherInlineComment.id].value.toString(),
                submissionId = it[TeacherInlineComment.submission].value.toString(),
                submissionNumber = it[Submission.number],
                teacher = selectTeacher(it[TeacherInlineComment.teacher].value),
                createdAt = it[TeacherInlineComment.createdAt],
                editedAt = it[TeacherInlineComment.editedAt],
                lineStart = it[TeacherInlineComment.lineStart],
                lineEnd = it[TeacherInlineComment.lineEnd],
                code = it[TeacherInlineComment.code],
                textMd = it[TeacherInlineComment.textMd],
                textHtml = it[TeacherInlineComment.textHtml],
                type = it[TeacherInlineComment.type],
                suggestedCode = it[TeacherInlineComment.suggestedCode],
            )
        }
}
