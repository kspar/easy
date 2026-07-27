package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.ExercisesResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectAllCourseExercisesLatestSubmissions
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class TeacherReadCourseExercisesController {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("exercises") val courseExercises: List<ExercisesResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @RequestParam("group", required = false) groupIdString: String?,
        caller: EasyUser
    ): Resp {

        log.info { "Getting exercises on course $courseIdString for teacher/admin ${caller.id} (courseId: $courseIdString, groupId: $groupIdString)" }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val groupId = groupIdString?.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        return Resp(selectAllCourseExercisesLatestSubmissions(courseId, groupId = groupId))
    }

}
