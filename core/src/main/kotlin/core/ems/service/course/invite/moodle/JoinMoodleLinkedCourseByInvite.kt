package core.ems.service.course.invite.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.singleOrInvalidRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class JoinMoodleLinkedCourseByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("course_id") val courseId: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/courses/moodle/join/{inviteId}")
    fun controller(@PathVariable("inviteId") inviteId: String, caller: EasyUser): Resp {
        log.info { "Joining Moodle course by invite $inviteId by ${caller.id}" }

        return join(inviteId, caller.id)
    }

    private fun join(inviteId: String, studentId: String): Resp = transaction {
        val (courseId, moodleUsername) = (StudentMoodlePendingAccess innerJoin Course)
            .select(Course.id, StudentMoodlePendingAccess.moodleUsername)
            .where {
                StudentMoodlePendingAccess.inviteId eq inviteId
            }.map { it[Course.id] to it[StudentMoodlePendingAccess.moodleUsername] }
            .singleOrInvalidRequest(false)

        StudentCourseAccess.insertIgnore {
            it[course] = courseId
            it[student] = studentId
            it[createdAt] = DateTime.now()
            it[StudentCourseAccess.moodleUsername] = moodleUsername
        }

        StudentMoodlePendingCourseGroup
            .select(StudentMoodlePendingCourseGroup.courseGroup)
            .where { (StudentMoodlePendingCourseGroup.course eq courseId) and (StudentMoodlePendingCourseGroup.moodleUsername eq moodleUsername) }
            .map { it[StudentMoodlePendingCourseGroup.courseGroup] }
            .forEach { group ->
                StudentCourseGroup.insert {
                    it[StudentCourseGroup.student] = studentId
                    it[StudentCourseGroup.course] = courseId
                    it[StudentCourseGroup.courseGroup] = group
                }
            }

        StudentMoodlePendingAccess.deleteWhere { StudentMoodlePendingAccess.inviteId eq inviteId }

        log.debug { "$studentId joined Moodle linked course $courseId by invite $inviteId" }
        Resp(courseId.toString())
    }
}

