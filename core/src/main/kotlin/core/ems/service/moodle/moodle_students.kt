package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.ems.service.generateMoodleInviteId
import core.ems.service.getCourse
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.exception.ResourceLockedException
import core.util.DBBackedLock
import core.util.SendMailService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException


data class MoodleSyncedStudents(val syncedPendingStudents: Int)

@Service
class MoodleStudentsSyncService(val mailService: SendMailService, restTemplateBuilder: RestTemplateBuilder) {
    private val log = KotlinLogging.logger {}
    private val restTemplate = restTemplateBuilder.build()

    @Value($$"${easy.core.moodle-sync.users.url}")
    private lateinit var moodleSyncUrl: String

    @Value($$"${easy.core.moodle-sync.moodlewsrestformat}")
    private lateinit var moodlewsrestformat: String

    @Value($$"${easy.core.moodle-sync.wstoken}")
    private lateinit var wstoken: String

    @Value($$"${easy.core.moodle-sync.users.wsfunction}")
    private lateinit var wsfunction: String

    val syncStudentsLock = DBBackedLock(Course, Course.moodleSyncStudentsInProgress)


    /**
     * Pull students for this course from Moodle and replace all students on this course with those.
     * Respects students sync locking.
     *
     * @throws ResourceLockedException if students sync is already in progress
     */
    fun syncStudents(courseId: Long) {
        syncStudentsLock.with(courseId) {
            val shortname = selectCourseShortName(courseId)
            if (shortname.isNullOrBlank()) {
                log.warn { "Course $courseId students not synced with Moodle, shortname: $shortname" }
            } else {
                insertStudentsFromMoodleResponse(queryStudents(shortname), courseId)
            }
        }
    }


    private data class MoodleRespStudent(
        @get:JsonProperty("username") val username: String,
        @get:JsonProperty("firstname") val firstname: String,
        @get:JsonProperty("lastname") val lastname: String,
        @get:JsonProperty("email") val email: String,
        @get:JsonProperty("groups", required = false) val groups: List<String>?
    )

    private data class MoodleRespGroup(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String
    )

    private data class MoodleResponse(
        @get:JsonProperty("students") val students: List<MoodleRespStudent>,
        @get:JsonProperty("groups", required = false) val groups: List<MoodleRespGroup>? = null
    )

    private fun queryStudents(moodleShortName: String): MoodleResponse {
        log.info { "Connecting Moodle ($moodleSyncUrl) for course linking..." }

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

        val map = LinkedMultiValueMap<String, String>()
        map.add("shortname", moodleShortName)
        map.add("wstoken", wstoken)
        map.add("wsfunction", wsfunction)
        map.add("moodlewsrestformat", moodlewsrestformat)

        val request = HttpEntity<MultiValueMap<String, String>>(map, headers)


        val responseEntity = try {
            restTemplate.postForEntity(moodleSyncUrl, request, MoodleResponse::class.java)
        } catch (e: ResourceAccessException) {
            log.error { "Moodle linking error due to the I/O (connection) error on POST request. Request: $request. Error: $e" }
            throw InvalidRequestException(
                "Course linking with Moodle failed due to internal connection issue.",
                ReqError.MOODLE_LINKING_ERROR,
                notify = true
            )
        } catch (e: RestClientException) {
            val debug = try {
                restTemplate.postForEntity(moodleSyncUrl, request, String::class.java)
            } catch (_: Exception) {
                null
            }
            log.error { "Moodle linking error on POST request. Request: $request. Error: $e. ${debug ?: ""}" }

            throw InvalidRequestException(
                "Course linking with Moodle failed due to unexpected response from Moodle.",
                ReqError.MOODLE_LINKING_ERROR,
                notify = true
            )
        }

        if (responseEntity.statusCode.value() != 200) {
            log.error { "Moodle linking error ${responseEntity.statusCode.value()} with request $request" }
            throw InvalidRequestException(
                "Course linking with Moodle failed due to error code in response.",
                ReqError.MOODLE_LINKING_ERROR,
                "Moodle response" to responseEntity.statusCode.value().toString(),
                notify = true
            )
        }

        val response = responseEntity.body
        if (response == null) {
            log.error { "Moodle returned empty response with request $request" }
            return MoodleResponse(emptyList())
        }
        return response
    }

