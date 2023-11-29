package core.ems.service.course.invite

import core.conf.security.EasyUser
import core.db.CourseInviteLink
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class DeleteCourseInvite {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/invite")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser) {
        val courseId = courseIdStr.idToLongOrInvalidReq()

        log.info { "Deleting course invite on course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId, false)
        }

        deleteInvite(courseId)
    }

    private fun deleteInvite(courseId: Long) = transaction {
        val deletedCount = CourseInviteLink.deleteWhere { CourseInviteLink.course eq courseId }
        log.debug { "Deleted course invite for course $courseId: ${deletedCount > 0}" }
    }
}

