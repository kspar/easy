package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseInviteLink
import core.db.StudentCourseAccess
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
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
        @JsonProperty("course_id") val courseId: String
    )

    @Secured("ROLE_STUDENT")
    @PostMapping("/courses/join/{invite-id}")
    fun controller(@PathVariable("invite-id") inviteId: String, caller: EasyUser): Resp {
        log.info { "Joining course by invite $inviteId by ${caller.id}" }

        return joinByInvite(inviteId, caller.id)
    }

    private fun joinByInvite(inviteId: String, studentId: String): Resp = transaction {
        val courseId = (CourseInviteLink innerJoin Course)
            .select(Course.id)
            .where {
                (CourseInviteLink.inviteId.upperCase() eq inviteId.uppercase()) and
                        CourseInviteLink.expiresAt.greater(DateTime.now()) and
                        CourseInviteLink.usedCount.less(CourseInviteLink.allowedUses)
            }.map { it[Course.id] }
            .singleOrInvalidRequest(false)


        val accessesAdded = StudentCourseAccess.insertIgnore {
            it[course] = courseId
            it[student] = studentId
            it[createdAt] = DateTime.now()
        }.insertedCount


        if (accessesAdded > 0) {
            CourseInviteLink.update({ CourseInviteLink.course eq courseId }) {
                with(SqlExpressionBuilder) {
                    it.update(usedCount, usedCount + 1)
                }
            }
        }

        log.debug { "$studentId joined course $courseId by invite $inviteId" }
        Resp(courseId.toString())
    }
}

