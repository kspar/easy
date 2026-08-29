package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.ems.service.selectLatestSubmissionsForExercise
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.exception.ResourceLockedException
import core.util.DBBackedLock
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.boot.restclient.RestTemplateBuilder
import tools.jackson.module.kotlin.jacksonObjectMapper


@Service
class MoodleGradesSyncService(
    restTemplateBuilder: RestTemplateBuilder,
    private val courseAllowlist: MoodleCourseAllowlist,
) {
    private val restTemplate = restTemplateBuilder.build()

    @Value($$"${easy.core.moodle-sync.grades.url}")
    private lateinit var moodleGradeUrl: String

    @Value($$"${easy.core.moodle-sync.wstoken}")
    private lateinit var wstoken: String

    @Value($$"${easy.core.moodle-sync.moodlewsrestformat}")
    private lateinit var moodlewsrestformat: String

    @Value($$"${easy.core.moodle-sync.grades.wsfunction}")
    private lateinit var wsfunction: String

    // Fallback for naming somebody in a row-creation call when the course has no Moodle-linked
    // students of its own. Empty means there is no fallback. See rowCreationUsername().
    @Value($$"${easy.core.moodle-sync.grades.row-creation-fallback-username:}")
    private lateinit var rowCreationFallbackUsername: String



    val syncGradesLock = DBBackedLock(Course, Course.moodleSyncGradesInProgress)


    data class MoodleReq(
        @get:JsonProperty("shortname") val shortname: String,
        @get:JsonProperty("exercises") val exercises: List<MoodleReqExercise>
    )

    data class MoodleReqExercise(
        @get:JsonProperty("idnumber") val idnumber: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("grades") val grades: List<MoodleReqGrade>
    )


    /**
     * A grade for one student, or — with a null [grade] — a way of naming a student and asking for
     * nothing to be written about them. See [rowCreationGrades].
     */
    data class MoodleReqGrade(
        @get:JsonProperty("username") val username: String,
        @get:JsonProperty("grade") val grade: Int?
    )

    companion object {
        /**
         * The request body, as Moodle's web-services layer wants it: bracketed form fields at the
         * top level, no wrapper.
         *
         * **This used to be `data=<the whole thing as a JSON string>`** and that was a leftover from
         * the protocol this one replaced. EZ-1688 (4c527f65) moved both syncs onto Moodle web
         * services by adding `wstoken`/`wsfunction`/`moodlewsrestformat` to each request, but only
         * the students side had its payload adapted — its `shortname` is a plain scalar and so was
         * already valid. Grades kept a body shaped for the bespoke `/local/lahendus/import.php`
         * script of 2019, which the web-service function rejects with `invalid_parameter_exception`.
         * Nothing failed at build time, because the difference is entirely in how a form is encoded.
         *
         * Confirmed against the live function on 2026-08-29 by sending all three candidate shapes:
         * this one answered `{"success": true}`; `data=<json>` and `data[exercises][0][...]` both
         * answered `invalidparameter`.
         *
         * The DTOs keep their `@JsonProperty` names because the field names here must match them —
         * writing the same names twice is the cost of Moodle not accepting the JSON we already know
         * how to produce.
         */
        internal fun encodeGradeRequest(req: MoodleReq): MultiValueMap<String, String> {
            val map: MultiValueMap<String, String> = LinkedMultiValueMap()
            map.add("shortname", req.shortname)
            req.exercises.forEachIndexed { i, exercise ->
                map.add("exercises[$i][idnumber]", exercise.idnumber)
                map.add("exercises[$i][title]", exercise.title)
                exercise.grades.forEachIndexed { j, grade ->
                    map.add("exercises[$i][grades][$j][username]", grade.username)
                    // A null grade omits the key rather than sending it empty, and the difference is
                    // the whole of rowCreationGrades(): `grade=` is refused, an absent `grade` is
                    // accepted and writes nothing.
                    grade.grade?.let { map.add("exercises[$i][grades][$j][grade]", it.toString()) }
                }
            }
            return map
        }

        /**
         * Whether Moodle reported the grades as written.
         *
         * **This used to be `body.contains("done")`**, which is the other half of the same
         * unmigrated protocol: the current function answers `{"success": true}`, so a sync that
         * worked would have been reported as a failure. Two bugs in a row meant fixing the encoding
         * alone would have looked like no progress at all.
         *
         * Parsed rather than substring-matched, because `"success"` appears in Moodle's *failure*
         * bodies too — an error mentioning a function name containing it, say — and a grade sync
         * that silently reports success it did not get is the one outcome worse than a false alarm.
         */
        internal fun isGradeSyncSuccess(body: String?): Boolean {
            if (body == null) return false
            val parsed = try {
                jacksonObjectMapper().readValue(body, Map::class.java)
            } catch (e: Exception) {
                log.warn { "Could not parse Moodle grade sync response as JSON: $body. Error: $e" }
                return false
            }
            return parsed["success"] == true
        }

        private val log = KotlinLogging.logger {}
    }

    /**
     * Sync single submission grade to Moodle. If the submission has no link with the Moodle, then nothing is done. Is asynchronous.
     */
    @Async
    fun syncSingleGradeToMoodle(submissionId: Long) {
        transaction {
            (Submission innerJoin CourseExercise innerJoin Course)
                .select(Course.id, CourseExercise.id, Course.moodleShortName, Course.moodleSyncGrades)
                .where { Submission.id eq submissionId }
                .single()
                .apply {
                    val shortname = this[Course.moodleShortName]
                    val isGradesSynced = this[Course.moodleSyncGrades]

                    // isAllowed rather than assertAllowed: this runs on every grade in the ordinary
                    // flow, so on an environment with an allowlist it would otherwise throw
                    // constantly for every course not on it. The throw stays at the send site, where
                    // reaching it means something got past this and is worth an alarm.
                    if (!shortname.isNullOrBlank() && isGradesSynced && courseAllowlist.isAllowed(shortname)) {
                        val singleExercise = selectSingleCourseExerciseSubmission(
                            this[Course.id].value,
                            this[CourseExercise.id].value,
                            submissionId
                        )

                        if (singleExercise.grades.isNotEmpty()) {
                            sendMoodleGradeRequest(MoodleReq(shortname, listOf(singleExercise)))
                            val grade = singleExercise.grades[0]
                            log.info { "Moodle synced grade ${grade.grade} for ${grade.username} to exercise ${singleExercise.idnumber} on course $shortname" }
                        } else {
                            log.warn { "Skipping Moodle grade sync due to no existing grades to sync." }
                        }
                    }
                }
        }
    }


    /**
     * Sync all grades on a single course to Moodle. Respects grade sync locking.
     *
     * @throws ResourceLockedException if grades sync is already in progress
     */
    fun syncCourseGradesToMoodle(courseId: Long) {
        syncGradesLock.with(courseId) {
            val shortname = selectCourseShortName(courseId)

            if (shortname.isNullOrBlank()) {
                log.warn { "Course $courseId is not synced due to no link with Moodle." }

            } else {
                // Send grades in batches of 200
                val exercises = selectExercisesOnCourse(courseId)
                val rowCreationUsername = rowCreationUsername(courseId)
                val batches = batchGrades(shortname, exercises, rowCreationUsername)

                // Every batch is attempted, and one failure no longer takes the rest with it. This
                // was `batches.forEach { sendMoodleGradeRequest(it) }`, so the first throw abandoned
                // the loop — and the batches are ordered by exercise, not by likelihood of working,
                // so a single unsendable exercise near the front silently cost every course grade
                // behind it.
                //
                // The two kinds of failure are not the same and are not treated the same:
                //
                //  - a batch that carries grades and fails is a real problem, so those are collected
                //    and thrown at the end, after the rest have had their turn;
                //  - a batch with no grades is the row-creation call, and Moodle currently refuses
                //    every one of them. That is not our bug and there is nothing to fix here: the
                //    grades key cannot be sent empty over form encoding, and the function requires
                //    it. Sending them anyway is deliberate — the day the plugin accepts a missing
                //    key as an empty list, rows start being created again with no change here.
                val failures = mutableListOf<String>()
                batches.forEach { batch ->
                    log.debug { "Sending grade batch: $batch" }
                    try {
                        sendMoodleGradeRequest(batch)
                    } catch (e: InvalidRequestException) {
                        if (batch.exercises.any { it.grades.isNotEmpty() }) {
                            failures += batch.exercises.map { it.idnumber }.joinToString(",")
                            log.error { "Grade batch failed for course $courseId: $batch. Error: $e" }
                        } else {
                            log.warn {
                                "Moodle refused the row-creation call for exercise(s) " +
                                        "${batch.exercises.map { it.idnumber }} on course $courseId. " +
                                        "Expected until the Moodle plugin accepts an absent 'grades' " +
                                        "key as an empty list; grades themselves are unaffected."
                            }
                        }
                    }
                }

                if (failures.isNotEmpty()) {
                    throw InvalidRequestException(
                        "Grade syncing with Moodle failed for ${failures.size} of ${batches.size} batches.",
                        ReqError.MOODLE_GRADE_SYNC_ERROR,
                        "Failed exercises" to failures.joinToString(";"),
                        notify = true
                    )
                }

                // Last, and only after the grades that could be sent have been. Creating a gradebook
                // row means naming somebody on the course, and here there is nobody to name — so the
                // grades synced but the rows for unattempted exercises did not appear, and saying so
                // is better than a sync that silently does three-quarters of its job.
                //
                // notify = false: this is a course that needs a student, not an incident.
                val ungraded = exercises.count { it.grades.isEmpty() }
                if (rowCreationUsername == null && ungraded > 0) {
                    log.warn {
                        "Course $courseId has $ungraded exercise(s) with no grades and no Moodle " +
                                "username to create their gradebook rows with."
                    }
                    throw InvalidRequestException(
                        "Grades were synced, but $ungraded exercise(s) with no grades could not get " +
                                "a Moodle gradebook row. Creating one requires naming a student who " +
                                "is on the course in Moodle, and this course has none with a Moodle " +
                                "username. Add at least one active student and sync again.",
                        ReqError.MOODLE_GRADE_SYNC_ERROR,
                        notify = false
                    )
                }
            }
        }
    }


    /**
     * Send grade request to Moodle. Expects a JSON body of `{"success": true}`.
     */
    private fun sendMoodleGradeRequest(req: MoodleReq) {
        // The one that matters most: grades reach Moodle from ordinary grading, not from an
        // endpoint, so this is the only place every path passes through.
        courseAllowlist.assertAllowed(req.shortname)
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        val map = encodeGradeRequest(req)
        map.add("wstoken", wstoken)
        map.add("wsfunction", wsfunction)
        map.add("moodlewsrestformat", moodlewsrestformat)
        val request = HttpEntity(map, headers)

        val responseEntity: ResponseEntity<String> =
            restTemplate.postForEntity(moodleGradeUrl, request, String::class.java)

        // Note that Moodle answers 200 for its own errors too, with the failure in the body — so
        // this catches transport and proxy failures only, never a rejected request.
        if (responseEntity.statusCode.value() != 200) {
            log.error { "Moodle grade syncing error ${responseEntity.statusCode.value()} with data $req" }
            throw InvalidRequestException(
                "Grade syncing with Moodle failed due to error code in response.",
                ReqError.MOODLE_GRADE_SYNC_ERROR,
                notify = true
            )
        }

        val body = responseEntity.body
        if (!isGradeSyncSuccess(body)) {
            log.error { "Moodle grade syncing error. Moodle did not report success: $body. Data: $req" }
            throw InvalidRequestException(
                "Grade syncing with Moodle failed: Moodle did not report success.",
                ReqError.MOODLE_GRADE_SYNC_ERROR,
                notify = true
            )
        }
    }


    /**
     * Helper function to generate grade batches of 200.
     */
    private fun batchGrades(
        courseShortName: String,
        exercises: List<MoodleReqExercise>,
        rowCreationUsername: String?
    ): List<MoodleReq> =
        exercises.flatMap {
            val chunks = it.grades.chunked(200) { grades ->
                MoodleReq(courseShortName, listOf(MoodleReqExercise(it.idnumber, it.title, grades.toMutableList())))
            }
            when {
                chunks.isNotEmpty() -> chunks

                // Sync exercises with no grades — this is what creates the row in Moodle's gradebook,
                // confirmed on 2026-08-29 by sending an idnumber Moodle had never seen and watching
                // the row appear. See rowCreationGrades() for why it cannot be an empty list.
                rowCreationUsername != null -> listOf(
                    MoodleReq(
                        courseShortName,
                        listOf(MoodleReqExercise(it.idnumber, it.title, rowCreationGrades(rowCreationUsername)))
                    )
                )

                // Nobody to name, so the request could only be refused. Dropped rather than sent and
                // failed, and reported once by syncCourseGradesToMoodle instead of once per exercise.
                else -> emptyList()
            }
        }

    /**
     * How an exercise nobody has a grade for still gets its gradebook row created.
     *
     * `grades` must be present and non-empty — an empty list cannot even be expressed over form
     * encoding — but **`grade` inside an entry is optional**. So one entry naming any enrolled
     * student, with no `grade` key at all, satisfies the parameter and asks for nothing to be
     * written: Moodle creates the row and the student's cell stays blank. Verified against the live
     * function on 2026-08-29 with an idnumber it had never seen.
     *
     * That asymmetry is almost certainly an oversight in the plugin rather than a design, which is
     * why this is written as a workaround with its reasoning attached rather than as the obvious way
     * to do it. If `grades` is ever given `VALUE_DEFAULT([])`, delete this and send the empty list.
     *
     * Everything else was refused: an unknown username (usernames are resolved against real
     * accounts), `grade=` empty, `grade=null`, and every spelling of an empty list. A *numeric*
     * grade does work — but Moodle clamps it to the item's minimum, so it shows as a `0` somebody
     * did not earn. Which is the trap this avoids: the obvious fix writes a visible zero, and only
     * omitting the key does not.
     *
     * The username is any student on the course, and which one is immaterial precisely because
     * nothing is written about them. A course with no Moodle-linked students yields an empty list,
     * the request is refused, and [syncCourseGradesToMoodle] tolerates it — no row, no harm.
     */
    private fun rowCreationGrades(username: String): List<MoodleReqGrade> =
        listOf(MoodleReqGrade(username, null))

    /**
     * Who to name in the row-creation call, in order of preference.
     *
     * 1. **A student on the course, picked at random.** Random rather than first because nothing is
     *    written about them and there is no reason for one student to appear in every such request
     *    for the life of a course — if this ever does leak into a log or an export, it should not
     *    always be the same person.
     * 2. **The configured fallback**, for a course whose students are not Moodle-linked yet. It has
     *    to be a real Moodle account, and — established the hard way on 2026-08-29 — **enrolled on
     *    the course**: a username that merely exists is refused. So the fallback only helps where
     *    that one account happens to be on the course, which is why it is second and not first.
     * 3. **Nobody**, and then rows cannot be created at all. [syncCourseGradesToMoodle] says so
     *    rather than failing quietly.
     */
    private fun rowCreationUsername(courseId: Long): String? =
        selectRandomMoodleUsernameOnCourse(courseId)
            ?: rowCreationFallbackUsername.trim().ifBlank { null }

    /** A random Moodle username from the course's students, or null if it has none. */
    private fun selectRandomMoodleUsernameOnCourse(courseId: Long): String? = transaction {
        StudentCourseAccess
            .select(StudentCourseAccess.moodleUsername)
            .where { StudentCourseAccess.course eq courseId and StudentCourseAccess.moodleUsername.isNotNull() }
            .mapNotNull { it[StudentCourseAccess.moodleUsername] }
            .randomOrNull()
    }


    private fun selectSingleCourseExerciseSubmission(
        courseId: Long,
        courseExId: Long,
        submissionId: Long
    ): MoodleReqExercise =
        transaction {
            (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .select(CourseExercise.id, ExerciseVer.title, CourseExercise.titleAlias, CourseExercise.moodleExId)
                .where { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() and (CourseExercise.id eq courseExId) }
                .map { ex ->
                    MoodleReqExercise(
                        ex[CourseExercise.moodleExId] ?: ex[CourseExercise.id].value.toString(),
                        ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                        listOfNotNull(selectLatestGradeForSubmission(submissionId, courseId))
                    )
                }.single()
        }


    private fun selectExercisesOnCourse(courseId: Long): List<MoodleReqExercise> = transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(
                CourseExercise.id, ExerciseVer.title, CourseExercise.titleAlias, CourseExercise.moodleExId,
                CourseExercise.orderIdx
            )
            .where { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
            .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
            .map { ex ->

                val grades =
                    selectLatestSubmissionsForExercise(ex[CourseExercise.id].value)
                        .mapNotNull {
                            selectLatestGradeForSubmission(it, courseId)
                        }

                MoodleReqExercise(
                    ex[CourseExercise.moodleExId] ?: ex[CourseExercise.id].value.toString(),
                    ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                    grades
                )
            }
    }


    private fun selectLatestGradeForSubmission(submissionId: Long, courseId: Long): MoodleReqGrade? =
        (Submission innerJoin Account innerJoin StudentCourseAccess)
            .select(StudentCourseAccess.moodleUsername, Account.id, Submission.grade)
            .where { (Submission.id eq submissionId) and (StudentCourseAccess.course eq courseId) }
            .map {
                val moodleUsername = it[StudentCourseAccess.moodleUsername]
                val grade = it[Submission.grade]

                when {
                    moodleUsername == null -> {
                        log.warn { "Unable to sync grades to Moodle for student ${it[Account.id]} because they have no Moodle username" }
                        return@selectLatestGradeForSubmission null
                    }

                    grade == null -> return@selectLatestGradeForSubmission null
                    else -> MoodleReqGrade(moodleUsername, grade)
                }

            }
            .singleOrNull()
}
