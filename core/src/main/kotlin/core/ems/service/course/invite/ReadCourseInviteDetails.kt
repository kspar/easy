package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.CourseInviteLink
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        @get:JsonProperty("invite_id") val inviteId: String,
        @get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("expires_at") val expiry: DateTime,
        @get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("allowed_uses") val allowedUses: Int,
        @get:JsonProperty("used_count") val usedCount: Int,
        @get:JsonProperty("uses_remaining") val usesRemaining: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/invite")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp? {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.info { "Reading course invite on course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        return selectCourseInviteDetails(courseId)
    }

    private fun selectCourseInviteDetails(courseId: Long): Resp? = transaction {
        CourseInviteLink
            .select(
                CourseInviteLink.inviteId,
                CourseInviteLink.expiresAt,
                CourseInviteLink.createdAt,
                CourseInviteLink.allowedUses,
                CourseInviteLink.usedCount
            )
            .where { CourseInviteLink.course eq courseId }
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

