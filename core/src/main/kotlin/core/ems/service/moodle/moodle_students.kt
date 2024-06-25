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


data class MoodleSyncedStudents(val syncedStudents: Int, val syncedPendingStudents: Int)

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

    private fun insertStudentsFromMoodleResponse(moodleResponse: MoodleResponse, courseId: Long): MoodleSyncedStudents {

        data class MoodleGroup(val id: String, val name: String)
        data class NewAccess(
            val email: String, val username: String, val moodleUsername: String, val groups: List<MoodleGroup>
        )

        data class NewPendingAccess(val email: String, val moodleUsername: String, val groups: List<MoodleGroup>)

        val courseTitle = getCourse(courseId)!!.let { it.alias ?: it.title }
        val courseEntity = EntityID(courseId, Course)

        return transaction {
            // TODO: ask Moodle to send all groups separately
            // TODO: then delete groups that don't exist anymore
            // Insert groups or get their IDs
            val moodleGroups = moodleResponse.students
                .mapNotNull { it.groups }
                .flatten()
                .map { it.name }

            val groupNamesToIds = moodleGroups.map { moodleGroupName ->
                val groupId =
                    CourseGroup.selectAll()
                        .where { CourseGroup.course eq courseId and (CourseGroup.name eq moodleGroupName) }
                        .map { it[CourseGroup.id] }
                        .singleOrNull()
                        ?: CourseGroup.insertAndGetId {
                            it[name] = moodleGroupName
                            it[course] = courseEntity
                        }
                moodleGroupName to groupId
            }.toMap()


            // Partition users by whether they have an Easy account
            val newAccesses =
                moodleResponse.students.flatMap { moodleStudent ->
                    val moodleEmail = moodleStudent.email.lowercase()

                    (Account innerJoin StudentCourseAccess).select(Account.id, Account.email)
                        .where {
                            StudentCourseAccess.moodleUsername eq moodleStudent.username or
                                    (Account.email eq moodleEmail) and (StudentCourseAccess.course eq courseId)
                        }
                        .withDistinct()
                        .map {
                            NewAccess(
                                it[Account.email],
                                it[Account.id].value,
                                moodleStudent.username,
                                moodleStudent.groups?.map { MoodleGroup(it.id, it.name) } ?: emptyList()
                            )
                        }
                        .also {
                            if (it.size > 1) {
                                log.warn { "Several accounts found with Moodle username ${moodleStudent.username} or email $moodleEmail: $it" }
                                mailService.sendSystemNotification(
                                    "Several accounts with Moodle username ${moodleStudent.username} or email $moodleEmail found on course $courseId: $it"
                                )
                            }
                        }
                }
            log.debug { "Giving access to students: $newAccesses" }

            val newPendingAccesses = moodleResponse.students
                .filter { moodleStudent ->
                    newAccesses.none { it.moodleUsername == moodleStudent.username }
                }.map {
                    NewPendingAccess(
                        it.email.lowercase(),
                        it.username,
                        it.groups?.map { MoodleGroup(it.id, it.name) } ?: emptyList()
                    )
                }
            log.debug { "Giving pending access to students: $newPendingAccesses" }


            // Diff accesses before and after to send notifications for only new accesses
            val previousStudentEmails =
                (StudentCourseAccess innerJoin Account).selectAll().where { StudentCourseAccess.course.eq(courseId) }
                    .map {
                        it[Account.email]
                    }

            // Remove & insert all accesses because groups might have changed
            StudentCourseAccess.deleteWhere { StudentCourseAccess.course eq courseId }

            val time = DateTime.now()
            newAccesses.forEach { newAccess ->
                // TODO: maybe can batchInsert
                StudentCourseAccess.insert {
                    it[student] = newAccess.username
                    it[moodleUsername] = newAccess.moodleUsername
                    it[course] = courseEntity
                    it[createdAt] = time
                }
                StudentCourseGroup.batchInsert(newAccess.groups) {
                    this[StudentCourseGroup.student] = newAccess.username
                    this[StudentCourseGroup.course] = courseId
                    this[StudentCourseGroup.courseGroup] = groupNamesToIds.getValue(it.name)
                }
            }

            // Send notifications for only new accesses
            val newStudentEmails = newAccesses.map { it.email } - previousStudentEmails.toSet()
            log.debug { "New active emails: $newStudentEmails" }
            newStudentEmails.forEach {
                mailService.sendStudentAddedToCourseActive(courseTitle, it)
            }

            // Diff accesses before and after to send invitations for only new accesses
            val previousEmailsPending =
                StudentMoodlePendingAccess.selectAll().where { StudentMoodlePendingAccess.course.eq(courseId) }.map {
                    it[StudentMoodlePendingAccess.email]
                }

            // Remove & insert all pending group accesses
            StudentMoodlePendingCourseGroup.deleteWhere { StudentMoodlePendingCourseGroup.course eq courseId }

            newPendingAccesses.forEach { newPendingAccess ->
                // TODO: maybe can batchInsert
                // InsertIgnore to generate new link only to new ones.
                StudentMoodlePendingAccess.insertIgnore {
                    it[moodleUsername] = newPendingAccess.moodleUsername
                    it[course] = courseEntity
                    it[email] = newPendingAccess.email
                    it[createdAt] = time
                    it[inviteId] = generateInviteId(10)
                }
                StudentMoodlePendingCourseGroup.batchInsert(newPendingAccess.groups) {
                    this[StudentMoodlePendingCourseGroup.moodleUsername] = newPendingAccess.moodleUsername
                    this[StudentMoodlePendingCourseGroup.course] = courseId
                    this[StudentMoodlePendingCourseGroup.courseGroup] = groupNamesToIds.getValue(it.name)
                }
            }

            // Send invitation for only new accesses
            val newEmailsPending = newPendingAccesses.map { it.email } - previousEmailsPending.toSet()
            log.debug { "New pending emails: $newEmailsPending" }
            newEmailsPending.forEach {
                mailService.sendStudentAddedToCoursePending(courseTitle, it)
            }

            val syncedStudentsCount = newAccesses.size
            val syncedPendingStudentsCount = newPendingAccesses.size
            log.info { "Synced $syncedStudentsCount students and $syncedPendingStudentsCount pending students" }
            MoodleSyncedStudents(newAccesses.size, newPendingAccesses.size)
        }
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
