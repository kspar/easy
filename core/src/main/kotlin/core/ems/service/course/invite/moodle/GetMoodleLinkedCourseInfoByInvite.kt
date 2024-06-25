package core.ems.service.course.invite.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentMoodlePendingAccess
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class GetMoodleLinkedCourseInfoByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("course_id") val courseId: String,
        @JsonProperty("course_title") val courseTitle: String,
    )

    @Secured("ROLE_STUDENT")
    @GetMapping("/courses/moodle/invite/{invite-id}")
    fun controller(@PathVariable("invite-id") inviteId: String, caller: EasyUser): Resp {
        log.info { "Finding Moodle linked course by invite '$inviteId' by ${caller.id}" }

        return selectCourseInfoByInvite(inviteId)
    }

    private fun selectCourseInfoByInvite(inviteId: String): Resp = transaction {
        (StudentMoodlePendingAccess innerJoin Course)
            .select(Course.id, Course.title, Course.alias)
            .where {
                (StudentMoodlePendingAccess.inviteId eq inviteId)
            }.map {
                Resp(
                    it[Course.id].value.toString(),
                    it[Course.alias] ?: it[Course.title].toString()
                )
            }
            .singleOrInvalidRequest(false)
    }
}