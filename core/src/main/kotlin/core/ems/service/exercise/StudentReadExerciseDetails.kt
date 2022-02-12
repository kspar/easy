package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.Exercise
import core.db.ExerciseVer
import core.db.GraderType
import core.ems.service.assertCourseExerciseIsOnCourse
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import mu.KotlinLogging
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
class StudentReadExerciseDetailsController {

    data class Resp(
        @JsonProperty("effective_title") val title: String,
        @JsonProperty("text_html") val textHtml: String?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("deadline") val softDeadline: DateTime?,
        @JsonProperty("grader_type") val graderType: GraderType,
        @JsonProperty("threshold") val threshold: Int,
        @JsonProperty("instructions_html") val instructionsHtml: String?
    )

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        caller: EasyUser
    ): Resp? {

        log.debug { "Getting exercise details for student ${caller.id} on course exercise $courseExIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        return selectStudentExerciseDetails(courseId, courseExId)
            ?: throw InvalidRequestException(
                "Exercise $courseExId not found on course $courseId or it is hidden",
                ReqError.ENTITY_WITH_ID_NOT_FOUND
            )
    }
}


private fun selectStudentExerciseDetails(courseId: Long, courseExId: Long):
        StudentReadExerciseDetailsController.Resp? {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .slice(
                ExerciseVer.title, ExerciseVer.textHtml, ExerciseVer.graderType,
                CourseExercise.softDeadline, CourseExercise.gradeThreshold, CourseExercise.instructionsHtml,
                CourseExercise.titleAlias
            )
            .select {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        ExerciseVer.validTo.isNull()
            }
            .map {
                StudentReadExerciseDetailsController.Resp(
                    it[CourseExercise.titleAlias] ?: it[ExerciseVer.title],
                    it[ExerciseVer.textHtml],
                    it[CourseExercise.softDeadline],
                    it[ExerciseVer.graderType],
                    it[CourseExercise.gradeThreshold],
                    it[CourseExercise.instructionsHtml]
                )
            }
            .singleOrNull()
    }
}

