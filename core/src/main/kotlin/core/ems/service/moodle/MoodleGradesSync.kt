package core.ems.service.moodle

import core.conf.security.EasyUser
import core.ems.service.assertCourseExists
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class MoodleGradesSyncController(val moodleGradesSyncService: MoodleGradesSyncService) {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodle/grades")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        caller: EasyUser
    ) {

        log.debug { "Syncing all grades for course $courseIdStr with Moodle" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertCourseExists(courseId)
        assertCourseIsMoodleLinked(courseId)

        moodleGradesSyncService.syncCourseGradesToMoodle(courseId)
    }
}
