package core.ems.service.course.invite.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentMoodlePendingAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.getCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid


@RestController
@RequestMapping("/v2")
class SendMoodleCourseInvites(val mailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("students") val students: List<String>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/moodle/{courseId}/students/invite")
    fun controller(
        @Valid @RequestBody req: Req, @PathVariable("courseId") courseIdStr: String, caller: EasyUser
    ) {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.info { "Sending Moodle invites on course $courseId by ${caller.id} for $req" }

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        sendInvites(courseId, req.students)
    }


    private fun sendInvites(courseId: Long, moodleUsernames: List<String>) = transaction {
        val course = getCourse(courseId) ?: throw InvalidRequestException(
            "Course $courseId not found",
            ReqError.ENTITY_WITH_ID_NOT_FOUND
        )

        StudentMoodlePendingAccess
            .select(
                StudentMoodlePendingAccess.moodleUsername,
                StudentMoodlePendingAccess.email,
                StudentMoodlePendingAccess.inviteId
            )
            .where { StudentMoodlePendingAccess.moodleUsername inList moodleUsernames }
            .forEach {
                mailService.sendStudentInvitedToMoodleLinkedCourse(
                    course.alias ?: course.title,
                    it[StudentMoodlePendingAccess.inviteId],
                    it[StudentMoodlePendingAccess.email]
                )
            }
    }
}
