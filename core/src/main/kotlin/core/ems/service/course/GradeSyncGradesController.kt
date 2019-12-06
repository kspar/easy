package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectLatestSubmissionsForExercise
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
import org.springframework.security.access.annotation.Secured
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate


private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v2")
class GradeSyncGradesController {

    @Value("\${easy.core.moodle-sync.grades.url}")
    private lateinit var moodleGradeUrl: String

    data class MoodleReq(@JsonProperty("shortname") val shortname: String,
                         @JsonProperty("exercises") val exercises: List<MoodleReqExercise>)

    data class MoodleReqExercise(@JsonProperty("idnumber") val idnumber: String,
                                 @JsonProperty("title") val title: String,
                                 @JsonProperty("grades") val grades: List<MoodleReqGrade>)


    data class MoodleReqGrade(@JsonProperty("username") val username: String,
                              @JsonProperty("grade") val grade: Int)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodlesync/grades")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser) {

        log.debug { "Syncing grades for course $courseIdStr with Moodle" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        if (!isCoursePresent(courseId)) {
            throw InvalidRequestException("Course $courseId does not exist")
        }

        if (!isMoodleLinked(courseId)) {
            throw InvalidRequestException("Course $courseId is not linked with Moodle")
        }

        syncGrades(courseId, moodleGradeUrl)
    }
}


private fun isMoodleLinked(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId and Course.moodleShortName.isNotNull()
        }.count() > 0
    }
}


private fun isCoursePresent(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId
        }.count() > 0
    }
}


private fun syncGrades(courseId: Long, url: String) {
    // TODO: probably need to split between different requests e.g. 200 grades per request
    val data = selectGradesResponse(courseId)

    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
    val map: MultiValueMap<String, String> = LinkedMultiValueMap()
    map.add("data", jacksonObjectMapper().writeValueAsString(data))
    val request = HttpEntity(map, headers)

    val responseEntity: ResponseEntity<String> = RestTemplate().postForEntity(url, request, String::class.java)

    if (responseEntity.statusCode.value() != 200) {
        log.error { "Moodle grade syncing error ${responseEntity.statusCodeValue} with data $data" }
        throw InvalidRequestException("Grade syncing with Moodle failed due to error code in response.",
                ReqError.MOODLE_GRADE_SYNC_ERROR,
                notify = true)
    }

    log.debug { "Grades sync response: ${responseEntity.body}" }
}


private fun selectGradesResponse(courseId: Long): GradeSyncGradesController.MoodleReq {
    return transaction {
        val shortname = Course.slice(Course.moodleShortName)
                .select {
                    Course.id eq courseId
                }.map { it[Course.moodleShortName] }
                .requireNoNulls()
                .single()

        val exercises = selectExercisesOnCourse(courseId)
        GradeSyncGradesController.MoodleReq(shortname, exercises)
    }
}


private fun selectExercisesOnCourse(courseId: Long): List<GradeSyncGradesController.MoodleReqExercise> {
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

                    GradeSyncGradesController.MoodleReqExercise(
                            ex[CourseExercise.moodleExId] ?: ex[CourseExercise.id].value.toString(),
                            ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                            grades
                    )
                }
    }
}


private fun selectLatestGradeForSubmission(submissionId: Long): GradeSyncGradesController.MoodleReqGrade? {
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
            .map { assessment -> GradeSyncGradesController.MoodleReqGrade(moodleUsername, assessment[TeacherAssessment.grade]) }
            .firstOrNull()

    if (teacherGrade != null)
        return teacherGrade

    return AutomaticAssessment
            .slice(AutomaticAssessment.grade)
            .select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { assessment ->
                GradeSyncGradesController.MoodleReqGrade(moodleUsername, assessment[AutomaticAssessment.grade])
            }
            .firstOrNull()
}
