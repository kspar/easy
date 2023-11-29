package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.conf.security.EasyUser
import core.db.CourseInviteLink
import core.db.insertOrUpdate
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import java.security.SecureRandom
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min


@RestController
@RequestMapping("/v2")
class GenerateCourseInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("invite_id") val inviteId: String)

    data class Req(
        @JsonDeserialize(using = DateTimeDeserializer::class) @JsonProperty("expires_at") val expiresAt: DateTime,
        @JsonProperty("allowed_uses") @field:Min(0) @field:Max(1000000) val allowedUses: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/invite")
    fun controller(
        @Valid @RequestBody req: Req, @PathVariable("courseId") courseIdStr: String, caller: EasyUser
    ): Resp {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.info { "Creating invite on course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId, false)
        }

        return Resp(createInvite(courseId, req))
    }

    private data class CourseInviteLinkDTO(val inviteId: String, val createdAt: DateTime, val usedCount: Int)

    private fun createInvite(courseId: Long, req: Req): String = transaction {
        val secureRandom = SecureRandom()
        val alphabet = ('A'..'Z')

        // If there is already an existing invite id, don't change it
        val d = CourseInviteLink
            .slice(CourseInviteLink.inviteId, CourseInviteLink.createdAt, CourseInviteLink.usedCount)
            .select { (CourseInviteLink.course eq courseId) }
            .map {
                CourseInviteLinkDTO(
                    it[CourseInviteLink.inviteId],
                    it[CourseInviteLink.createdAt],
                    it[CourseInviteLink.usedCount]
                )
            }
            .singleOrNull()
            ?: CourseInviteLinkDTO(
                ((1..6).map { alphabet.elementAt(secureRandom.nextInt(alphabet.count())) }.joinToString("")),
                DateTime.now(),
                0
            )

        CourseInviteLink.insertOrUpdate(listOf(CourseInviteLink.course), listOf(CourseInviteLink.course)) {
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
