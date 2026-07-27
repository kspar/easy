package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseInviteLink
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.generateInviteId
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeDeserializer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.annotation.JsonDeserialize


@RestController
@RequestMapping("/v2")
class GenerateCourseInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("invite_id") val inviteId: String)

    data class Req(
        @param:JsonDeserialize(using = DateTimeDeserializer::class) @param:JsonProperty("expires_at") val expiresAt: DateTime,
        @param:JsonProperty("allowed_uses") @field:Min(0) @field:Max(1000000) val allowedUses: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/invite")
    fun controller(
        @Valid @RequestBody req: Req, @PathVariable("courseId") courseIdStr: String, caller: EasyUser
    ): Resp {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.info { "Creating invite on course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        assertCourseNotMoodleLinked(courseId)

        return Resp(createInvite(courseId, req))
    }

    private fun assertCourseNotMoodleLinked(courseId: Long) = transaction {
        val moodleShortName = Course
            .select(Course.moodleShortName)
            .where { Course.id eq courseId }
            .singleOrNull()
            ?.get(Course.moodleShortName)

        if (moodleShortName != null) {
            throw InvalidRequestException("Invite links are not available for Moodle-synced courses")
        }
    }

    private data class CourseInviteLinkDTO(val inviteId: String, val createdAt: DateTime, val usedCount: Int)


    private fun createInvite(courseId: Long, req: Req): String = transaction {
        // If there is already an existing invite id, don't change it
        val d = CourseInviteLink
            .select(CourseInviteLink.inviteId, CourseInviteLink.createdAt, CourseInviteLink.usedCount)
            .where { (CourseInviteLink.course eq courseId) }
            .map {
                CourseInviteLinkDTO(
                    it[CourseInviteLink.inviteId],
                    it[CourseInviteLink.createdAt],
                    it[CourseInviteLink.usedCount]
                )
            }
            .singleOrNull()
            ?: CourseInviteLinkDTO(
                generateInviteId(6),
                DateTime.now(),
                0
            )

        CourseInviteLink.upsert(
            CourseInviteLink.course,
            onUpdateExclude = listOf(CourseInviteLink.course)
        ) {
            it[course] = courseId
            it[createdAt] = d.createdAt
            it[expiresAt] = req.expiresAt
            it[allowedUses] = req.allowedUses
            it[inviteId] = d.inviteId
            it[usedCount] = d.usedCount
        }

        log.debug { "Invite '${d.inviteId}' created for course $courseId with expiry date of ${req.expiresAt} and ${req.allowedUses} uses." }

        d.inviteId
    }
}
