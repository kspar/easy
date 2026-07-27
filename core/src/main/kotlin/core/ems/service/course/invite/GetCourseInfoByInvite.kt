package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseInviteLink
import core.exception.InvalidRequestException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class GetCourseInfoByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("course_id") val courseId: String,
        @get:JsonProperty("course_title") val courseTitle: String,
    )

    @Secured("ROLE_STUDENT")
    @GetMapping("/courses/invite/{invite-id}")
    fun controller(@PathVariable("invite-id") inviteId: String, caller: EasyUser): Resp {
        log.info { "Finding course title by invite $inviteId by ${caller.id}" }

        return selectCourseInfoByInvite(inviteId)
    }

    private fun selectCourseInfoByInvite(inviteId: String): Resp = transaction {
        val row = (CourseInviteLink innerJoin Course)
            .select(Course.id, Course.title, Course.alias, Course.moodleShortName)
            .where {
                (CourseInviteLink.inviteId.upperCase() eq inviteId.uppercase()) and
                        CourseInviteLink.expiresAt.greater(DateTime.now()) and
                        CourseInviteLink.usedCount.less(CourseInviteLink.allowedUses)
            }.singleOrNull() ?: throw InvalidRequestException("Invalid invite link")

        if (row[Course.moodleShortName] != null) {
            throw InvalidRequestException("Invite links are not available for Moodle-synced courses")
        }

        Resp(
            row[Course.id].value.toString(),
            row[Course.alias] ?: row[Course.title]
        )
    }
}

