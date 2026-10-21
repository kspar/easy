package core.ems.service.code

import core.conf.security.EasyUser
import core.db.AutoExercise
import core.db.CourseExercise
import core.db.CourseSimilaritySettings
import core.db.Exercise
import core.db.ExerciseVer
import core.db.SimilarityMode
import core.db.Submission
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.code.similarity.SimilarityBinding
import core.ems.service.code.similarity.SimilarityEstimate
import core.ems.service.code.similarity.SimilarityLanguage
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.code.similarity.SimilarityTooCostly
import core.ems.service.code.similarity.SimilarityUnavailable
import core.ems.service.code.similarity.SizedSource
import core.ems.service.code.similarity.TooManySubmissions
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.charLength
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

/**
 * Everything a similarity check decides before it asks for the permit, shared by the check itself and
 * by its estimate (EZ-1667): who may ask, which submissions, which language, which provider and mode,
 * and what it would cost. The two must agree - an estimate that resolved the cohort differently from
 * the run would price one comparison and refuse or admit another.
 */
@Service
class SimilarityRequests(private val similarityService: SimilarityService) {

    /** A check resolved down to what it will run: no solution text has been read yet. */
    class Resolved(
        val exerciseId: Long,
        val courses: List<Long>,
        val submissionIds: List<Long>,
        val language: SimilarityLanguage,
        val mode: SimilarityMode,
        /** With the course's mode in it: [SimilarityBinding.optimised]. */
        val binding: SimilarityBinding,
    )

    fun resolve(exIdString: String, courseIds: List<String>, submissionIds: List<String>?, caller: EasyUser): Resolved {
        val exerciseId = exIdString.idToLongOrInvalidReq()
        val courses = courseIds.map { it.idToLongOrInvalidReq() }

        // An empty list would register zero access checks and assert nothing. The submissions query
        // survives that (an empty IN matches no rows), but selectExerciseMeta would then run entirely
        // unauthorised, turning this endpoint into an existence oracle over the whole exercise table.
        if (courses.isEmpty()) throw InvalidRequestException(
            "At least one course must be given.", ReqError.INVALID_PARAMETER_VALUE, notify = false
        )

        caller.assertAccess {
            courses.forEach { teacherOnCourse(it) }
        }

        // Resolved to concrete ids before anything else happens, which is what lets the cap be
        // checked without a COUNT and without a window in which rows can appear between counting and
        // selecting. Without explicit ids this now means the *latest* submission per student, not
        // every attempt ever made: comparing all of them let one student's resubmissions pair with
        // each other at ~100% and, at fifteen attempts, fill the whole top-N cap with self-pairs and
        // push every real finding out of the response. The page always sent explicit ids and so
        // never saw this; the endpoint is documented as callable without them.
        //
        // Distinct, because the query below answers a repeated id with one row: counted twice, it
        // failed the found-count check and was reported as not found although it plainly exists, and
        // the cap counted it twice as well.
        val ids = submissionIds?.map { it.idToLongOrInvalidReq() }?.distinct()
            ?: selectLatestSubmissionIds(exerciseId, courses)

        // The cap exists to stop a huge cohort reaching the heap at all, so it is checked before any
        // solution text is read — and only here: the loader selects exactly these ids, so a second
        // check after loading could never fire. Mapped here, because an unmapped TooManySubmissions
        // would leave as a bare RuntimeException and reach the generic handler — a 500 with an empty
        // body and a system-notification email, for a condition the API has a named 400 for.
        try {
            similarityService.assertWithinLimit(ids.size)
        } catch (e: TooManySubmissions) {
            throw InvalidRequestException(
                "Similarity can compare at most ${e.limit} submissions at once, ${e.requested} were requested.",
                ReqError.TOO_MANY_SUBMISSIONS,
                "limit" to e.limit.toString(), "requested" to e.requested.toString(),
                notify = false,
            )
        }

        // One cheap row, and it decides the tokeniser, so it is read before the permit is asked for.
        val exercise = selectExerciseMeta(exerciseId, courses)
        val language = SimilarityLanguage.of(exercise.solutionFileName, exercise.containerImage)
        // Which provider compares it is the admin's to say (see SimilarityRegistry), and known before the
        // permit is asked for, so a language nobody may compare is refused without anybody waiting.
        // A course's own settings apply when the check is about that course alone - which the page always
        // asks; across several courses there is no one course's choice to follow, so the defaults do.
        val courseSettings = courses.singleOrNull()?.let(::selectCourseSettings)
        val binding = try {
            similarityService.bind(language, courseSettings?.provider)
        } catch (e: SimilarityUnavailable) {
            throw InvalidRequestException(
                "Similarity is not available for ${e.language.wireName} here.",
                ReqError.SIMILARITY_UNAVAILABLE,
                "language" to e.language.wireName,
                notify = false,
            )
        }
        val mode = courseSettings?.mode ?: SimilarityMode.OPTIMISED
        return Resolved(exerciseId, courses, ids, language, mode, binding.copy(optimised = mode == SimilarityMode.OPTIMISED))
    }

    /** What running [resolved] would take, from the submissions' sizes; no text leaves the database. */
    fun estimate(resolved: Resolved): SimilarityEstimate =
        similarityService.estimate(resolved.binding, selectSizes(resolved))

