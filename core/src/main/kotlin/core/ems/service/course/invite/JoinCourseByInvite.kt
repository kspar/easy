package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseInviteLink
import core.db.StudentCourseAccess
import core.exception.InvalidRequestException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class JoinCourseByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("course_id") val courseId: String
    )

    @Secured("ROLE_STUDENT")
    @PostMapping("/courses/join/{invite-id}")
    fun controller(@PathVariable("invite-id") inviteId: String, caller: EasyUser): Resp {
        log.info { "Joining course by invite $inviteId by ${caller.id}" }

        return joinByInvite(inviteId, caller.id)
    }

    private fun joinByInvite(inviteId: String, studentId: String): Resp = transaction {
        val row = (CourseInviteLink innerJoin Course)
            .select(Course.id, Course.moodleShortName)
            .where {
                (CourseInviteLink.inviteId.upperCase() eq inviteId.uppercase()) and
                        CourseInviteLink.expiresAt.greater(DateTime.now()) and
                        CourseInviteLink.usedCount.less(CourseInviteLink.allowedUses)
            }.singleOrNull() ?: throw InvalidRequestException("Invalid invite link")

        if (row[Course.moodleShortName] != null) {
            throw InvalidRequestException("Invite links are not available for Moodle-synced courses")
        }

        val courseId = row[Course.id]


        val accessesAdded = StudentCourseAccess.insertIgnore {
            it[course] = courseId
            it[student] = studentId
            it[createdAt] = DateTime.now()
        }.insertedCount


        if (accessesAdded > 0) {
            CourseInviteLink.update({ CourseInviteLink.course eq courseId }) {
                it.update(usedCount, usedCount + 1)
            }
        }

        log.debug { "$studentId joined course $courseId by invite $inviteId" }
        Resp(courseId.toString())
    }
}

