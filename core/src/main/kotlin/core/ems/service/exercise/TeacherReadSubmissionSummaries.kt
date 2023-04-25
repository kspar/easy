package core.ems.service.exercise

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
class TeacherReadSubmissionSummariesController {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        caller: EasyUser
    ): ExercisesResp {

        log.debug {
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
