package core.ems.service.exercise

import core.conf.security.EasyUser
import core.ems.service.ExercisesResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectAllCourseExercisesLatestSubmissions
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class TeacherReadSubmissionSummariesController {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        caller: EasyUser
    ): ExercisesResp {

        log.info {
            "Getting submission summaries for ${caller.id} on course exercise $courseExerciseIdString on course $courseIdString"
        }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId, true)
        }

        return selectAllCourseExercisesLatestSubmissions(courseId)
            .filter { it.exerciseId.toLong() == courseExId }
            .singleOrInvalidRequest()
    }
}
