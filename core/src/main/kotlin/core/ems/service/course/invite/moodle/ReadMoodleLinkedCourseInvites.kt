package core.ems.service.course.invite.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.StudentMoodlePendingAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadMoodleLinkedCourseInvites {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("invites") val invites: List<InviteResp>)

    data class InviteResp(
        @JsonProperty("invite_id") val inviteId: String,
        @JsonProperty("moodle_username") val moodleUsername: String,
        @JsonProperty("email") val email: String,
        @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("created_at") val createdAt: DateTime
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/moodle/{courseId}/invites")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp? {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.info { "Reading Moodle invites for course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        return selectCourseInviteDetails(courseId)
    }

    private fun selectCourseInviteDetails(courseId: Long): Resp = transaction {
        Resp(StudentMoodlePendingAccess
            .select(
                StudentMoodlePendingAccess.inviteId,
                StudentMoodlePendingAccess.moodleUsername,
                StudentMoodlePendingAccess.email,
                StudentMoodlePendingAccess.createdAt
            )
            .where { StudentMoodlePendingAccess.course eq courseId }
            .map {
                InviteResp(
                    it[StudentMoodlePendingAccess.inviteId],
                    it[StudentMoodlePendingAccess.moodleUsername],
                    it[StudentMoodlePendingAccess.email],
                    it[StudentMoodlePendingAccess.createdAt]
                )
            })
    }
}

