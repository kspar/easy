package core.ems.service.course

import core.conf.security.EasyUser
import core.ems.service.ExercisesResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectAllCourseExercisesLatestSubmissions
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v2")
class TeacherReadGradesController {
    private val log = KotlinLogging.logger {}


    // TODO: this is identical to TeacherReadExercises.kt and (probably) not used anymore - remove at some point
    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/teacher/{courseId}/grades")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @RequestParam("group", required = false) groupIdString: String?,
        caller: EasyUser
    ): List<ExercisesResp> {
        log.info { "Getting grades for ${caller.id} on course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdString?.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        return selectAllCourseExercisesLatestSubmissions(courseId, groupId = groupId)
    }
}

