package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class StudentReadExerciseDetailsController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("effective_title") val title: String,
        @JsonProperty("text_html") val textHtml: String?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("deadline") val softDeadline: DateTime?,
        @JsonProperty("grader_type") val graderType: GraderType,
        @JsonProperty("threshold") val threshold: Int,
        @JsonProperty("instructions_html") val instructionsHtml: String?,
        @JsonProperty("is_open") val isOpenForSubmissions: Boolean,
        @JsonProperty("solution_file_name") val solutionFileName: String,
        @JsonProperty("solution_file_type") val solutionFileType: SolutionFileType,
    )

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        caller: EasyUser
    ): Resp? {

        log.info { "Getting exercise details for student ${caller.id} on course exercise $courseExIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        // Student visibility check moved into selectStudentExerciseDetails to account for exceptions
        assertCourseExerciseIsOnCourse(courseExId, courseId, false)

        return selectStudentExerciseDetails(courseId, courseExId, caller.id)
    }

    private fun selectStudentExerciseDetails(courseId: Long, courseExId: Long, studentId: String): Resp = transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(
                ExerciseVer.title, ExerciseVer.textHtml, ExerciseVer.graderType, ExerciseVer.solutionFileName,
                ExerciseVer.solutionFileType, CourseExercise.softDeadline, CourseExercise.hardDeadline,
                CourseExercise.gradeThreshold, CourseExercise.instructionsHtml,
                CourseExercise.titleAlias, CourseExercise.studentVisibleFrom
            )
            .where {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        ExerciseVer.validTo.isNull()
            }
            .map {
                val exceptions = selectCourseExerciseExceptions(listOf(courseExId), listOf(studentId))

                val visibleFrom = determineCourseExerciseVisibleFrom(
                    exceptions,
                    courseExId,
                    studentId,
                    it[CourseExercise.studentVisibleFrom]
                )
                if (visibleFrom == null || visibleFrom.isAfterNow) {
                    throw InvalidRequestException(
                        "Course exercise $courseExId not found on course $courseId or it is hidden",
                        ReqError.ENTITY_WITH_ID_NOT_FOUND
                    )
                }

                Resp(
                    it[CourseExercise.titleAlias] ?: it[ExerciseVer.title],
                    it[ExerciseVer.textHtml],
                    determineSoftDeadline(exceptions, courseExId, studentId, it[CourseExercise.softDeadline]),
                    it[ExerciseVer.graderType],
                    it[CourseExercise.gradeThreshold],
                    it[CourseExercise.instructionsHtml],
                    isCourseExerciseOpenForSubmit(exceptions, courseExId, studentId, it[CourseExercise.hardDeadline]),
                    it[ExerciseVer.solutionFileName],
                    it[ExerciseVer.solutionFileType],
                )
            }
            .singleOrInvalidRequest()
    }
}

