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
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.http.HttpStatus
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class TeacherInlineCommentCrudController(val markdownService: MarkdownService, val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    /*
     * There is no `type` on these, and that is EZ-1777's actual conclusion.
     *
     * It was a `required = true` String, unvalidated, stored verbatim and echoed back, so a teacher
     * could POST `"banana"` and core would serve it. Making it an enum fixed that and was still the
     * wrong fix: `AnnotatedCodeEditor.tsx` computed it as `suggestedCode ? 'suggestion' : 'comment'`
     * on every save, and no reader anywhere consulted it. A discriminator that is a pure function of
     * another field is not one. `suggested_code` says the same thing and cannot disagree with itself.
     *
     * Deleting a request field is normally the dangerous direction here — `FAIL_ON_UNKNOWN_PROPERTIES`
     * is off, so a client still sending it gets 200 and whatever the field would have carried is
     * silently gone, which is why `legacy_content_fields.kt` exists to name the removed `*_adoc`
     * fields in a 400. That treatment is deliberately *not* applied to `type`: those fields were the
     * content, so losing them produced an empty exercise, while ignoring `type` loses nothing at all.
     * The test to apply before reaching for the reject-list is whether the ignored field held
     * information, not whether it was removed.
     */
    data class CreateReq(
        @param:JsonProperty("line_start", required = true) val lineStart: Int,
        @param:JsonProperty("line_end", required = true) val lineEnd: Int,
        @param:JsonProperty("code", required = true) val code: String,
        @param:JsonProperty("text_md", required = true) @field:NotBlank @field:Size(max = 300000) val textMd: String,
        @param:JsonProperty("suggested_code", required = false) val suggestedCode: String? = null,
        @param:JsonProperty("notify_student", required = false) val notifyStudent: Boolean = false,
    )

    data class UpdateReq(
        @param:JsonProperty("line_start", required = true) val lineStart: Int,
        @param:JsonProperty("line_end", required = true) val lineEnd: Int,
        @param:JsonProperty("code", required = true) val code: String,
        @param:JsonProperty("text_md", required = true) @field:NotBlank @field:Size(max = 300000) val textMd: String,
        @param:JsonProperty("suggested_code", required = false) val suggestedCode: String? = null,
        @param:JsonProperty("notify_student", required = false) val notifyStudent: Boolean = false,
    )

    data class InlineCommentsResp(
        @get:JsonProperty("inline_comments") val inlineComments: List<InlineCommentResp>,
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

        val suggested = req.suggestedCode.normaliseSuggestion()

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
                it[suggestedCode] = suggested
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
                suggestedCode = suggested,
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
        val suggested = req.suggestedCode.normaliseSuggestion()

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
                it[suggestedCode] = suggested
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
                suggestedCode = suggested,
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

/**
 * `""` means "no suggestion", the same as absent.
 *
 * With `type` gone (EZ-1777), `suggested_code IS NOT NULL` *is* the definition of a suggestion — the
 * changeset comment, `TeacherInlineComment`'s KDoc and `types.ts` all state it that way. An empty
 * string would be a third state underneath that claim: stored, so non-null, so a suggestion by the
 * stated rule, while every render site in the app tests the string for truthiness and draws a plain
 * comment. Two answers about one row, which is the exact thing dropping the column was for.
 *
 * `ifEmpty`, deliberately, not `ifBlank`. The client's rule is JS truthiness — `d.suggestedCode ?` on
 * write, `comment.suggested_code &&` on read — under which `""` is absent and `" "` is present. This
 * matches it exactly. Trimming would be defensible in isolation and would introduce a *new*
 * disagreement at whitespace-only, which is how the field got into this state to begin with.
 *
 * `210826-3`'s backfill already made this distinction (`suggested_code <> ''`); nothing on the write
 * path did until the review of `210826-4` asked why not.
 */
private fun String?.normaliseSuggestion(): String? = this?.ifEmpty { null }

private fun selectInlineComments(courseExId: Long, studentId: String): List<InlineCommentResp> = transaction {
    // Join with Submission to get submission_number and filter by student
    (TeacherInlineComment innerJoin Submission)
        .selectAll()
        .where {
            (TeacherInlineComment.courseExercise eq courseExId) and
                    (Submission.student eq studentId)
        }
        // The id is a tiebreaker, not decoration. `created_at` is millisecond-resolution and the
        // controller stamps it with `DateTime.now()`, so two comments saved in the same millisecond —
        // a double-click on save, or the annotated editor's two writes for one edit — tie, and which
        // one the client shows first is then whatever the query plan produces. Same defect class as
        // EZ-1763, found by reviewing EZ-1777 rather than by anything failing: an ordering that is
        // not total is not an ordering. Ascending on both, so the sequence is the writing order.
        .orderBy(
            TeacherInlineComment.createdAt to SortOrder.ASC,
            TeacherInlineComment.id to SortOrder.ASC,
        )
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
                suggestedCode = it[TeacherInlineComment.suggestedCode],
            )
        }
}
