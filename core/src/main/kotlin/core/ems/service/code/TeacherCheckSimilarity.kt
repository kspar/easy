package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.Course
import core.db.CourseExercise
import core.db.Submission
import core.ems.service.code.similarity.MetricUnit
import core.ems.service.code.similarity.SimilarityBusy
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.code.similarity.SimilaritySource
import core.ems.service.code.similarity.SimilarityTimedOut
import core.ems.service.code.similarity.SimilarityTooCostly
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.annotation.JsonSerialize
import kotlin.math.roundToInt


@RestController
@RequestMapping("/v2")
class TeacherCheckSimilarityController(
    private val similarityService: SimilarityService,
    private val requests: SimilarityRequests,
) {
    private val log = KotlinLogging.logger {}

    /**
     * How to call this service:
     * 1) exerciseId, courseId(s) — compare the **latest submission per student** on those courses
     * 2) exerciseId, courseId(s), submissionIds — compare exactly those submissions (must be on
     *    those courses)
     * 3) exerciseId, courseId(s), studentIds — compare those students' submissions, the ones the
     *    attempts setting names: each one's latest, or each one's first. What the page sends.
     *
     * A named submission is that one - which attempt it is, is not a question - and students are named
     * so that the setting can answer it. Named by submission, under the first attempts, each was taken for
     * its author and swapped for their first, so the same body meant two cohorts by a setting the caller
     * may not have seen (EZ-1667). The page sends the students' latest submissions as well, for a core
     * older than students, which reads only those: given both, the students decide and the submissions
     * are not read.
     *
     * A course exercise, when given, is the one the students and the course stand for: an exercise can be
     * on a course twice, and the page lists - and links to - one of them. Without it, every course exercise
     * of the exercise on the courses is compared, as it always was.
     *
     * Mode 1 used to compare every submission ever made, so a student appeared once per attempt and
     * matched themselves at ~100%; enough attempts filled the returned-pair cap and pushed the real
     * findings out. Either way a student's own submissions are never reported against each other.
     *
     * At least one course is required. An empty list used to be accepted and answered with an empty
     * result, but it registers zero access checks — which stopped being harmless once the exercise's
     * own metadata was read here, because that read then ran unauthorised.
     *
     * `@field:Valid` is not decoration: without it Jakarta does not descend into the list, so the
     * `@NotBlank` and `@Size` on the element ids never run at all. They did not, until this was
     * added — a 200-character id and a blank one both reached the id parser and were echoed back in
     * the error payload and into the log. `@field:Size` on the course list bounds the work one request
     * can ask for, since every course id costs an access check. The submission list deliberately has
     * no such bound: the named cap ([SimilarityService.assertWithinLimit]) is checked on its size
     * before any id reaches a query, and a bean-validation bound in front of it answered a large
     * course with a generic INVALID_PARAMETER_VALUE, which the page reads as a timeout, instead of
     * TOO_MANY_SUBMISSIONS and the limit. Neither list can make validation itself unbounded: it fails
     * fast (`core.conf.ValidationConf`), so a million bad ids cost one violation, not a million.
     */
    data class Req(
        @param:JsonProperty("courses", required = false)
        @field:Valid @field:Size(max = MAX_COURSES)
        val courses: List<ReqCourse>,
        @param:JsonProperty("submissions", required = false)
        @field:Valid
        val submissions: List<ReqSubmission>?,
        /** No bound here either, for the reason the submissions have none: the named cap is checked on its size. */
        @param:JsonProperty("students", required = false)
        @field:Valid
        val students: List<ReqStudent>? = null,
        @param:JsonProperty("course_exercise_id", required = false)
        @field:Size(max = 100)
        val courseExercise: String? = null,
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

    data class ReqStudent(
        @param:JsonProperty("id", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        val id: String
    )

    data class Resp(
        /** Only those referenced by [scores] — see the note where this is built. */
        @get:JsonProperty("submissions") val submissions: List<RespSubmission>,
        /** How many submissions the comparison ran over, including ones no pair references. */
        @get:JsonProperty("compared") val compared: Int,
        @get:JsonProperty("scores") val scores: List<RespScore>,
        /** Which tokeniser actually ran — "python", "java", "sql", or the "text" fallback. */
        @get:JsonProperty("language") val language: String,
        /** Submissions JPlag could not use, so "no similar pairs" cannot quietly mean "half the cohort was dropped". */
        @get:JsonProperty("skipped") val skipped: List<RespSkipped>,
        /** True when more pairs existed than were returned. */
        @get:JsonProperty("truncated") val truncated: Boolean,
        /**
         * Identical submissions by three or more authors, one entry each rather than every pair among
         * them; `scores` then names one member for the whole group. See SimilarityOutcome.groups.
         */
        @get:JsonProperty("groups") val groups: List<RespGroup>,
        /**
         * Which provider measured the scores: `jplag` (tokens, so renaming does not hide a copy) or
         * `edit-distance` (whole texts, character by character). An admin chooses; see SimilarityRegistry.
         */
        @get:JsonProperty("provider") val provider: String,
        /** `full` or `optimised`: the course's setting, or the default. */
        @get:JsonProperty("mode") val mode: String,
        /**
         * Why the comparison did not run as asked, or null when it did: `PINNED_UNAVAILABLE` (the course's
         * pinned provider may not compare this language now) or `LANGUAGE_UNAVAILABLE` (no provider may,
         * so it was compared as text).
         */
        @get:JsonProperty("provider_fallback") val providerFallback: String?,
        /**
         * Whether `scores[].matches` says where each pair matches. When true, an empty list means the pair
         * shares nothing; when false, matches are never sent and the page finds the shared code itself.
         */
        @get:JsonProperty("match_ranges") val matchRanges: Boolean,
        /** Pairs worth comparing - a student's own left out, identical submissions once - and how many were scored. */
        @get:JsonProperty("pairs_total") val pairsTotal: Int,
        @get:JsonProperty("pairs_compared") val pairsCompared: Int,
        /**
         * False when an optimised run stopped at the budget with pairs left: [scores] are the best of the
         * pairs it reached, the likeliest first, and a more similar pair may be among the rest.
         */
        @get:JsonProperty("complete") val complete: Boolean,
        /**
         * `latest` or `first`: which of each student's submissions were compared - or `named`, when the
         * request named the submissions themselves.
         */
        @get:JsonProperty("attempts") val attempts: String,
    )

    data class RespGroup(
        /** Ids of the identical submissions, in id order: the first, the lowest, is the one sent with its code. */
        @get:JsonProperty("submissions") val submissions: List<String>
    )

    data class RespSubmission(
        @get:JsonProperty("id") val id: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        /**
         * Null for a member of an identical group that no pair names and that is not the group's first:
         * its code is the first one's but for layout, and the page shows only that one. Sent in full,
         * a class that mostly handed in one file sent that file once per student.
         */
        @get:JsonProperty("solution") val solution: String?,
        @get:JsonProperty("given_name") val givenName: String,
        @get:JsonProperty("family_name") val familyName: String,
        @get:JsonProperty("course_title") val courseTitle: String,
        /**
         * Who handed it in, for the page to link each side to their grading view: its own list of the
         * course's students names each one's latest submission, and with the first attempts compared, the
         * ids here are not those.
         */
        @get:JsonProperty("student_id") val studentId: String,
        /** Which of the student's attempts this is, from 1: the page says so when it is not their latest. */
        @get:JsonProperty("number") val number: Int,
    )

    data class RespScore(
        @get:JsonProperty("sub_1") val sub1: String,
        @get:JsonProperty("sub_2") val sub2: String,
        /** Average similarity, 0-100. The page's threshold slider filters on [maxSimilarity], not this. */
        @get:JsonProperty("similarity") val similarity: Int,
        @get:JsonProperty("max_similarity") val maxSimilarity: Int,
        @get:JsonProperty("matches") val matches: List<RespMatch>,
        /** What the provider measured, by name - JPlag's similarities and shared tokens, or Levenshtein and Dice. */
        @get:JsonProperty("metrics") val metrics: List<RespMetric> = emptyList(),
        /**
         * The two scores under the names the pre-JPlag page reads, which labels `score_a` "Shared text"
         * (Dice) and `score_b` "Edit distance" (Levenshtein). A tab stays on the bundle it loaded — for
         * days, if it is left open (`webVersion.ts`) — and that page filters on
         * `Math.max(score_a, score_b)`, which is NaN without these, so every pair vanished and it reported
         * nothing similar. Where the provider measured Dice and Levenshtein - edit distance, the rollout
         * default - they are those, so the old page's labels are true; otherwise [similarity] and
         * [maxSimilarity]. Transitional: remove once no pre-JPlag bundle can be open.
         */
        @get:JsonProperty("score_a") val scoreA: Int = metrics.firstOrNull { it.id == "dice" }?.value ?: similarity,
        @get:JsonProperty("score_b") val scoreB: Int = metrics.firstOrNull { it.id == "levenshtein" }?.value ?: maxSimilarity,
    )

    data class RespMetric(
        @get:JsonProperty("id") val id: String,
        /** A whole percent for unit `percent`, a number for unit `count`. */
        @get:JsonProperty("value") val value: Int,
        @get:JsonProperty("unit") val unit: String,
    )

    /** One matched region, as line ranges in each submission. */
    data class RespMatch(
        @get:JsonProperty("start_line_1") val startLine1: Int,
        @get:JsonProperty("end_line_1") val endLine1: Int,
        @get:JsonProperty("start_line_2") val startLine2: Int,
        @get:JsonProperty("end_line_2") val endLine2: Int,
        @get:JsonProperty("tokens") val tokens: Int
    )

    data class RespSkipped(
        @get:JsonProperty("id") val id: String,
        /**
         * `CANNOT_PARSE`, `TOO_SMALL`, `TOO_LARGE`, `TOO_COMPLEX` or `TOO_REPETITIVE` — our own names, mapped in
         * `JPlagProvider.skipReason` or assigned by the provider, rather than passed through from
         * JPlag's enum. `EMPTY` and `UNUSABLE` are mapped too, so the mapping stays exhaustive, but
         * cannot occur today. The page names the skipped students under each reason it knows, and
         * under a generic one for anything else.
         */
        @get:JsonProperty("reason") val reason: String
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises/{exerciseId}/similarity")
    fun controller(
        @PathVariable("exerciseId") exIdString: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        // Counts, not the body. Interpolating the DTO put unvalidated request text straight into the
        // log, and an id containing a newline wrote a line that was indistinguishable from a real
        // one - a teacher could forge an ERROR entry attributed to another component. The ids are in
        // no access log: they travel in the POST body, which nginx does not record and core has no
        // access log of its own. So they are on record only when an error carries one, and there the
        // exception handlers escape line breaks before logging; what they must not be is in a
        // position to end a log line and start a fabricated one.
        log.info {
            "Calculate similarity for exercise '$exIdString' by ${caller.id} over " +
                    "${body.courses.size} course(s), ${body.submissions?.size ?: 0} explicit submission(s), " +
                    "${body.students?.size ?: 0} student(s)"
        }
        val resolved = requests.resolve(exIdString, body.courses.map { it.id }, body.courseExercise, body.submissions?.map { it.id }, body.students?.map { it.id }, caller)
        val binding = resolved.binding
        // Priced before the permit: a cohort the budget cannot cover is refused now, by name, rather
        // than after holding the one permit for the whole budget and timing out (EZ-1667).
        requests.assertAffordable(resolved)

        // The rows are selected by the loader below, which the service runs only once it holds the
        // permit. Loading them out here instead would put every queuing caller's whole cohort on the
        // heap at the same time, which is exactly what the single permit exists to prevent. They come
        // back as part of the result rather than through a captured `var`, so the response cannot be
        // built from rows the comparison never actually ran on.
        val run = try {
            similarityService.compare(
                loadRows = {
                    selectSubmissions(resolved.exerciseId, resolved.courses, resolved.submissionIds).also { rows ->
                        // Not the solution file name: it is teacher-authored text, and a newline in it
                        // would end this line and start a forged one on every run of the exercise.
                        log.info { "Analysing similarity of ${rows.size} submissions as ${binding.language.wireName} with ${binding.provider}" }
                    }
                },
                // The owner is what stops a student's own attempts being reported as plagiarism
                // against each other, whether they arrived as explicit ids or as the latest set.
                asSource = { SimilaritySource(it.resp.id.toLong(), it.solution, it.studentId) },
                binding = binding,
                // No base code. The only template column, anonymous_autoassess_template, is the embed
                // page's starting code for anonymous visitors: course students are never given it, so
                // subtracting it changed every course cohort's scores over a setting that belongs to
                // the embed, and an exercise that gives its starter code in the text got none.
                baseCode = null,
                topN = TOP_PAIRS,
            )
        } catch (_: SimilarityBusy) {
            // One comparison at a time, and this caller waited long enough. A named refusal the UI
            // can explain beats a request that sits on a Tomcat thread until the client gives up.
            throw InvalidRequestException(
                "Another similarity comparison is already running, try again shortly.",
                ReqError.SIMILARITY_BUSY, notify = false,
            )
        } catch (e: SimilarityTooCostly) {
            // Measured as it ran: at its pace it would not have finished, so it was stopped early with the
            // same refusal the estimate gives, rather than at the deadline.
            throw requests.tooCostly(e)
        } catch (e: SimilarityTimedOut) {
            // Not a 500: nothing is broken, the cohort is simply more than one run's budget. Named so
            // the page can say "try a smaller group", which for this one is advice that works — the
            // budget is per run, unlike the permit.
            throw InvalidRequestException(
                "Similarity comparison did not finish within ${e.budgetSec}s; try a smaller group.",
                ReqError.SIMILARITY_TIMEOUT,
                "budget_sec" to e.budgetSec.toString(),
                notify = false,
            )
        }

        val outcome = run.outcome
        // Only the submissions some returned pair or group actually references. The cohort can be 500
        // rows of up to 500,000 characters each (StudentSubmit's limit) while at most 400 of them appear
        // in the up to 200 pairs returned, so shipping all of them made the response mostly payload the page
        // cannot use. `compared` keeps the "n of m could not be compared" line honest now that
        // `submissions` is no longer that m. A group's members are all here, for the page to name them,
        // but only its first carries code, unless a pair names another.
        val paired = outcome.pairs.flatMapTo(mutableSetOf()) { listOf(it.id1.toString(), it.id2.toString()) }
        val coded = paired + outcome.groups.map { it.first().toString() }
        val referenced = paired + outcome.groups.flatten().map { it.toString() }
        return Resp(
            run.rows.map { it.resp }.filter { it.id in referenced }.map { if (it.id in coded) it else it.copy(solution = null) },
            run.rows.size,
            outcome.pairs.map { pair ->
                RespScore(
                    pair.id1.toString(), pair.id2.toString(),
                    // Clamped as a cheap guard only. JPlag's similarity is provably in [0, 1]:
                    // base-code and FILE_END tokens are excluded from matching, so the divisor
                    // cannot go negative, and similarity() short-circuits to 0 when it is zero.
                    // Kept so a future sign error surfaces as a clamp rather than as "-240%".
                    percent(pair.similarity), percent(pair.maxSimilarity),
                    pair.matches.map { RespMatch(it.startLine1, it.endLine1, it.startLine2, it.endLine2, it.tokens) },
                    metrics = pair.metrics.map { metric ->
                        RespMetric(metric.id, if (metric.unit == MetricUnit.SHARE) percent(metric.value) else metric.value.toInt(), metric.unit.wireName)
                    },
                )
            },
            outcome.language.wireName,
            outcome.skipped.map { RespSkipped(it.id.toString(), it.reason) },
            outcome.truncated,
            outcome.groups.map { group -> RespGroup(group.map { it.toString() }) },
            outcome.provider,
            resolved.mode.name.lowercase(),
            binding.fallback,
            similarityService.registry.provider(outcome.provider)?.matchRanges ?: false,
            outcome.pairsTotal,
            outcome.pairsCompared,
            outcome.complete,
            resolved.attemptsName,
        )
    }

    private fun percent(fraction: Double): Int =
        if (fraction.isNaN()) 0 else (fraction * 100).roundToInt().coerceIn(0, 100)

    /**
     * A row plus the author, who is not on the wire but decides which pairs are worth reporting, and the
     * solution as loaded - what is compared, whatever the response then sends of it.
     */
    private data class SubmissionRow(val resp: RespSubmission, val studentId: String, val solution: String)

    /**
     * The chosen submissions, with their solution bodies. Always given a concrete id list, so the
     * count the cap saw and the rows loaded here cannot disagree.
     */
    private fun selectSubmissions(
        exerciseId: Long,
        courses: List<Long>,
        submissionIds: List<Long>
    ): List<SubmissionRow> = transaction {
        if (submissionIds.isEmpty()) return@transaction emptyList()

        val query = (Course innerJoin CourseExercise innerJoin (Submission innerJoin Account))
            .select(
                Course.title,
                Submission.id,
                Submission.createdAt,
                Submission.solution,
                Submission.student,
                Submission.number,
                Account.givenName,
                Account.familyName
            )
            .where {
                CourseExercise.exercise eq exerciseId and
                        (CourseExercise.course inList courses) and
                        (Submission.id inList submissionIds)
            }

        val rows = query.map {
            SubmissionRow(
                RespSubmission(
                    it[Submission.id].value.toString(),
                    it[Submission.createdAt],
                    it[Submission.solution],
                    it[Account.givenName],
                    it[Account.familyName],
                    it[Course.title],
                    it[Submission.student].value,
                    it[Submission.number],
                ),
                it[Submission.student].value,
                it[Submission.solution],
            )
        }

        // Still checked, and still the caller's error: an id the caller named that is not on one of
        // the courses they were asserted on must not be silently dropped, or the endpoint answers
        // about a smaller set than was asked for without saying so. notify = false like the other
        // refusals here: a client naming the wrong id is not an operator alert, and every one of
        // these was mailed to ops.
        if (rows.size < submissionIds.size) {
            throw InvalidRequestException(
                "Number of submissions '${submissionIds.size}' requested to analyze does not equal to the number of submissions found: '${rows.size}'.",
                ReqError.ENTITY_WITH_ID_NOT_FOUND,
                notify = false,
            )
        }
        rows
    }

    companion object {
        /** Unchanged from the previous engine, and what the page's "capped" notice is written against. */
        private const val TOP_PAIRS = 100

        /**
         * Bound on one request's course list, so a single body cannot ask for unbounded access
         * checks: each course id costs one before anything else runs. Comfortably above any real
         * cohort — a similarity run spans the handful of courses sharing an exercise. Submission ids
         * are bounded by the named cap instead; see [Req].
         */
        const val MAX_COURSES = 100
    }
}
