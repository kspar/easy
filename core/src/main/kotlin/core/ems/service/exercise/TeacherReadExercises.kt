package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.ExercisesResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectAllCourseExercisesLatestSubmissions
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class TeacherReadCourseExercisesController {
    private val log = KotlinLogging.logger {}


    data class Resp(@JsonProperty("exercises") val courseExercises: List<ExercisesResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdString: String, caller: EasyUser): Resp {

        log.info { "Getting exercises on course $courseIdString for teacher/admin ${caller.id}" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId, true) }
        return Resp(selectAllCourseExercisesLatestSubmissions(courseId))
    }

}
