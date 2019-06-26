package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.canStudentAccessCourse
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.db.GraderType
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.util.DateTimeSerializer
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

    data class ExerciseDetails(@JsonProperty("title") val title: String,
                               @JsonProperty("text_html") val textHtml: String?,
                               @JsonSerialize(using = DateTimeSerializer::class)
                               @JsonProperty("deadline") val softDeadline: DateTime?,
                               @JsonProperty("grader_type") val graderType: GraderType,
                               @JsonProperty("threshold") val threshold: Int)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}")
    fun getStudentExerciseDetails(@PathVariable("courseId") courseIdStr: String,
                                  @PathVariable("courseExerciseId") courseExIdStr: String,
                                  caller: EasyUser): ExerciseDetails {

        val callerId = caller.id
        val courseId = courseIdStr.toLong()
        val courseExId = courseExIdStr.toLong()

        if (!canStudentAccessCourse(callerId, courseId)) {
            throw ForbiddenException("Student $callerId does not have access to course $courseId")
        }

        return selectStudentExerciseDetails(courseId, courseExId)
    }
}


private fun selectStudentExerciseDetails(courseId: Long, courseExId: Long): StudentReadExerciseDetailsController.ExerciseDetails {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.title, ExerciseVer.textHtml, ExerciseVer.graderType,
                        CourseExercise.softDeadline, CourseExercise.gradeThreshold)
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            ExerciseVer.validTo.isNull()
                }
                .map {
                    StudentReadExerciseDetailsController.ExerciseDetails(
                            it[ExerciseVer.title],
                            it[ExerciseVer.textHtml],
                            it[CourseExercise.softDeadline],
                            it[ExerciseVer.graderType],
                            it[CourseExercise.gradeThreshold]
                    )
                }
                .first()
    }
}

