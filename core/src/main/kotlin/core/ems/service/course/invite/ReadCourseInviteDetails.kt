package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.CourseInviteLink
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.math.max


@RestController
@RequestMapping("/v2")
class ReadCourseInviteDetails {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("invite_id") val inviteId: String,
        @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("expires_at") val expiry: DateTime,
        @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("created_at") val createdAt: DateTime,
        @JsonProperty("allowed_uses") val allowedUses: Int,
        @JsonProperty("used_count") val usedCount: Int,
        @JsonProperty("uses_remaining") val usesRemaining: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/invite")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp? {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.debug { "Reading course invite on course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId, false)
        }

        return selectCourseInviteDetails(courseId)
    }

    private fun selectCourseInviteDetails(courseId: Long): Resp? = transaction {
        CourseInviteLink
            .slice(
                CourseInviteLink.inviteId,
                CourseInviteLink.expiresAt,
                CourseInviteLink.createdAt,
                CourseInviteLink.allowedUses,
                CourseInviteLink.usedCount
            )
            .select { CourseInviteLink.course eq courseId }
            .map {
                Resp(
                    it[CourseInviteLink.inviteId],
                    it[CourseInviteLink.expiresAt],
                    it[CourseInviteLink.createdAt],
                    it[CourseInviteLink.allowedUses],
                    it[CourseInviteLink.usedCount],
                    max(it[CourseInviteLink.allowedUses] - it[CourseInviteLink.usedCount], 0)
                )
            }.singleOrNull()
    }
}

