package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseInviteLink
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class FindCourseByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("course_id") val courseId: String)

    @Secured("ROLE_STUDENT")
    @GetMapping("/courses/invite/{invite-id}")
    fun controller(@PathVariable("invite-id") inviteId: String, caller: EasyUser): Resp {
        log.debug { "Finding course by invite $inviteId by ${caller.id}" }

        return readCourseByInvite(inviteId)
    }

    private fun readCourseByInvite(inviteId: String): Resp = transaction {
        val resp = (CourseInviteLink innerJoin Course)
            .slice(Course.id)
            .select {
                (CourseInviteLink.inviteId eq inviteId) and
                        CourseInviteLink.expiresAt.greater(DateTime.now()) and
                        CourseInviteLink.usedCount.less(CourseInviteLink.allowedUses)

            }.map { Resp(it[Course.id].toString()) }
            .singleOrInvalidRequest(false)

        CourseInviteLink.update({ CourseInviteLink.course eq resp.courseId.toLong() }) {
            with(SqlExpressionBuilder) {
                it.update(usedCount, usedCount + 1)
            }
        }

        resp
    }
}

