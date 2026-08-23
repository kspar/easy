package core.ems.service.exercise

import core.conf.security.EasyUser
import core.ems.service.ActivityResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectStudentAllExerciseActivities
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class TecherReadStudentAllExerciseActivities {
    private val log = KotlinLogging.logger {}


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/students/{studentId}/activities")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("studentId") studentId: String,
        caller: EasyUser
    ): ActivityResp {

        log.info { "Getting activities for ${caller.id} by $studentId on course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        // `teacherOnCourse` checks the caller against `courseId`, and this checks `courseId` against
        // the exercise the caller actually asked for. Without the second check the first one is
        // decorative: `selectStudentAllExerciseActivities` filters on `courseExId` and `studentId`
        // only, so a course the caller does teach would authorise a course exercise on a course they
        // do not. Both ids come from the path and nothing else ties them together.
        //
        // No `RequireStudentVisible`: a teacher may read activities on an exercise that is still
        // hidden from students. That is the one difference from the student-side twin of this
        // endpoint, `StudentReadAllExerciseTeacherActivities`, which is otherwise the same two lines.
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        return selectStudentAllExerciseActivities(courseExId, studentId)
    }

}