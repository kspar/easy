package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.Student
import core.db.StudentCourseAccess
import core.db.StudentMoodlePendingAccess
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.course.AdminLinkMoodleCourseController.MoodleResponse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Value
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

    @Value("\${easy.core.service.moodle.sync.url}")
    private lateinit var moodleSyncUrl: String

    data class Req(@JsonProperty("moodle_short_name", required = true) @field:NotBlank @field:Size(max = 500)
                   val moodleShortName: String)

    data class Resp(@JsonProperty("students_added") val studentsAdded: Int,
                    @JsonProperty("pending_students_added") val pendingStudentsAdded: Int,
                    @JsonProperty("student_access_removed") val studentAccessRemoved: Int)

    data class MoodleRespStudent(@JsonProperty("username") val username: String,
                                 @JsonProperty("firstname") val firstname: String,
                                 @JsonProperty("lastname") val lastname: String,
                                 @JsonProperty("email") val email: String,
                                 @JsonProperty("groups", required = false) val groups: List<MoodleRespGroup?>?)

    data class MoodleRespGroup(@JsonProperty("id") val id: String,
                               @JsonProperty("name") val name: String)

    data class MoodleResponse(@JsonProperty("students")
                              val students: List<MoodleRespStudent>)


    @Secured("ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodle")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.debug { "${caller.id} is one-way linking Moodle course '${dto.moodleShortName}' with '$courseIdStr'" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        checkIfCourseExists(courseId)
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        val students = queryStudents(dto.moodleShortName, moodleSyncUrl)
        return syncCourse(students, courseId, dto.moodleShortName)
    }
}


private fun syncCourse(moodleResponse: MoodleResponse, courseId: Long, moodleCourseShortName: String): AdminLinkMoodleCourseController.Resp {
    return transaction {
        // Link course info
        Course.update({ Course.id eq courseId }) {
            it[moodleShortName] = moodleCourseShortName
        }

        // Filter out Moodle students who have Easy account
        val accountExists =
                moodleResponse.students.mapNotNull {
                    (Student innerJoin StudentCourseAccess)
                            .slice(Student.id)
                            .select {
                                (Student.moodleUsername eq it.username) and
                                        (StudentCourseAccess.student eq it.username) and
                                        (StudentCourseAccess.course eq courseId)
                            }
                            .map { it[Student.id].value }
                            .firstOrNull()
                }

        // Filter Moodle users who have no Easy account
        val noAccountExists = moodleResponse.students
                .filter { Student.select { Student.moodleUsername eq it.username }.count() == 0 }

        log.debug { "Granting access to Moodle students with accounts (the rest already have access): $accountExists" }
        log.debug { "Setting pending access to Moodle students with no accounts: $noAccountExists" }
        log.debug { "Removing student access for students who are not anymore on the Moodle course." }

        // Add access to course for users with Moodle and Easy account
        val studentsAdded = StudentCourseAccess.batchInsert(accountExists) {
            this[StudentCourseAccess.student] = EntityID(it, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }.count()

        // Remove previous pending access
        StudentMoodlePendingAccess.deleteWhere { StudentMoodlePendingAccess.course eq courseId }

        // Create pending access for Moodle students without Easy account
        val pendingStudentsAdded = StudentMoodlePendingAccess.batchInsert(noAccountExists) {
            this[StudentMoodlePendingAccess.course] = EntityID(courseId, Course)
            this[StudentMoodlePendingAccess.utUsername] = it.username
        }.count()

        // Revoke student access for course if not anymore present in the Moodle
        val studentAccessRemoved = (Student innerJoin StudentCourseAccess)
                .slice(Student.id)
                .select { (StudentCourseAccess.course eq courseId) }
                .mapNotNull { it[Student.id].value }
                .filter { !accountExists.contains(it) }
                .map {
                    StudentCourseAccess.deleteWhere {
                        (StudentCourseAccess.student eq it) and
                                (StudentCourseAccess.course eq courseId)
                    }
                }
                .sum()

        AdminLinkMoodleCourseController.Resp(studentsAdded, pendingStudentsAdded, studentAccessRemoved)
    }
}


private fun checkIfCourseExists(courseId: Long) {
    transaction {
        if (Course.select { Course.id eq courseId }.count() != 1) {
            throw InvalidRequestException("Course with $courseId does not exist.")
        }
    }
}


private fun queryStudents(moodleShortName: String, moodleSyncUrl: String): MoodleResponse {
    log.info { "Connecting Moodle ($moodleSyncUrl) for course linking..." }

    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

    val map = LinkedMultiValueMap<String, String>()
    map.add("shortname", moodleShortName)

    val request = HttpEntity<MultiValueMap<String, String>>(map, headers)
    val responseEntity = RestTemplate().postForEntity(moodleSyncUrl, request, MoodleResponse::class.java)

    if (responseEntity.statusCode.value() != 200) {
        log.error { "Moodle linking error ${responseEntity.statusCodeValue} with request $request" }
        throw InvalidRequestException("Course linking with Moodle failed due to error code in response.",
                ReqError.MOODLE_LINKING_ERROR,
                "Moodle response" to responseEntity.statusCodeValue.toString(),
                notify = true)
    }

    val response = responseEntity.body
    if (response == null) {
        log.error { "Moodle returned empty response with request $request" }
        throw InvalidRequestException("Course linking with Moodle failed due to Moodle empty response body.",
                ReqError.MOODLE_EMPTY_RESPONSE,
                "Moodle response" to responseEntity.statusCodeValue.toString(),
                notify = true)
    }
    return response
}