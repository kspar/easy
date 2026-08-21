package core.ems.service.course.invite.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentMoodlePendingAccess
import core.ems.service.singleOrInvalidRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.upperCase
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        @get:JsonProperty("course_id") val courseId: String,
        @get:JsonProperty("course_title") val courseTitle: String,
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
                (StudentMoodlePendingAccess.inviteId.upperCase() eq inviteId.uppercase()) and
                        // Same predicate as the join next door, and it has to be here too: without
                        // it the invite page names the course and offers a join button that the join
                        // endpoint then refuses. The invite is either usable or it is not, and the
                        // page that presents it should agree with the endpoint behind it (EZ-1780).
                        Course.moodleShortName.isNotNull()
            }.map {
                Resp(
                    it[Course.id].value.toString(),
                    it[Course.alias] ?: it[Course.title]
                )
            }
            .singleOrInvalidRequest(false)
    }
}