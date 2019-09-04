package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectLatestGradeForSubmission
import core.ems.service.selectLatestSubmissionsForExercise
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v2")
class TeacherReadCourseExercisesController {

    data class Resp(@JsonProperty("id") val id: String,
                    @JsonProperty("effective_title") val title: String,
                    @JsonSerialize(using = DateTimeSerializer::class)
                    @JsonProperty("soft_deadline") val softDeadline: DateTime?,
                    @JsonProperty("grader_type") val graderType: GraderType,
                    @JsonProperty("ordering_idx") val orderingIndex: Int,
                    @JsonProperty("unstarted_count") val unstartedCount: Int,
                    @JsonProperty("ungraded_count") val ungradedCount: Int,
                    @JsonProperty("started_count") val startedCount: Int,
                    @JsonProperty("completed_count") val completedCount: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   caller: EasyUser): List<Resp> {

        log.debug { "Getting exercises on course $courseIdString for teacher/admin ${caller.id}" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return selectTeacherExercisesOnCourse(courseId)
    }
}


private fun selectTeacherExercisesOnCourse(courseId: Long):
        List<TeacherReadCourseExercisesController.Resp> {

    return transaction {

        val studentCount = StudentCourseAccess.select {
            StudentCourseAccess.course eq courseId
        }.count()

        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.id,
                        CourseExercise.gradeThreshold,
                        CourseExercise.softDeadline,
                        CourseExercise.orderIdx,
                        ExerciseVer.graderType,
                        ExerciseVer.title,
                        ExerciseVer.validTo,
                        CourseExercise.titleAlias)
                .select { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
                .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
                .map { ex ->

                    val latestSubmissionIds = selectLatestSubmissionsForExercise(ex[CourseExercise.id].value)
                    val latestGrades = latestSubmissionIds.map { selectLatestGradeForSubmission(it) }

                    val gradeThreshold = ex[CourseExercise.gradeThreshold]

                    val unstartedCount = studentCount - latestSubmissionIds.size
                    val ungradedCount = latestGrades.count { it == null }
                    val startedCount = latestGrades.count { it != null && it < gradeThreshold }
                    val completedCount = latestGrades.count { it != null && it >= gradeThreshold }

                    // Sanity check
                    if (unstartedCount + ungradedCount + startedCount + completedCount != studentCount)
                        log.warn {
                            "Student grade sanity check failed. unstarted: $unstartedCount, ungraded: $ungradedCount, " +
                                    "started: $startedCount, completed: $completedCount, students in course: $studentCount"
                        }

                    TeacherReadCourseExercisesController.Resp(
                            ex[CourseExercise.id].value.toString(),
                            ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                            ex[CourseExercise.softDeadline],
                            ex[ExerciseVer.graderType],
                            ex[CourseExercise.orderIdx],
                            unstartedCount,
                            ungradedCount,
                            startedCount,
                            completedCount
                    )

                }
    }
}
