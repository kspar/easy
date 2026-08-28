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

        deleteCourseExerciseExceptions(
            courseExId,
            req.exceptionStudents ?: emptyList(),
            req.exceptionGroups ?: emptyList()
        )
    }

    /**
     * Deletes the named students' and the named groups' exceptions on this course exercise, and
     * nothing else — an empty list means "none of these", never "all of them".
     *
     * **The group branch used to delete from the student table.** It read
     * `CourseExerciseExceptionStudent.deleteWhere { CourseExerciseExceptionGroup.courseExercise … }`:
     * the receiver naming one table and every column in the predicate naming the other. The SQL that
     * came out was a `DELETE FROM course_exercise_exception_student` whose `WHERE` referred to a table
     * not in the statement, so Postgres answered `missing FROM-clause entry`, the transaction rolled
     * back, and removing a group's exception was a 500 every time. There was no test, and the failure
     * is invisible from the other side of the API — the row is still there afterwards either way.
     *
     * It was a copy from [PutCourseExerciseExceptions], which this method was also named after until
     * now: it was called `insertOrUpdateCourseExerciseExceptions` in a controller that only deletes.
     * The two branches were meant to be the same shape and one of them was half-edited, which is the
     * asymmetry `doc/review-plan.md` treats as a defect detector rather than a style question.
     */
    private fun deleteCourseExerciseExceptions(
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
                CourseExerciseExceptionGroup.deleteWhere {
                    CourseExerciseExceptionGroup.courseExercise eq courseExId and (CourseExerciseExceptionGroup.courseGroup inList exceptionGroups)
                }
            }
        }
    }
}


