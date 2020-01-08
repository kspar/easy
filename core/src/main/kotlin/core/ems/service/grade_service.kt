package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import core.db.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
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

private val log = KotlinLogging.logger {}


@Service
class GradeService {

    @Value("\${easy.core.moodle-sync.grades.url}")
    private lateinit var moodleGradeUrl: String


    data class MoodleReq(@JsonProperty("shortname") val shortname: String,
                         @JsonProperty("exercises") val exercises: List<MoodleReqExercise>)

    data class MoodleReqExercise(@JsonProperty("idnumber") val idnumber: String,
                                 @JsonProperty("title") val title: String,
                                 @JsonProperty("grades") val grades: List<MoodleReqGrade>)


    data class MoodleReqGrade(@JsonProperty("username") val username: String,
                              @JsonProperty("grade") val grade: Int)


    /**
     * Sync single submission grade to Moodle. If the submission has no link with the Moodle, then nothing is done. Is asynchronous.
     */
    @Async
    fun syncSingleGradeToMoodle(submissionId: Long) {
        transaction {
            (Submission innerJoin CourseExercise innerJoin Course)
                    .slice(Course.id, CourseExercise.id)
                    .select { Submission.id eq submissionId }
                    .single()
                    .apply {
                        val shortname = selectCourseShortName(this[Course.id].value)

                        if (!shortname.isNullOrBlank()) {
                            val singleExercise = selectSingleCourseExerciseSubmission(
                                    this[Course.id].value,
                                    this[CourseExercise.id].value,
                                    submissionId)

                            if (singleExercise.grades.isNotEmpty()) {
                                sendMoodleGradeRequest(MoodleReq(shortname, listOf(singleExercise)))
                            } else {
                                log.debug { "Skipping Moodle grade sync due to no existing grades to sync." }
                            }


                        } else {
                            log.debug { "Skipping Moodle grade sync due to empty course shortname." }
                        }
                    }
        }
    }


    /**
     * Sync single course all grades to Moodle.
     */
    fun syncCourseGradesToMoodle(courseId: Long) {
        val shortname = selectCourseShortName(courseId)

        if (shortname.isNullOrBlank()) {
            log.debug { "Course $courseId is not synced due to no link with Moodle." }

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


    /**
     * Send grade request to Moodle. Excepts response body from Moodle to contain 'done'.
     */
    private fun sendMoodleGradeRequest(req: MoodleReq) {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        val map: MultiValueMap<String, String> = LinkedMultiValueMap()
        map.add("data", jacksonObjectMapper().writeValueAsString(req))
        val request = HttpEntity(map, headers)

        val responseEntity: ResponseEntity<String> = RestTemplate().postForEntity(moodleGradeUrl, request, String::class.java)

        if (responseEntity.statusCode.value() != 200) {
            log.error { "Moodle grade syncing error ${responseEntity.statusCodeValue} with data $req" }
            throw InvalidRequestException("Grade syncing with Moodle failed due to error code in response.",
                    ReqError.MOODLE_GRADE_SYNC_ERROR,
                    notify = true)
        }

        val body = responseEntity.body
        if (body == null || !body.contains("done")) {
            log.error { "Moodle grade syncing error. Grade syncing with Moodle failed due to response body from Moodle did not contain 'done': ${responseEntity.body}. Data: $req" }
            throw InvalidRequestException("Grade syncing with Moodle failed due to response body from Moodle did not contain 'done'.",
                    ReqError.MOODLE_GRADE_SYNC_ERROR,
                    notify = true)
        }
        log.debug { "Grades sync response: $body" }
    }


    /**
     * Helper function to generate grade batches of 200.
     */
    private fun batchGrades(courseShortName: String, exercises: List<MoodleReqExercise>): List<MoodleReq> {
        return exercises.flatMap {
            it.grades.chunked(200) { grades ->
                MoodleReq(courseShortName, listOf(MoodleReqExercise(it.idnumber, it.title, grades)))
            }
        }
    }


    private fun selectSingleCourseExerciseSubmission(courseId: Long, courseExId: Long, submissionId: Long): MoodleReqExercise {
        return transaction {
            (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                    .slice(CourseExercise.id, ExerciseVer.title, CourseExercise.titleAlias, CourseExercise.moodleExId)
                    .select { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() and (CourseExercise.id eq courseExId) }
                    .map { ex ->
                        MoodleReqExercise(
                                ex[CourseExercise.moodleExId] ?: ex[CourseExercise.id].value.toString(),
                                ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                                listOfNotNull(selectLatestGradeForSubmission(submissionId))
                        )
                    }.single()
        }
    }


    private fun selectCourseShortName(courseId: Long): String? {
        return transaction {
            Course.slice(Course.moodleShortName)
                    .select {
                        Course.id eq courseId
                    }.map { it[Course.moodleShortName] }
                    .firstOrNull()
        }
    }


    private fun selectExercisesOnCourse(courseId: Long): List<MoodleReqExercise> {
        return transaction {
            (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                    .slice(CourseExercise.id, ExerciseVer.title, CourseExercise.titleAlias, CourseExercise.moodleExId)
                    .select { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
                    .map { ex ->

                        val grades =
                                selectLatestSubmissionsForExercise(ex[CourseExercise.id].value)
                                        .mapNotNull {
                                            selectLatestGradeForSubmission(it)
                                        }

                        MoodleReqExercise(
                                ex[CourseExercise.moodleExId] ?: ex[CourseExercise.id].value.toString(),
                                ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                                grades
                        )
                    }
        }
    }


    private fun selectLatestGradeForSubmission(submissionId: Long): MoodleReqGrade? {
        val moodleUsername = (Submission innerJoin Student innerJoin Account)
                .slice(Account.moodleUsername, Account.id)
                .select { Submission.id eq submissionId }
                .map {
                    val moodleUsername = it[Account.moodleUsername]
                    if (moodleUsername != null) {
                        moodleUsername
                    } else {
                        log.warn { "Unable to sync grades to Moodle for student ${it[Account.id]} because they have no Moodle username" }
                        return@selectLatestGradeForSubmission null
                    }
                }
                .single()

        val teacherGrade = TeacherAssessment
                .slice(TeacherAssessment.grade)
                .select { TeacherAssessment.submission eq submissionId }
                .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
                .limit(1)
                .map { assessment -> MoodleReqGrade(moodleUsername, assessment[TeacherAssessment.grade]) }
                .firstOrNull()

        if (teacherGrade != null)
            return teacherGrade

        return AutomaticAssessment
                .slice(AutomaticAssessment.grade)
                .select { AutomaticAssessment.submission eq submissionId }
                .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
                .limit(1)
                .map { assessment ->
                    MoodleReqGrade(moodleUsername, assessment[AutomaticAssessment.grade])
                }
                .firstOrNull()
    }
}
