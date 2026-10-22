package core.ems.service.code

import core.conf.security.EasyUser
import core.db.CourseExerciseSimilaritySettings
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Drops one course exercise's own similarity settings (EZ-1667), so it follows its course's again. Dropping
 * settings it does not have is no error: the exercise then follows the course's, as asked.
 */
@RestController
@RequestMapping("/v2")
class TeacherDeleteCourseExerciseSimilaritySettingsController {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/similarity-settings/exercises/{courseExerciseId}")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExerciseIdStr: String,
        caller: EasyUser,
    ) {
        log.info { "Delete similarity settings of course exercise $courseExerciseIdStr on course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExerciseId = courseExerciseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExerciseId, courseId)

        transaction {
            CourseExerciseSimilaritySettings.deleteWhere { CourseExerciseSimilaritySettings.courseExercise eq courseExerciseId }
        }
    }
}
