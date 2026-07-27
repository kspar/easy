package core.ems.service.exercise.exceptions

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExerciseExceptionGroup
import core.db.CourseExerciseExceptionStudent
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import jakarta.validation.Valid
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class RemoveCourseExerciseExceptions {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("exception_students") @field:Valid val exceptionStudents: List<String>?,
        @param:JsonProperty("exception_groups") @field:Valid val exceptionGroups: List<Long>?
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/exercises/{courseExerciseId}/exception")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Delete course exercise $courseExIdStr exceptions on course $courseIdStr by ${caller.id}: $req" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        insertOrUpdateCourseExerciseExceptions(
            courseExId,
            req.exceptionStudents ?: emptyList(),
            req.exceptionGroups ?: emptyList()
        )
    }

    private fun insertOrUpdateCourseExerciseExceptions(
        courseExId: Long,
        exceptionStudents: List<String>,
        exceptionGroups: List<Long>
    ) {
        transaction {
            if (exceptionStudents.isNotEmpty()) {
                CourseExerciseExceptionStudent.deleteWhere {
                    CourseExerciseExceptionStudent.courseExercise eq courseExId and (CourseExerciseExceptionStudent.student inList exceptionStudents)
                }
            }

            if (exceptionGroups.isNotEmpty()) {
                CourseExerciseExceptionStudent.deleteWhere {
                    CourseExerciseExceptionGroup.courseExercise eq courseExId and (CourseExerciseExceptionGroup.courseGroup inList exceptionGroups)
                }
            }
        }
    }
}


