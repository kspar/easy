package core.ems.service.moodle

import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v2")
class MoodleGradesSyncController(val gradeService: GradeService) {


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodlesync/grades")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser) {

        log.debug { "Syncing grades for course $courseIdStr with Moodle" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        if (!isCoursePresent(courseId)) {
            throw InvalidRequestException("Course $courseId does not exist")
        }

        if (!isMoodleLinked(courseId)) {
            throw InvalidRequestException("Course $courseId is not linked with Moodle")
        }

        gradeService.syncCourseGradesToMoodle(courseId)
    }
}


private fun isMoodleLinked(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId and Course.moodleShortName.isNotNull()
        }.count() > 0
    }
}


private fun isCoursePresent(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId
        }.count() > 0
    }
}