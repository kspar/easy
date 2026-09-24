package core.ems.service.ai

import core.db.*
import core.ems.service.AiFeedbackResp
import core.ems.service.MarkdownService
import core.ems.service.getLatestAutomaticAssessmentRespOrNull
import core.ems.service.toAiFeedbackResp
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.stereotype.Service

/**
 * EZ-1712. One explanation for one failed submission, on the student's request.
 *
 * The provider call runs outside any transaction and blocks the request for as long as it takes
 * (bounded by the provider's read timeout). Two rows can result: an OK row with the explanation,
 * or a FAILED row with whatever the provider said — both carry the full prompt, so an operator can
 * replay either.
 *
 * What this never does is touch `submission.grade`. There is no code path from here to a grade.
 */
@Service
class AiFeedbackService(
    private val providerFactory: AiProviderFactory,
    private val markdownService: MarkdownService,
) {
    private val log = KotlinLogging.logger {}

    fun explain(
        courseId: Long, courseExId: Long, submissionId: Long, studentId: String, language: String,
    ): AiFeedbackResp {
        // Idempotent before anything else: a second click, a reload mid-request, a retried POST —
        // all get the same row back and cost nothing.
        selectOk(submissionId)?.let { return it }

        val provider = providerFactory.forCourse(courseId)
            ?: throw InvalidRequestException(
                "Course $courseId has no AI provider configured", ReqError.AI_NOT_CONFIGURED, notify = false
            )

        val ctx = loadContext(courseExId, submissionId, studentId, language)
        val built = AiFeedbackPrompt.build(ctx)
        val audit = built.forAudit()

        val result = try {
            provider.complete(AiCompletionRequest(system = built.system, user = built.user))
        } catch (e: AiProviderException) {
            // The log line carries the cause; the response does not. What the provider said may
            // name the configured host, and that is the teacher's or an operator's to read, not
            // the student's. `notify = false`: a revoked key is the course's problem, and one mail
            // per click would be sixty mails per lecture.
            log.warn(e) { "AI feedback failed for submission $submissionId (provider ${provider.type})" }
            insertRow(
                courseExId, submissionId, studentId, AiFeedbackStatus.FAILED, provider.type,
                provider.model, feedbackMd = null, feedbackHtml = null, prompt = audit,
                raw = e.rawResponse, tokensIn = null, tokensOut = null,
            )
            throw InvalidRequestException(
                "AI provider error for submission $submissionId", ReqError.AI_PROVIDER_ERROR, notify = false
            )
        }

        val html = markdownService.mdToHtml(result.text)
        return try {
            val id = insertRow(
                courseExId, submissionId, studentId, AiFeedbackStatus.OK, provider.type, result.model,
                feedbackMd = result.text, feedbackHtml = html, prompt = audit, raw = result.rawResponse,
                tokensIn = result.tokensIn, tokensOut = result.tokensOut,
            )
            log.info { "AI feedback $id stored for submission $submissionId (${result.tokensIn} in, ${result.tokensOut} out)" }
            selectOk(submissionId) ?: throw IllegalStateException("AI feedback $id was inserted and is not there")
        } catch (e: ExposedSQLException) {
            // Two clicks raced past the check at the top; the index picked a winner. The loser's
            // answer is the winner's row — same as LinkCourseMoodle does with its unique constraint.
            if (e.message?.contains(UNIQUE_OK_INDEX) == true) {
                selectOk(submissionId) ?: throw e
            } else throw e
        }
    }

    /**
     * Everything the guards need and everything the prompt needs, in one read. Guards in the
     * order a student would hit them: not the latest submission, not graded yet, not failed.
     */
    private fun loadContext(
        courseExId: Long, submissionId: Long, studentId: String, language: String,
    ): AiFeedbackPrompt.Context = transaction {
        val maxNumber = Submission.number.max()
        val latestNumber = Submission
            .select(maxNumber)
            .where { Submission.courseExercise eq courseExId and (Submission.student eq studentId) }
            .single()[maxNumber]

        val submission = Submission
            .select(Submission.number, Submission.solution, Submission.autoGradeStatus)
            .where { Submission.id eq submissionId }
            .single()

        if (submission[Submission.number] != latestNumber) {
            throw notAvailable("submission $submissionId is not the student's latest")
        }
        if (submission[Submission.autoGradeStatus] != AutoGradeStatus.COMPLETED) {
            throw notAvailable("submission $submissionId is not autograded (${submission[Submission.autoGradeStatus]})")
        }
        val assessment = getLatestAutomaticAssessmentRespOrNull(submissionId)
            ?: throw notAvailable("submission $submissionId has no autograde result")
        if (assessment.grade >= 100) {
            throw notAvailable("submission $submissionId passed all tests")
        }

        val exercise = (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(
                ExerciseVer.title, ExerciseVer.textMd, ExerciseVer.textHtml, ExerciseVer.solutionFileName,
                CourseExercise.titleAlias,
            )
            .where { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
            .single()

        AiFeedbackPrompt.Context(
            exerciseTitle = exercise[CourseExercise.titleAlias] ?: exercise[ExerciseVer.title],
            exerciseText = exercise[ExerciseVer.textMd] ?: exercise[ExerciseVer.textHtml],
            solutionFileName = exercise[ExerciseVer.solutionFileName],
            solution = submission[Submission.solution],
            grade = assessment.grade,
            autogradeFeedback = assessment.feedback,
            language = language,
        )
    }

    private fun notAvailable(why: String) =
        InvalidRequestException("AI feedback not available: $why", ReqError.AI_FEEDBACK_NOT_AVAILABLE, notify = false)

    private fun insertRow(
        courseExId: Long, submissionId: Long, studentId: String, status: AiFeedbackStatus,
        provider: AiProviderType, model: String, feedbackMd: String?, feedbackHtml: String?,
        prompt: String, raw: String?, tokensIn: Int?, tokensOut: Int?,
    ): Long = transaction {
        AiFeedback.insertAndGetId {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[submission] = EntityID(submissionId, Submission)
            it[student] = EntityID(studentId, Account)
            it[createdAt] = DateTime.now()
            it[AiFeedback.status] = status
            it[AiFeedback.provider] = provider
            it[AiFeedback.model] = model
            it[AiFeedback.feedbackMd] = feedbackMd
            it[AiFeedback.feedbackHtml] = feedbackHtml
            it[AiFeedback.prompt] = prompt
            it[responseRaw] = raw
            it[AiFeedback.tokensIn] = tokensIn
            it[AiFeedback.tokensOut] = tokensOut
        }.value
    }

    private fun selectOk(submissionId: Long): AiFeedbackResp? = transaction {
        (Submission innerJoin AiFeedback)
            .select(
                AiFeedback.id, AiFeedback.submission, Submission.number, AiFeedback.createdAt,
                AiFeedback.provider, AiFeedback.model, AiFeedback.feedbackMd, AiFeedback.feedbackHtml,
            )
            .where { AiFeedback.submission eq submissionId and (AiFeedback.status eq AiFeedbackStatus.OK) }
            .orderBy(AiFeedback.createdAt, SortOrder.DESC)
            .limit(1)
            .map { row: ResultRow -> row.toAiFeedbackResp() }
            .firstOrNull()
    }

    companion object {
        /** Changeset 240926-2. Matched by name in the failed insert's message. */
        private const val UNIQUE_OK_INDEX = "uq_ai_feedback_submission_ok"
    }
}