    /**
     * [estimate], refused when over the budget - a named 400 that says how long it would have taken and
     * what the budget is, so the page can say "about 9 minutes; try a group" rather than wait two minutes
     * for a timeout that was certain from the start.
     */
    fun assertAffordable(resolved: Resolved): SimilarityEstimate = try {
        similarityService.assertAffordable(resolved.binding, selectSizes(resolved))
    } catch (e: SimilarityTooCostly) {
        throw InvalidRequestException(
            "Similarity comparison would take about ${e.estimate.seconds}s, over the ${e.estimate.budgetSec}s budget; try a smaller group.",
            ReqError.SIMILARITY_TOO_COSTLY,
            "estimated_sec" to e.estimate.seconds.toString(),
            "budget_sec" to e.estimate.budgetSec.toString(),
            notify = false,
        )
    }

    private class CourseSettings(val provider: String?, val mode: SimilarityMode)

    private fun selectCourseSettings(courseId: Long): CourseSettings? = transaction {
        CourseSimilaritySettings.selectAll().where { CourseSimilaritySettings.course eq courseId }.singleOrNull()
            ?.let { CourseSettings(it[CourseSimilaritySettings.provider], it[CourseSimilaritySettings.mode]) }
    }

    private data class ExerciseMeta(val solutionFileName: String, val containerImage: String?)

    /**
     * The current version's solution file name and its grader's container image, if it has one - the
     * two language hints the schema has. [SimilarityLanguage.of] explains why it takes both: an SQL
     * exercise's file name is often still the default `lahendus.py`.
     *
     * Joined through [CourseExercise] and restricted to the courses the caller was just asserted on.
     * Keyed on `exerciseId` alone this leaked: any teacher could probe any exercise id and tell an
     * existing one from a missing one by the status code, and read its `solution_file_name` through
     * the response's `language`. Every other exercise read in this codebase reaches the row through a
     * course or through the library-dir checks, and so does this one now.
     */
    private fun selectExerciseMeta(exerciseId: Long, courses: List<Long>): ExerciseMeta = transaction {
        (Exercise innerJoin ExerciseVer innerJoin CourseExercise)
            .join(AutoExercise, JoinType.LEFT, ExerciseVer.autoExerciseId, AutoExercise.id)
            .select(ExerciseVer.solutionFileName, AutoExercise.containerImage)
            .where {
                Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() and
                        (CourseExercise.course inList courses)
            }
            .map { ExerciseMeta(it[ExerciseVer.solutionFileName], it.getOrNull(AutoExercise.containerImage)?.value) }
            .distinct()
            .singleOrNull()
            // notify = false like every other refusal here, and like assertExerciseIsOnCourse, which
            // states the same fact: a client-side id mismatch is not an operator alert.
            ?: throw InvalidRequestException(
                "Exercise $exerciseId not found", ReqError.ENTITY_WITH_ID_NOT_FOUND, notify = false
            )
    }

    /**
     * The newest submission per student per course exercise, ids only.
     *
     * Deliberately selects no solution text: this runs before the cap is checked and before the
     * permit is taken, so it must not be the thing that puts a cohort on the heap. The ordering plus
     * `associate` is the idiom from [selectLatestSubmissionsForExercise] — last write per key wins —
     * extended to a key of (course exercise, student) because this endpoint spans several courses
     * and the same student can appear in more than one.
     */
    private fun selectLatestSubmissionIds(exerciseId: Long, courses: List<Long>): List<Long> = transaction {
        (CourseExercise innerJoin Submission)
            .select(Submission.id, Submission.student, Submission.courseExercise)
            .where { CourseExercise.exercise eq exerciseId and (CourseExercise.course inList courses) }
            .orderBy(
                Submission.createdAt to SortOrder.ASC,
                Submission.number to SortOrder.ASC,
                Submission.id to SortOrder.ASC,
            )
            .associate { (it[Submission.courseExercise].value to it[Submission.student].value) to it[Submission.id].value }
            .values
            .toList()
    }

    /**
     * Each chosen submission's length in characters and its author, and - only where another has the
     * same length, since only those can be identical - an md5 of its text, all computed by the database,
     * so the cohort is priced without one solution reaching the heap. It runs before the permit, as often
     * as the page asks: hashing every text on every call made the database read and hash the whole
     * cohort each time a teacher changed group. Restricted like the loader, to the exercise on the
     * asserted courses; an id that is not there is left out here and refused by the loader when the
     * check runs.
     */
    private fun selectSizes(resolved: Resolved): List<SizedSource> = transaction {
        if (resolved.submissionIds.isEmpty()) return@transaction emptyList()
        val chars = Submission.solution.charLength()
        val chosen = (CourseExercise innerJoin Submission).select(Submission.id, Submission.student, chars).where {
            CourseExercise.exercise eq resolved.exerciseId and
                    (CourseExercise.course inList resolved.courses) and
                    (Submission.id inList resolved.submissionIds)
        }.map { Triple(it[Submission.id].value, it[Submission.student].value, it[chars] ?: 0) }

        val alike = chosen.groupBy { it.third }.values.filter { it.size > 1 }.flatten().map { it.first }
        val digest = CustomFunction("md5", TextColumnType(), Submission.solution)
        val digests = if (alike.isEmpty()) emptyMap() else Submission.select(Submission.id, digest)
            .where { Submission.id inList alike }
            .associate { it[Submission.id].value to it[digest] }
        chosen.map { (id, owner, length) -> SizedSource(id, length, owner, digests[id]) }
    }
}
