package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.ems.service.generateInviteId
import core.ems.service.getCourse
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.exception.ResourceLockedException
import core.util.DBBackedLock
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
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


data class MoodleSyncedStudents(val syncedPendingStudents: Int)

@Service
class MoodleStudentsSyncService(val mailService: SendMailService) {

    @Value("\${easy.core.moodle-sync.users.url}")
    private lateinit var moodleSyncUrl: String

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
        @JsonProperty("username") val username: String,
        @JsonProperty("firstname") val firstname: String,
        @JsonProperty("lastname") val lastname: String,
        @JsonProperty("email") val email: String,
        @JsonProperty("groups", required = false) val groups: List<MoodleRespGroup>?
    )

    private data class MoodleRespGroup(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
    )

    private data class MoodleResponse(
        @JsonProperty("students") val students: List<MoodleRespStudent>
    )

    private fun queryStudents(moodleShortName: String): MoodleResponse {
        log.info { "Connecting Moodle ($moodleSyncUrl) for course linking..." }

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

        val map = LinkedMultiValueMap<String, String>()
        map.add("shortname", moodleShortName)

        val request = HttpEntity<MultiValueMap<String, String>>(map, headers)
        val responseEntity = RestTemplate().postForEntity(moodleSyncUrl, request, MoodleResponse::class.java)

        if (responseEntity.statusCode.value() != 200) {
            log.error { "Moodle linking error ${responseEntity.statusCodeValue} with request $request" }
            throw InvalidRequestException(
                "Course linking with Moodle failed due to error code in response.",
                ReqError.MOODLE_LINKING_ERROR,
                "Moodle response" to responseEntity.statusCodeValue.toString(),
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

            // TODO: ask Moodle to send all groups separately
            // TODO: then delete groups that don't exist anymore
            // Insert groups or get their IDs
            val moodleGroups = moodleResponse.students
                .mapNotNull { it.groups }
                .flatten()
                .map { it.name }

            // Make sure all groups from Moodle are here as well or add them if needed
            val groupNamesToIds = moodleGroups.associateWith { moodleGroupName ->
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


            // Combine students from Moodle with easy username (if they have one).
            val allStudents = moodleResponse.students.map { student ->
                ActiveOrPendingStudent(
                    student.email.lowercase(),
                    // generate inviteId for all students, but do not use this
                    // for existing active accesses (not needed) and existing pending accesses (keep the old one)
                    generateInviteId(10),
                    student.username,
                    student.groups?.map { MoodleGroup(it.id, it.name) } ?: emptyList(),
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

    @Scheduled(cron = "\${easy.core.moodle-sync.users.cron}")
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
                } catch (e: ResourceLockedException) {
                    log.warn { "Cannot Moodle sync students on course $courseId because it's locked" }
                }
            }
        }
    }
}
