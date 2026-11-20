package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Account
import core.db.AutoExercise
import core.db.Course
import core.db.CourseExercise
import core.db.Exercise
import core.db.ExerciseVer
import core.db.Submission
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.code.similarity.SimilarityLanguage
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.code.similarity.SimilaritySource
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import kotlin.math.roundToInt


@RestController
@RequestMapping("/v2")
class TeacherCheckSimilarityController(
    private val similarityService: SimilarityService,
    /**
     * The largest cohort one check takes. Every solution and token stream is on core's heap for the
     * duration and a comparison cannot be interrupted, so a larger cohort is refused before it is loaded.
     */
    @param:Value("\${easy.core.similarity.max-submissions:500}") private val maxSubmissions: Int,
) {
    private val log = KotlinLogging.logger {}

    /**
     * How to call this service:
     * 1) exerciseId only - compare over all courses and all submissions
     * 2) exerciseId, courseId(s) - compare over all given courses and all submissions
     * 3) exerciseId, courseId(s), submissionIds - compare over all given submissions (must be on given courses)
     */
    data class Req(
        @param:JsonProperty("courses", required = false) val courses: List<ReqCourse>,
        @param:JsonProperty("submissions", required = false) val submissions: List<ReqSubmission>?
    )

    data class ReqCourse(
        @param:JsonProperty("id", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        val id: String
    )

    data class ReqSubmission(
        @param:JsonProperty("id", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        val id: String
    )

    data class Resp(
        /** Only those some returned pair or group names: the cohort can be hundreds of long solutions. */
        @get:JsonProperty("submissions") val submissions: List<RespSubmission>,
        /** How many submissions the check was over, including those skipped and those no pair names. */
        @get:JsonProperty("compared") val compared: Int,
        @get:JsonProperty("scores") val scores: List<RespScore>,
        /** `jplag` for Python, which renaming does not fool, or `edit-distance`, character by character. */
        @get:JsonProperty("provider") val provider: String,
        /** The language compared: `python`, `sql`, or `text` - also for Python answers that turned out to be prose. */
        @get:JsonProperty("language") val language: String,
        /** Submissions that could not be compared, and why, so "nothing similar" cannot hide half the cohort. */
        @get:JsonProperty("skipped") val skipped: List<RespSkipped>,
        /** Identical submissions by three or more students, compared once; `scores` names one member for them all. */
        @get:JsonProperty("groups") val groups: List<RespGroup>,
        /** True when more pairs scored than were returned. */
        @get:JsonProperty("truncated") val truncated: Boolean,
    )

    data class RespSubmission(
        @get:JsonProperty("id") val id: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        // TODO: number
        @get:JsonProperty("solution") val solution: String,
        @get:JsonProperty("given_name") val givenName: String,
        @get:JsonProperty("family_name") val familyName: String,
        @get:JsonProperty("course_title") val courseTitle: String
    )

    data class RespScore(
        @get:JsonProperty("sub_1") val sub1: String,
        @get:JsonProperty("sub_2") val sub2: String,
        /** The share of both submissions together, 0-100. */
        @get:JsonProperty("similarity") val similarity: Int,
        /** The share of whichever submission is covered more, 0-100: what the page's threshold filters on. */
        @get:JsonProperty("max_similarity") val maxSimilarity: Int,
        /** How many tokens the two share, as JPlag counts them; null for edit distance. */
        @get:JsonProperty("shared_tokens") val sharedTokens: Int?,
        /** Where the two match, as line ranges; always empty for edit distance, which does not say. */
        @get:JsonProperty("matches") val matches: List<RespMatch>,
    )

    data class RespMatch(
        @get:JsonProperty("start_line_1") val startLine1: Int,
        @get:JsonProperty("end_line_1") val endLine1: Int,
        @get:JsonProperty("start_line_2") val startLine2: Int,
        @get:JsonProperty("end_line_2") val endLine2: Int,
        @get:JsonProperty("tokens") val tokens: Int
    )

    data class RespSkipped(
        @get:JsonProperty("id") val id: String,
        /** `TOO_SMALL`, `TOO_LARGE`, `TOO_COMPLEX`, `TOO_REPETITIVE` or `CANNOT_PARSE`. */
        @get:JsonProperty("reason") val reason: String
    )

    data class RespGroup(
        /** In id order. */
        @get:JsonProperty("submissions") val submissions: List<String>
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/similarity")
    fun controller(
        @PathVariable("exerciseId") exIdString: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        // Counts, not the body: a body can list tens of thousands of ids, each logged as part of one line.
        log.info { "Calculate similarity for exercise '$exIdString' by ${caller.id} over ${body.courses.size} course(s), ${body.submissions?.size ?: "all"} submission(s)" }
        val exerciseId = exIdString.idToLongOrInvalidReq()
        // Each course once: every copy costs an access query, so 70,000 copies of one course would hold the
        // request for half a minute and then fail the query on them, mailing the admins an error.
        val courses = body.courses.map { it.id.idToLongOrInvalidReq() }.distinct()

        caller.assertAccess {
            courses.forEach { teacherOnCourse(it) }
        }

        // The cap on a listed set before it is looked up, so a long list does not become one large query.
        body.submissions?.let { assertWithinCap(it.size) }
        val submissions = body.submissions?.map { it.id.idToLongOrInvalidReq() }

        // Ids first, and the cap on their count, before any solution is loaded.
        val ids = selectSubmissionIds(exerciseId, courses, submissions)
        assertWithinCap(ids.size)
        val language = selectLanguage(exerciseId, courses)

        // Loaded by the service once it holds the permit, so a refused caller never loads a cohort.
        lateinit var rows: List<SubmissionRow>
        val outcome = similarityService.compare(
            loadSources = {
                rows = selectSubmissions(ids)
                rows.map { SimilaritySource(it.resp.id.toLong(), it.resp.solution, it.studentId) }
            },
            language = language,
            topN = TOP_PAIRS,
            owner = caller.id,
        )

        val referenced = (outcome.pairs.flatMap { listOf(it.id1, it.id2) } + outcome.groups.flatten()).toSet()
        return Resp(
            rows.map { it.resp }.filter { it.id.toLong() in referenced },
            rows.size,
            outcome.pairs.map { pair ->
                RespScore(
                    pair.id1.toString(), pair.id2.toString(),
                    percent(pair.similarity), percent(pair.maxSimilarity),
                    pair.sharedTokens,
                    pair.matches.map { RespMatch(it.startLine1, it.endLine1, it.startLine2, it.endLine2, it.tokens) },
                )
            },
            outcome.provider,
            outcome.language.wireName,
            outcome.skipped.map { RespSkipped(it.id.toString(), it.reason) },
            outcome.groups.map { group -> RespGroup(group.map { it.toString() }) },
            outcome.truncated,
        )
    }

    private fun assertWithinCap(requested: Int) {
        if (requested > maxSubmissions) {
            throw InvalidRequestException(
                "Similarity can compare at most $maxSubmissions submissions at once, $requested were requested.",
                ReqError.TOO_MANY_SUBMISSIONS,
                "limit" to maxSubmissions.toString(), "requested" to requested.toString(),
                notify = false,
            )
        }
    }

    private fun percent(fraction: Double): Int =
        if (fraction.isNaN()) 0 else (fraction * 100).roundToInt().coerceIn(0, 100)

    private fun selectSubmissionIds(exerciseId: Long, courses: List<Long>, submissions: List<Long>?): List<Long> = transaction {
        val query = (CourseExercise innerJoin Submission)
            .select(Submission.id)
            .where {
                CourseExercise.exercise eq exerciseId and
                        (CourseExercise.course inList courses)
            }

        if (submissions != null) {
            query.andWhere {
                Submission.id inList submissions
            }

            val count = query.count()
            if (count < submissions.size) {
                throw InvalidRequestException(
                    "Number of submissions '${submissions.size}' requested to analyze does not equal to the number of submissions found: '$count'.",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )
            }
        }

        query.map { it[Submission.id].value }
    }

    /**
     * The exercise's language, from its current version's solution file name and grader image (see
     * [SimilarityLanguage.of]). Read through the courses the caller was asserted on, so it says nothing of
     * an exercise on another teacher's course; one on none of them has no submissions here either.
     */
    private fun selectLanguage(exerciseId: Long, courses: List<Long>): SimilarityLanguage = transaction {
        (Exercise innerJoin ExerciseVer innerJoin CourseExercise)
            .join(AutoExercise, JoinType.LEFT, ExerciseVer.autoExerciseId, AutoExercise.id)
            .select(ExerciseVer.solutionFileName, AutoExercise.containerImage)
            .where {
                Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() and
                        (CourseExercise.course inList courses)
            }
            .firstOrNull()
            ?.let { SimilarityLanguage.of(it[ExerciseVer.solutionFileName], it.getOrNull(AutoExercise.containerImage)?.value) }
            ?: SimilarityLanguage.TEXT
    }

    /** A row with its author, who is not on the wire but decides which pairs are compared. */
    private data class SubmissionRow(val resp: RespSubmission, val studentId: String)

    private fun selectSubmissions(ids: List<Long>): List<SubmissionRow> = transaction {
        (Course innerJoin CourseExercise innerJoin (Submission innerJoin Account))
            .select(
                Course.title,
                Submission.id,
                Submission.createdAt,
                Submission.solution,
                Submission.student,
                Account.givenName,
                Account.familyName
            )
            .where { Submission.id inList ids }
            .map {
                SubmissionRow(
                    RespSubmission(
                        it[Submission.id].value.toString(),
                        it[Submission.createdAt],
                        it[Submission.solution],
                        it[Account.givenName],
                        it[Account.familyName],
                        it[Course.title]
                    ),
                    it[Submission.student].value,
                )
            }
    }

    companion object {
        private const val TOP_PAIRS = 100
    }
}
