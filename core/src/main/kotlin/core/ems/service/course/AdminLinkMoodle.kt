package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access.assertUserHasAccessToCourse
import core.ems.service.course.AdminLinkMoodleCourseController.MoodleResponse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.access.annotation.Secured
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminLinkMoodleCourseController {

    data class Req(@JsonProperty("moodle_course_id", required = true) @field:NotBlank @field:Size(max = 500)
                   val moodleCourseShortName: String)


    data class MoodleStudent(@JsonProperty("username") val username: String,
                             @JsonProperty("firstname") val firstname: String,
                             @JsonProperty("lastname") val lastname: String,
                             @JsonProperty("email") val email: String,
                             @JsonProperty("groups", required = false) val groups: List<MoodleGroup?>?)

    data class MoodleGroup(@JsonProperty("id") val id: String,
                           @JsonProperty("name") val name: String)

    data class MoodleResponse(@JsonProperty("students")
                              val students: List<MoodleStudent>)


    @Secured("ROLE_ADMIN")
    @PostMapping("/admin/courses/{courseIdStr}/moodle")
    fun controller(@PathVariable courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser) {
        log.debug { "${caller.id} is one-way linking Moodle course '${dto.moodleCourseShortName}' with '$courseIdStr'" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        checkIfCourseExists(courseId)
        assertUserHasAccessToCourse(caller, courseId)
        val students = queryStudents(dto.moodleCourseShortName)
        log.debug { students }
        // TODO 0: fix encoding issue
        // TODO 1: update db
        // TODO 2: insert pending access students
        // TODO 3: insert non-pending access students
        // TODO 4: create and update tests
        // TODO 5: Document API
    }
}


private fun checkIfCourseExists(courseId: Long) {
    transaction {
        if (Course.select { Course.id eq courseId }.count() != 1) {
            throw InvalidRequestException("Course with $courseId does not exist.")
        }
    }
}


private fun queryStudents(moodleShortName: String): MoodleResponse {
    //TODO ?: from properties
    val moodleUrl = "https://moodledev.ut.ee/local/lahendus/get_course_users.php"

    log.info { "Connecting Moodle for course linking..." }

    val template = RestTemplate()
    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
    val map = LinkedMultiValueMap<String, String>()
    map.add("shortname", moodleShortName)
    val request = HttpEntity<MultiValueMap<String, String>>(map, headers)
    val responseEntity = template.postForEntity(moodleUrl, request, MoodleResponse::class.java)


    if (responseEntity.statusCode.isError) {
        log.error { "Moodle linking error ${responseEntity.statusCodeValue} with request $request" }
        throw InvalidRequestException("Course linking with Moodle failed due to error code in response.",
                ReqError.MOODLE_LINKING_ERROR,
                "Moodle response" to responseEntity.statusCodeValue.toString(),
                notify = false)
    }

    val response = responseEntity.body
    if (response == null) {
        log.error { "Moodle returned empty response with request $request" }
        throw InvalidRequestException("Course linking with Moodle failed due to Moodle empty response body.",
                ReqError.MOODLE_EMPTY_RESPONSE,
                "Moodle response" to responseEntity.statusCodeValue.toString(),
                notify = false)
    }
    return response
}