    private fun insertStudentsFromMoodleResponse(moodleResponse: MoodleResponse, courseId: Long): MoodleSyncedStudents =
        transaction {

            data class MoodleGroup(val id: String, val name: String)

            data class ActiveOrPendingStudent(
                val email: String,
                val inviteId: String,
                val moodleUsername: String,
                val groups: List<MoodleGroup>,
                val existingStudentId: EntityID<String>?
            )

            val courseTitle = getCourse(courseId)!!.let { it.alias ?: it.title }
            val time = DateTime.now()

            // Insert groups or get their IDs
            val moodleGroups = moodleResponse.groups.orEmpty()

            // Make sure all groups from Moodle are here as well or add them if needed
            val groupNamesToIds = moodleGroups.map { it.name }.associateWith { moodleGroupName ->
                val groupId =
                    CourseGroup.selectAll()
                        .where { CourseGroup.course eq courseId and (CourseGroup.name eq moodleGroupName) }
                        .map { it[CourseGroup.id] }
                        .singleOrNull()
                        ?: CourseGroup.insertAndGetId {
                            it[name] = moodleGroupName
                            it[course] = courseId
                        }
                groupId
            }

            // All existing active accesses on the course
            val existingAccesses = StudentCourseAccess
                .select(StudentCourseAccess.moodleUsername, StudentCourseAccess.student)
                .where(StudentCourseAccess.course eq courseId)
                .associate { it[StudentCourseAccess.moodleUsername] to it[StudentCourseAccess.student] }


            val groupIdToName = moodleGroups.associate { it.id to it.name }

            // Combine students from Moodle with easy username (if they have one).
            val allStudents = moodleResponse.students.map { student ->
                ActiveOrPendingStudent(
                    student.email.lowercase(),
                    // generate inviteId for all students, but do not use this
                    // for existing active accesses (not needed) and existing pending accesses (keep the old one)
                    generateMoodleInviteId(),
                    student.username,
                    student.groups.orEmpty().mapNotNull { groupId ->
                        val groupName = groupIdToName[groupId]
                        if (groupName == null) {
                            log.warn { "Student ${student.username} references Moodle group $groupId that is missing from the groups list on course $courseId" }
                            null
                        } else {
                            MoodleGroup(groupId, groupName)
                        }
                    },
                    existingAccesses[student.username]
                )
            }

            // Delete all active accesses to update groups
            StudentCourseAccess.deleteWhere { StudentCourseAccess.course eq courseId }

            // Delete only pending accesses that are missing from this Moodle response - don't update existing inviteIds
            StudentMoodlePendingAccess.deleteWhere {
                (StudentMoodlePendingAccess.course eq courseId) and
                        (StudentMoodlePendingAccess.email.notInList(allStudents.map { it.email }))
            }

            // Diff accesses before and after to send invitations for only new accesses later
            val existingPendingEmails =
                StudentMoodlePendingAccess.select(StudentMoodlePendingAccess.email)
                    .where { StudentMoodlePendingAccess.course.eq(courseId) }
                    .map { it[StudentMoodlePendingAccess.email] }
                    .toSet()

            // Remove all pending group accesses to update them
            StudentMoodlePendingCourseGroup.deleteWhere { StudentMoodlePendingCourseGroup.course eq courseId }

            // Add new pending accesses - insertIgnore to prevent old ones from being update with new inviteIds
            allStudents.filter { it.existingStudentId == null }.forEach { pendingStudent ->
                StudentMoodlePendingAccess.insertIgnore {
                    it[moodleUsername] = pendingStudent.moodleUsername
                    it[course] = courseId
                    it[email] = pendingStudent.email
                    it[createdAt] = time
                    it[inviteId] = pendingStudent.inviteId
                }
                StudentMoodlePendingCourseGroup.batchInsert(pendingStudent.groups) {
                    this[StudentMoodlePendingCourseGroup.moodleUsername] = pendingStudent.moodleUsername
                    this[StudentMoodlePendingCourseGroup.course] = courseId
                    this[StudentMoodlePendingCourseGroup.courseGroup] = groupNamesToIds.getValue(it.name)
                }
            }

            // Readd existing active accesses
            allStudents.filter { it.existingStudentId != null }.forEach { activeStudent ->
                StudentCourseAccess.insertIgnore {
                    it[student] = activeStudent.existingStudentId!!
                    it[moodleUsername] = activeStudent.moodleUsername
                    it[course] = courseId
                    it[createdAt] = time
                }
                StudentCourseGroup.batchInsert(activeStudent.groups) {
                    this[StudentCourseGroup.student] = activeStudent.existingStudentId!!
                    this[StudentCourseGroup.course] = courseId
                    this[StudentCourseGroup.courseGroup] = groupNamesToIds.getValue(it.name)
                }
            }

            // Delete groups that don't exist in Moodle anymore - group management is Moodle's on a synced course;
            // memberships were already rebuilt above and exception rows cascade
            val deletedGroupsCount = CourseGroup.deleteWhere {
                (CourseGroup.course eq courseId) and CourseGroup.name.notInList(moodleGroups.map { it.name })
            }
            if (deletedGroupsCount > 0) {
                log.debug { "Deleted $deletedGroupsCount groups on course $courseId that no longer exist in Moodle" }
            }

            // Send invitations for only new pending accesses
            val invitationEmailRecipients = allStudents
                // only pending students
                .filter { it.existingStudentId == null }
                // only if the email is new
                .filter { !existingPendingEmails.contains(it.email) }

            invitationEmailRecipients.forEach {
                mailService.sendStudentInvitedToMoodleLinkedCourse(
                    courseTitle,
                    it.inviteId,
                    it.email
                )
            }

            log.debug { "All synced students (${allStudents.size}): $allStudents" }
            log.debug { "New invitations (${invitationEmailRecipients.size}): $invitationEmailRecipients" }

            MoodleSyncedStudents(allStudents.size)
        }

    @Scheduled(cron = $$"${easy.core.moodle-sync.users.cron}")
    fun moodleSyncAllCoursesStudents() {
        log.info { "Cron checking for courses for Moodle student syncing" }

        transaction {
            Course.selectAll().where {
                Course.moodleShortName.isNotNull() and
                        Course.moodleShortName.neq("") and
                        Course.moodleSyncStudents
            }.forEach {
                val courseId = it[Course.id].value
                log.info { "Cron Moodle syncing students on course $courseId" }

                try {
                    syncStudents(courseId)
                } catch (_: ResourceLockedException) {
                    log.warn { "Cannot Moodle sync students on course $courseId because it's locked" }
                }
            }
        }
    }
}
