package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
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
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate


private val log = KotlinLogging.logger {}


@Service
class MoodleSyncService {
    @Value("\${easy.core.moodle-sync.users.url}")
    private lateinit var moodleSyncUrl: String

    data class MoodleRespStudent(@JsonProperty("username") val username: String,
                                 @JsonProperty("firstname") val firstname: String,
                                 @JsonProperty("lastname") val lastname: String,
                                 @JsonProperty("email") val email: String,
                                 @JsonProperty("groups", required = false) val groups: List<MoodleRespGroup?>?)

    data class MoodleRespGroup(@JsonProperty("id") val id: String,
                               @JsonProperty("name") val name: String)

    data class MoodleResponse(@JsonProperty("students")
                              val students: List<MoodleRespStudent>)

    data class MoodleCron(val courseId: Long, val moodleShortName: String)


    fun queryStudents(moodleShortName: String): MoodleResponse {
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


    fun syncCourse(moodleResponse: MoodleResponse, courseId: Long, moodleCourseShortName: String): AdminLinkMoodleCourseController.Resp {
        return transaction {
            // Link course info
            Course.update({ Course.id eq courseId }) {
                it[moodleShortName] = moodleCourseShortName
            }

            // Get Account ids of the users who have easy account
            val easyAccountExists =
                    moodleResponse.students.mapNotNull {
                        Account.slice(Account.id)
                                .select {
                                    (Account.moodleUsername eq it.username)
                                }
                                .map { it[Account.id].value }
                                .firstOrNull()
                    }.toSet()

            // Users who have easy account and have access to this course
            val courseAccessExists =
                    moodleResponse.students.mapNotNull {
                        (Account innerJoin Student innerJoin StudentCourseAccess)
                                .slice(Account.id)
                                .select {
                                    (Account.moodleUsername eq it.username) and (StudentCourseAccess.course eq courseId)
                                }
                                .map { it[Account.id].value }
                                .firstOrNull()
                    }.toSet()

            // Users who have easy account but no access
            val easyAccountNoAccess = easyAccountExists subtract courseAccessExists

            // Users who have no easy account
            val noAccountExists = moodleResponse.students
                    .filter { Account.select { Account.moodleUsername eq it.username }.count() == 0 }

            log.debug { "Granting access to Moodle students with accounts (the rest already have access): $easyAccountNoAccess" }
            log.debug { "Setting pending access to Moodle students with no accounts: $noAccountExists" }
            log.debug { "Removing student access for students who are not anymore on the Moodle course." }

            // Add access to course for users with Moodle and Easy account
            val studentsAdded = StudentCourseAccess.batchInsert(easyAccountNoAccess) {
                this[StudentCourseAccess.student] = EntityID(it, Student)
                this[StudentCourseAccess.course] = EntityID(courseId, Course)
            }.count()

            // Remove previous pending access
            StudentMoodlePendingAccess.deleteWhere { StudentMoodlePendingAccess.course eq courseId }

            // Create pending access for Moodle students without Easy account
            val pendingStudentsAdded = StudentMoodlePendingAccess.batchInsert(noAccountExists) {
                this[StudentMoodlePendingAccess.course] = EntityID(courseId, Course)
                this[StudentMoodlePendingAccess.moodleUsername] = it.username
            }.count()

            val usersWhoShouldHaveAccess = easyAccountNoAccess union courseAccessExists
            // Revoke student access for course if not anymore present in the Moodle
            val studentAccessRemoved = StudentCourseAccess
                    .slice(StudentCourseAccess.student)
                    .select { (StudentCourseAccess.course eq courseId and
                            (StudentCourseAccess.student notInList usersWhoShouldHaveAccess) ) }
                    .map { it[StudentCourseAccess.student].value }
                    .map {
                        StudentCourseAccess.deleteWhere {
                            (StudentCourseAccess.student eq it) and
                                    (StudentCourseAccess.course eq courseId)
                        }
                    }
                    .size

            AdminLinkMoodleCourseController.Resp(studentsAdded, pendingStudentsAdded, studentAccessRemoved)
        }
    }


    @Scheduled(cron = "\${easy.core.moodle-sync.users.cron}")
    fun syncMoodle() {
        transaction {
            Course.select {
                log.debug { "Checking for courses for Moodle syncing..." }
                Course.moodleShortName.isNotNull() and (Course.moodleShortName neq "")
            }.mapNotNull {
                MoodleCron(it[Course.id].value, it[Course.moodleShortName]!!)
            }.forEach {
                log.debug { "Cron Moodle syncing ${it.moodleShortName} with course ${it.courseId}." }
                syncCourse(queryStudents(it.moodleShortName), it.courseId, it.moodleShortName)
            }
        }
    }
}
