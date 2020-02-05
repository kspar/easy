package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Autowired
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


data class MoodleSyncedStudents(val syncedStudents: Int, val syncedPendingStudents: Int)

@Service
class MoodleSyncService {
    @Value("\${easy.core.moodle-sync.users.url}")
    private lateinit var moodleSyncUrl: String

    @Autowired
    private lateinit var mailService: SendMailService

    data class MoodleRespStudent(@JsonProperty("username") val username: String,
                                 @JsonProperty("firstname") val firstname: String,
                                 @JsonProperty("lastname") val lastname: String,
                                 @JsonProperty("email") val email: String,
                                 @JsonProperty("groups", required = false) val groups: List<MoodleRespGroup>?)

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

    fun syncCourse(moodleResponse: MoodleResponse, courseId: Long, moodleCourseShortName: String): MoodleSyncedStudents {
        data class MoodleGroup(val id: String, val name: String)
        data class NewAccess(val username: String, val moodleUsername: String, val groups: List<MoodleGroup>)
        data class NewPendingAccess(val email: String, val moodleUsername: String, val groups: List<MoodleGroup>)

        val courseEntity = EntityID(courseId, Course)

        return transaction {
            // Insert groups or get their IDs
            val moodleGroups = moodleResponse.students
                    .mapNotNull { it.groups }
                    .flatten()
                    .map { it.name }

            val groupNamesToIds = moodleGroups.map { moodleGroupName ->
                val groupId = Group.select { Group.course eq courseId and (Group.name eq moodleGroupName) }
                        .map { it[Group.id] }
                        .singleOrNull()
                        ?: Group.insertAndGetId {
                            it[name] = moodleGroupName
                            it[course] = courseEntity
                        }
                moodleGroupName to groupId
            }.toMap()


            // Partition users by whether they have an Easy account
            val newAccesses =
                    moodleResponse.students.flatMap { moodleStudent ->
                        val moodleEmail = moodleStudent.email.toLowerCase()
                        Account.slice(Account.id)
                                .select {
                                    Account.moodleUsername eq moodleStudent.username or
                                            (Account.email eq moodleEmail)
                                }
                                .withDistinct()
                                .map {
                                    NewAccess(
                                            it[Account.id].value,
                                            moodleStudent.username,
                                            moodleStudent.groups?.map { MoodleGroup(it.id, it.name) } ?: emptyList()
                                    )
                                }
                                .also {
                                    if (it.size > 1) {
                                        log.warn { "Several accounts found with Moodle username ${moodleStudent.username} or email $moodleEmail: $it" }
                                        mailService.sendSystemNotification(
                                                "Several accounts with Moodle username ${moodleStudent.username} or email $moodleEmail found on course $courseId: $it")
                                    }
                                }
                    }
            log.debug { "Giving access to students: $newAccesses" }

            val newPendingAccesses = moodleResponse.students
                    .filter { moodleStudent ->
                        newAccesses.none { it.moodleUsername == moodleStudent.username }
                    }.map {
                        NewPendingAccess(
                                it.email.toLowerCase(),
                                it.username,
                                it.groups?.map { MoodleGroup(it.id, it.name) } ?: emptyList()
                        )
                    }
            log.debug { "Giving pending access to students: $newPendingAccesses" }


            // Remove & insert all accesses
            StudentCourseAccess.deleteWhere { StudentCourseAccess.course eq courseId }

            newAccesses.forEach { newAccess ->
                Account.update({ Account.id eq newAccess.username }) {
                    it[moodleUsername] = newAccess.moodleUsername
                }
                val accessId = StudentCourseAccess.insertAndGetId {
                    it[student] = EntityID(newAccess.username, Student)
                    it[course] = courseEntity
                }
                StudentGroupAccess.batchInsert(newAccess.groups) {
                    this[StudentGroupAccess.student] = newAccess.username
                    this[StudentGroupAccess.course] = courseId
                    this[StudentGroupAccess.group] = groupNamesToIds.getValue(it.name)
                    this[StudentGroupAccess.courseAccess] = accessId
                }
            }


            // Remove & insert all pending accesses
            StudentMoodlePendingAccess.deleteWhere { StudentMoodlePendingAccess.course eq courseId }

            newPendingAccesses.forEach { newPendingAccess ->
                val accessId = StudentMoodlePendingAccess.insertAndGetId {
                    it[moodleUsername] = newPendingAccess.moodleUsername
                    it[course] = courseEntity
                    it[email] = newPendingAccess.email
                }
                StudentMoodlePendingGroup.batchInsert(newPendingAccess.groups) {
                    this[StudentMoodlePendingGroup.moodleUsername] = newPendingAccess.moodleUsername
                    this[StudentMoodlePendingGroup.course] = courseId
                    this[StudentMoodlePendingGroup.group] = groupNamesToIds.getValue(it.name)
                    this[StudentMoodlePendingGroup.pendingAccess] = accessId
                }
            }

            val syncedStudentsCount = newAccesses.size
            val syncedPendingStudentsCount = newPendingAccesses.size
            log.debug { "Synced $syncedStudentsCount students and $syncedPendingStudentsCount pending students" }
            MoodleSyncedStudents(newAccesses.size, newPendingAccesses.size)
        }
    }


    @Scheduled(cron = "\${easy.core.moodle-sync.users.cron}")
    fun syncMoodle() {
        log.debug { "Checking for courses for Moodle syncing..." }
        transaction {
            Course.select {
                Course.moodleShortName.isNotNull() and
                        (Course.moodleShortName neq "") and
                        Course.moodleSyncStudents
            }.map {
                MoodleCron(it[Course.id].value, it[Course.moodleShortName]!!)
            }.forEach {
                log.debug { "Cron Moodle syncing ${it.moodleShortName} with course ${it.courseId}." }
                syncCourse(queryStudents(it.moodleShortName), it.courseId, it.moodleShortName)
            }
        }
    }
}
