package core.ems.service.code

import core.conf.security.EasyUser
import core.db.AutoExercise
import core.db.CourseExercise
import core.db.CourseExerciseSimilaritySettings
import core.db.CourseSimilaritySettings
import core.db.Exercise
import core.db.ExerciseVer
import core.db.SimilarityAttempts
import core.db.SimilarityMode
import core.db.Submission
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.code.similarity.SimilarityBinding
import core.ems.service.code.similarity.SimilarityCohort
import core.ems.service.code.similarity.SimilarityLanguage
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.code.similarity.SimilarityUnavailable
import core.ems.service.code.similarity.SizedSource
import core.ems.service.code.similarity.TooManySubmissions
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

/**
 * Everything a similarity check decides before it asks for the permit, shared by the check itself and by
 * the count the page shows before it (EZ-1667): who may ask, which submissions, which language, which
 * provider and mode, and how many pairs that makes. The two must agree - a count that resolved the cohort
 * differently from the run would tell the teacher about one comparison and run another.
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
        /** Where the provider choice came from, and the mode: the exercise's own, the course's, or the default. */
        val providerFrom: SettingsSource = SettingsSource.DEFAULT,
        val modeFrom: SettingsSource = SettingsSource.DEFAULT,
        /**
         * Which of each student's submissions [submissionIds] are, and where that came from: the setting's,
         * or null when the request named the submissions, which then are what they are ([SettingsSource.REQUEST]).
         */
        val attempts: SimilarityAttempts? = SimilarityAttempts.LATEST,
        val attemptsFrom: SettingsSource = SettingsSource.DEFAULT,
    ) {
        /** [attempts] on the wire: `latest`, `first`, or `named` for submissions the request named. */
        val attemptsName: String get() = attempts?.name?.lowercase() ?: "named"
    }

    /** Where a setting came from. [REQUEST] only for attempts: the submissions were named. */
    enum class SettingsSource { EXERCISE, COURSE, DEFAULT, REQUEST }

    fun resolve(
        exIdString: String,
        courseIds: List<String>,
        courseExerciseId: String?,
        submissionIds: List<String>?,
        studentIds: List<String>?,
        caller: EasyUser,
    ): Resolved {
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
        // The one the page lists, when it says: the exercise can be on a course twice, and the students'
        // submissions to the other are not the ones it shows or links to.
        val courseExercise = courseExerciseId?.idToLongOrInvalidReq()?.also { assertCourseExercise(exerciseId, courses, it) }

        // Resolved to concrete ids before anything else happens, which is what lets the cap be
        // checked without a COUNT and without a window in which rows can appear between counting and
        // selecting. Without explicit ids this now means the *latest* submission per student, not
        // every attempt ever made: comparing all of them let one student's resubmissions pair with
        // each other at ~100% and, at fifteen attempts, fill the whole top-N cap with self-pairs and
        // push every real finding out of the response. The page always named its cohort - by ids, now by
        // students - and so never saw this; the endpoint is documented as callable without them.
        //
        // Distinct, because the query below answers a repeated id with one row: counted twice, it
        // failed the found-count check and was reported as not found although it plainly exists, and
        // the cap counted it twice as well.
        //
        // A course's own settings apply when the check is about that course alone - which the page always
        // asks; across several courses there is no one course's choice to follow, so the defaults do. The
        // exercise's own, where it has them, come before the course's, each setting on its own. Read here,
        // because which attempts are compared decides the ids.
        val courseSettings = courses.singleOrNull()?.let(::selectCourseSettings)
        val exerciseSettings = courses.singleOrNull()?.let { selectExerciseSettings(exerciseId, it, courseExercise) }
        val attempts = exerciseSettings?.attempts ?: courseSettings?.attempts ?: SimilarityAttempts.LATEST
        val students = studentIds?.distinct()
        // Sent beside the students only for a core that reads no students: here, those decide.
        val named = if (students != null) null else submissionIds?.map { it.idToLongOrInvalidReq() }?.distinct()
        // A list of students is capped before it reaches a query, as a list of ids is: each brings at least
        // one submission, so more of them than the cap is refused whatever they have handed in.
        students?.let { assertWithinLimit(it.size) }
        val ids = when {
            named != null -> named
            students != null -> selectAttemptsOf(exerciseId, courses, courseExercise, attempts, students)
            else -> selectSubmissionIds(exerciseId, courses, courseExercise, attempts).values.toList()
        }

        // The cap exists to stop a huge cohort reaching the heap at all, so it is checked before any
        // solution text is read — and only here: the loader selects exactly these ids, so a second
        // check after loading could never fire.
        assertWithinLimit(ids.size)
        // Named ids are there, before the permit: found missing only by the loader, a wrong id waited behind
        // a running check - and was refused as busy, if another waited too - rather than as not found.
        if (named != null) assertNamedFound(exerciseId, courses, named)

        // One cheap row, and it decides the tokeniser, so it is read before the permit is asked for.
        val exercise = selectExerciseMeta(exerciseId, courses)
        val language = SimilarityLanguage.of(exercise.solutionFileName, exercise.containerImage)
        // Which provider compares it is the admin's to say (see SimilarityRegistry), and known before the
        // permit is asked for, so a language nobody may compare is refused without anybody waiting.
        val ownProvider = exerciseSettings != null && exerciseSettings.ownProvider
        val pinned = if (exerciseSettings != null && exerciseSettings.ownProvider) exerciseSettings.provider else courseSettings?.provider
        val binding = try {
            similarityService.bind(language, pinned)
        } catch (e: SimilarityUnavailable) {
            throw InvalidRequestException(
                "Similarity is not available for ${e.language.wireName} here.",
                ReqError.SIMILARITY_UNAVAILABLE,
                "language" to e.language.wireName,
                notify = false,
            )
        }
        val mode = exerciseSettings?.mode ?: courseSettings?.mode ?: SimilarityMode.OPTIMISED
        return Resolved(
            exerciseId, courses, ids, language, mode, binding.copy(optimised = mode == SimilarityMode.OPTIMISED),
            providerFrom = when {
                ownProvider -> SettingsSource.EXERCISE
                courseSettings != null -> SettingsSource.COURSE
                else -> SettingsSource.DEFAULT
            },
            modeFrom = when {
                exerciseSettings?.mode != null -> SettingsSource.EXERCISE
                courseSettings != null -> SettingsSource.COURSE
                else -> SettingsSource.DEFAULT
            },
            attempts = attempts.takeIf { named == null },
            attemptsFrom = when {
                named != null -> SettingsSource.REQUEST
                exerciseSettings?.attempts != null -> SettingsSource.EXERCISE
                courseSettings != null -> SettingsSource.COURSE
                else -> SettingsSource.DEFAULT
            },
        )
    }

    /**
     * Mapped here, because an unmapped TooManySubmissions would leave as a bare RuntimeException and reach
     * the generic handler — a 500 with an empty body and a system-notification email, for a condition the
     * API has a named 400 for.
     */
    private fun assertWithinLimit(requested: Int) {
        try {
            similarityService.assertWithinLimit(requested)
        } catch (e: TooManySubmissions) {
            throw InvalidRequestException(
                "Similarity can compare at most ${e.limit} submissions at once, ${e.requested} were requested.",
                ReqError.TOO_MANY_SUBMISSIONS,
                "limit" to e.limit.toString(), "requested" to e.requested.toString(),
                notify = false,
            )
        }
    }

    /** What running [resolved] would compare, from the submissions' sizes; no text leaves the database. */
    fun count(resolved: Resolved): SimilarityCohort =
        similarityService.count(resolved.binding, selectSizes(resolved))

    private class CourseSettings(val provider: String?, val mode: SimilarityMode, val attempts: SimilarityAttempts)

    private fun selectCourseSettings(courseId: Long): CourseSettings? = transaction {
        CourseSimilaritySettings.selectAll().where { CourseSimilaritySettings.course eq courseId }.singleOrNull()
            ?.let { CourseSettings(it[CourseSimilaritySettings.provider], it[CourseSimilaritySettings.mode], it[CourseSimilaritySettings.attempts]) }
    }

    private class ExerciseSettings(val ownProvider: Boolean, val provider: String?, val mode: SimilarityMode?, val attempts: SimilarityAttempts?)

    /**
     * The exercise's own settings on [courseId], if it has any: [courseExercise]'s, when the request names
     * it. Should the exercise be on the course twice and the request not say which, the first course
     * exercise's settings are the ones read, every time.
     */
    private fun selectExerciseSettings(exerciseId: Long, courseId: Long, courseExercise: Long?): ExerciseSettings? = transaction {
        (CourseExerciseSimilaritySettings innerJoin CourseExercise).selectAll()
            .where {
                var where = CourseExercise.exercise eq exerciseId and (CourseExercise.course eq courseId)
                if (courseExercise != null) where = where and (CourseExercise.id eq courseExercise)
                where
            }
            .orderBy(CourseExercise.id)
            .firstOrNull()
            ?.let {
                ExerciseSettings(
                    it[CourseExerciseSimilaritySettings.ownProvider],
                    it[CourseExerciseSimilaritySettings.provider],
                    it[CourseExerciseSimilaritySettings.mode],
                    it[CourseExerciseSimilaritySettings.attempts],
                )
            }
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
     * The newest submission per student per course exercise - or with [SimilarityAttempts.FIRST] the
     * oldest - ids only, by course exercise and student, of every author or of [students] alone.
     *
     * Deliberately selects no solution text: this runs before the cap is checked and before the
     * permit is taken, so it must not be the thing that puts a cohort on the heap. The ordering plus
     * `associate` is the idiom from [selectLatestSubmissionsForExercise] — last write per key wins —
     * extended to a key of (course exercise, student) because this endpoint spans several courses
     * and the same student can appear in more than one. For the first attempt the order is reversed,
     * so the last write is the oldest.
     */
    private fun selectSubmissionIds(
        exerciseId: Long,
        courses: List<Long>,
        courseExercise: Long?,
        attempts: SimilarityAttempts,
        students: List<String>? = null,
    ): Map<Pair<Long, String>, Long> = transaction {
        val order = if (attempts == SimilarityAttempts.FIRST) SortOrder.DESC else SortOrder.ASC
        (CourseExercise innerJoin Submission)
            .select(Submission.id, Submission.student, Submission.courseExercise)
            .where {
                var where = CourseExercise.exercise eq exerciseId and (CourseExercise.course inList courses)
                if (courseExercise != null) where = where and (CourseExercise.id eq courseExercise)
                if (students != null) where = where and (Submission.student inList students)
                where
            }
            .orderBy(Submission.createdAt to order, Submission.number to order, Submission.id to order)
            .associate { (it[Submission.courseExercise].value to it[Submission.student].value) to it[Submission.id].value }
    }

    /**
     * The submission of each of [students] that [attempts] names, on each course exercise they handed in
     * on - or on [courseExercise] alone. Refused like the loader refuses an id it cannot find: a student
     * with nothing handed in here, dropped, would have the check answer for fewer students than were asked
     * for and not say so.
     */
    private fun selectAttemptsOf(
        exerciseId: Long,
        courses: List<Long>,
        courseExercise: Long?,
        attempts: SimilarityAttempts,
        students: List<String>,
    ): List<Long> {
        val chosen = selectSubmissionIds(exerciseId, courses, courseExercise, attempts, students)
        val found = chosen.keys.mapTo(HashSet()) { it.second }
        if (found.size < students.size) throw InvalidRequestException(
            "Number of students '${students.size}' requested to analyze does not equal to the number of students with a submission: '${found.size}'.",
            ReqError.ENTITY_WITH_ID_NOT_FOUND,
            notify = false,
        )
        return chosen.values.toList()
    }

    /**
     * Refused unless every one of [named] is a submission to [exerciseId] on one of [courses], which the caller
     * was asserted on - so one on another course is refused as if it were not there, as the loader refuses it.
     */
    private fun assertNamedFound(exerciseId: Long, courses: List<Long>, named: List<Long>) = transaction {
        val found = (CourseExercise innerJoin Submission).select(Submission.id).where {
            CourseExercise.exercise eq exerciseId and
                    (CourseExercise.course inList courses) and
                    (Submission.id inList named)
        }.count()
        if (found < named.size) throw InvalidRequestException(
            "Number of submissions '${named.size}' requested to analyze does not equal to the number of submissions found: '$found'.",
            ReqError.ENTITY_WITH_ID_NOT_FOUND,
            notify = false,
        )
    }

    /** Refused unless [courseExercise] is [exerciseId] on one of [courses], which the caller was asserted on. */
    private fun assertCourseExercise(exerciseId: Long, courses: List<Long>, courseExercise: Long) = transaction {
        val found = CourseExercise.select(CourseExercise.id).where {
            CourseExercise.id eq courseExercise and (CourseExercise.exercise eq exerciseId) and (CourseExercise.course inList courses)
        }.empty().not()
        if (!found) throw InvalidRequestException(
            "Course exercise $courseExercise not found", ReqError.ENTITY_WITH_ID_NOT_FOUND, notify = false
        )
    }

    /**
     * Each chosen submission's length and its author, and - only where another has the same length, since
     * only those can be identical - an md5 of its text, all computed by the database, so the cohort is
     * counted without one solution reaching the heap. It runs before the permit, as often as the page asks:
     * hashing every text on every call made the database read and hash the whole cohort each time a
     * teacher changed group.
     *
     * The length is in UTF-8 bytes, `octet_length`, which Postgres reads from a stored value's header;
     * `char_length` decoded every solution to count it. The providers cap solutions in UTF-8 bytes too, so
     * the count skips what the run skips.
     *
     * Restricted like the loader, to the exercise on the asserted courses. An id named that is not there was
     * refused by [resolve] already: left out, the count answered for the rest and enabled a check the loader
     * then refused, after the permit.
     */
    private fun selectSizes(resolved: Resolved): List<SizedSource> = transaction {
        if (resolved.submissionIds.isEmpty()) return@transaction emptyList()
        val bytes = CustomFunction("octet_length", IntegerColumnType(), Submission.solution)
        val chosen = (CourseExercise innerJoin Submission).select(Submission.id, Submission.student, bytes).where {
            CourseExercise.exercise eq resolved.exerciseId and
                    (CourseExercise.course inList resolved.courses) and
                    (Submission.id inList resolved.submissionIds)
        }.map { Triple(it[Submission.id].value, it[Submission.student].value, it[bytes] ?: 0) }

        // Blank ones, which no provider compares: counted, a whitespace-only answer made pairs the run then
        // skipped, and the count before Find disagreed with the one after. Only the short texts are read for
        // it, so counting still costs the database next to nothing; a blank one longer than this is rare
        // enough to be counted as it is.
        val short = chosen.filter { it.third <= BLANK_READ_BYTES }.map { it.first }
        val blank = if (short.isEmpty()) emptySet() else Submission.select(Submission.id, Submission.solution)
            .where { Submission.id inList short }
            .filter { it[Submission.solution].isBlank() }
            .mapTo(HashSet()) { it[Submission.id].value }

        val alike = chosen.groupBy { it.third }.values.filter { it.size > 1 }.flatten().map { it.first }
        val digest = CustomFunction("md5", TextColumnType(), Submission.solution)
        val digests = if (alike.isEmpty()) emptyMap() else Submission.select(Submission.id, digest)
            .where { Submission.id inList alike }
            .associate { it[Submission.id].value to it[digest] }
        chosen.map { (id, owner, length) -> SizedSource(id, length, owner, digests[id], id in blank) }
    }

    private companion object {
        /** The longest solution read to see whether it is blank, in UTF-8 bytes. */
        const val BLANK_READ_BYTES = 1_000
    }
}
