package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.CourseService
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.getTeacherRestrictedCourseGroups
import core.ems.service.idToLongOrInvalidReq
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


@RestController
@RequestMapping("/v2")
class TeacherReadCourseExercisesController(val courseService: CourseService) {
    private val log = KotlinLogging.logger {}

    data class CourseExerciseResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("exercise_id") val exerciseId: String,
        @JsonProperty("library_title") val libraryTitle: String,
        @JsonProperty("title_alias") val titleAlias: String?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("student_visible_from") val studentVisibleFrom: DateTime?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("soft_deadline") val softDeadline: DateTime?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("hard_deadline") val hardDeadline: DateTime?,
        @JsonProperty("grader_type") val graderType: GraderType,
        @JsonProperty("ordering_idx") val orderingIndex: Int,
        @JsonProperty("unstarted_count") val unstartedCount: Int,
        @JsonProperty("ungraded_count") val ungradedCount: Int,
        @JsonProperty("started_count") val startedCount: Int,
        @JsonProperty("completed_count") val completedCount: Int
    )

    data class Resp(@JsonProperty("exercises") val courseExercises: List<CourseExerciseResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        caller: EasyUser
    ): Resp {

        log.debug { "Getting exercises on course $courseIdString for teacher/admin ${caller.id}" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId, true) }

        return Resp(selectTeacherExercisesOnCourse(courseId, caller, courseService))
    }

    private fun selectTeacherExercisesOnCourse(
        courseId: Long,
        caller: EasyUser,
        courseService: CourseService
    ): List<CourseExerciseResp> = transaction {

        val restrictedGroups = getTeacherRestrictedCourseGroups(courseId, caller)
        val studentQuery = courseService.selectStudentsOnCourseQuery(courseId, emptyList(), restrictedGroups, true)

        val studentCount = studentQuery.count().toInt()
        val students = studentQuery.map { it[Student.id].value }

        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .slice(
                CourseExercise.id,
                CourseExercise.gradeThreshold,
                CourseExercise.softDeadline,
                CourseExercise.hardDeadline,
                CourseExercise.studentVisibleFrom,
                Exercise.id,
                ExerciseVer.graderType,
                ExerciseVer.title,
                CourseExercise.titleAlias
            )
            .select {
                CourseExercise.course eq courseId and ExerciseVer.validTo.isNull()
            }
            .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
            .mapIndexed { i, ex ->
                val ceId = ex[CourseExercise.id].value

                val latestSubmissionValidGrades =
                    courseService.selectLatestValidGrades(ceId, students).map { it.grade }

                val gradeThreshold = ex[CourseExercise.gradeThreshold]

                val unstartedCount = studentCount - latestSubmissionValidGrades.size
                val ungradedCount = latestSubmissionValidGrades.count { it == null }
                val startedCount = latestSubmissionValidGrades.count { it != null && it < gradeThreshold }
                val completedCount = latestSubmissionValidGrades.count { it != null && it >= gradeThreshold }

                // Sanity check
                if (unstartedCount + ungradedCount + startedCount + completedCount != studentCount)
                    log.warn {
                        "Student grade sanity check failed. unstarted: $unstartedCount, ungraded: $ungradedCount, " +
                                "started: $startedCount, completed: $completedCount, students in course: $studentCount"
                    }

                CourseExerciseResp(
                    ex[CourseExercise.id].value.toString(),
                    ex[Exercise.id].value.toString(),
                    ex[ExerciseVer.title],
                    ex[CourseExercise.titleAlias],
                    ex[CourseExercise.studentVisibleFrom],
                    ex[CourseExercise.softDeadline],
                    ex[CourseExercise.hardDeadline],
                    ex[ExerciseVer.graderType],
                    i,
                    unstartedCount,
                    ungradedCount,
                    startedCount,
                    completedCount
                )
            }
    }
}
