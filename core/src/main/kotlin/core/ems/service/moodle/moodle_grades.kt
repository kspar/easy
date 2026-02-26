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
import org.springframework.web.client.RestTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper


@Service
class MoodleGradesSyncService {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.moodle-sync.grades.url}")
    private lateinit var moodleGradeUrl: String

    val syncGradesLock = DBBackedLock(Course, Course.moodleSyncGradesInProgress)


    data class MoodleReq(
        @param:JsonProperty("shortname") val shortname: String,
        @param:JsonProperty("exercises") val exercises: List<MoodleReqExercise>
    )

    data class MoodleReqExercise(
        @param:JsonProperty("idnumber") val idnumber: String,
        @param:JsonProperty("title") val title: String,
        @param:JsonProperty("grades") val grades: List<MoodleReqGrade>
    )


    data class MoodleReqGrade(
        @param:JsonProperty("username") val username: String,
        @param:JsonProperty("grade") val grade: Int
    )


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

                    if (!shortname.isNullOrBlank() && isGradesSynced) {
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
                val batches = batchGrades(shortname, exercises)

                batches.forEach {
                    log.debug { "Sending grade batch: $it" }
                    sendMoodleGradeRequest(it)
                }
            }
        }
    }


    /**
     * Send grade request to Moodle. Excepts response body from Moodle to contain 'done'.
     */
    private fun sendMoodleGradeRequest(req: MoodleReq) {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        val map: MultiValueMap<String, String> = LinkedMultiValueMap()
        map.add("data", jacksonObjectMapper().writeValueAsString(req))
        val request = HttpEntity(map, headers)

        val responseEntity: ResponseEntity<String> =
            RestTemplate().postForEntity(moodleGradeUrl, request, String::class.java)

        if (responseEntity.statusCode.value() != 200) {
            log.error { "Moodle grade syncing error ${responseEntity.statusCode.value()} with data $req" }
            throw InvalidRequestException(
                "Grade syncing with Moodle failed due to error code in response.",
                ReqError.MOODLE_GRADE_SYNC_ERROR,
                notify = true
            )
        }

        val body = responseEntity.body
        if (body == null || !body.contains("done")) {
            log.error { "Moodle grade syncing error. Grade syncing with Moodle failed due to response body from Moodle did not contain 'done': ${responseEntity.body}. Data: $req" }
            throw InvalidRequestException(
                "Grade syncing with Moodle failed due to response body from Moodle did not contain 'done'.",
                ReqError.MOODLE_GRADE_SYNC_ERROR,
                notify = true
            )
        }
    }


    /**
     * Helper function to generate grade batches of 200.
     */
    private fun batchGrades(courseShortName: String, exercises: List<MoodleReqExercise>): List<MoodleReq> =
        exercises.flatMap {
            val chunks = it.grades.chunked(200) { grades ->
                MoodleReq(courseShortName, listOf(MoodleReqExercise(it.idnumber, it.title, grades.toMutableList())))
            }
            if (chunks.isNotEmpty()) {
                chunks
            } else {
                // Sync exercises with no grades
                listOf(MoodleReq(courseShortName, listOf(MoodleReqExercise(it.idnumber, it.title, emptyList()))))
            }
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
