package core.ems.service.course

import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v2")
class TeacherReadGradesController {
    private val log = KotlinLogging.logger {}


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/teacher/{courseId}/grades")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): List<ExercisesResp> {
        log.info { "Getting grades for ${caller.id} on course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId, true) }
        return selectAllCourseExercisesLatestSubmissions(courseId)
    }
}

