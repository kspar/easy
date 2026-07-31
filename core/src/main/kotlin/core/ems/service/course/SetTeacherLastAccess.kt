package core.ems.service.course

import core.conf.security.EasyUser
import core.db.TeacherCourseAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.userOnCourse
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class SetTeacherLastAccess {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/access")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser) {
        log.info { "Updating last accessed for teacher ${caller.id} for course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { userOnCourse(courseId) }

        return updateLastAccessed(courseId, caller.id)
    }

    private fun updateLastAccessed(courseId: Long, teacherId: String): Unit = transaction {
        TeacherCourseAccess.update({ (TeacherCourseAccess.course.eq(courseId)) and (TeacherCourseAccess.teacher eq teacherId) }) {
            it[TeacherCourseAccess.lastAccessed] = DateTime.now()
        }
    }
}
