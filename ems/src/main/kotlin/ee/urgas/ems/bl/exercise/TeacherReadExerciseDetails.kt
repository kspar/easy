package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.canTeacherAccessCourse
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.db.GraderType
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.exception.InvalidRequestException
import ee.urgas.ems.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class TeacherReadExerciseDetailsController {

    data class ExDetailsResponse(@JsonProperty("title") val title: String,
                                 @JsonProperty("text_html") val text: String?,
                                 @JsonSerialize(using = DateTimeSerializer::class)
                                 @JsonProperty("soft_deadline") val softDeadline: DateTime?,
                                 @JsonSerialize(using = DateTimeSerializer::class)
                                 @JsonProperty("hard_deadline") val hardDeadline: DateTime?,
                                 @JsonProperty("grader_type") val grader: GraderType,
                                 @JsonProperty("threshold") val threshold: Int,
                                 @JsonSerialize(using = DateTimeSerializer::class)
                                 @JsonProperty("last_modified") val lastModified: DateTime,
                                 @JsonProperty("student_visible") val studentVisible: Boolean,
                                 @JsonProperty("assessments_student_visible") val assStudentVisible: Boolean)

    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}")
    fun readExDetails(@PathVariable("courseId") courseIdString: String,
                      @PathVariable("courseExerciseId") courseExerciseIdString: String): ExDetailsResponse {
        // TODO: get from auth
        val callerEmail = "ford"
        val courseId = courseIdString.toLong()

        if (!canTeacherAccessCourse(callerEmail, courseId)) {
            throw ForbiddenException("Teacher $callerEmail does not have access to course $courseId")
        }

        val exerciseDetails = selectTeacherCourseExerciseDetails(courseId, courseExerciseIdString.toLong())
                ?: throw InvalidRequestException("No course exercise found with id $courseExerciseIdString from course $courseId")

        return mapToExDetailsResponse(exerciseDetails)
    }

    private fun mapToExDetailsResponse(exDetails: TeacherExDetails): ExDetailsResponse =
            ExDetailsResponse(exDetails.title, exDetails.text, exDetails.softDeadline, exDetails.hardDeadline,
                    exDetails.grader, exDetails.threshold, exDetails.lastModified, exDetails.studentVisible,
                    exDetails.assStudentVisible)
}


data class TeacherExDetails(val title: String, val text: String?, val softDeadline: DateTime?, val hardDeadline: DateTime?,
                            val grader: GraderType, val threshold: Int, val lastModified: DateTime,
                            val studentVisible: Boolean, val assStudentVisible: Boolean)


private fun selectTeacherCourseExerciseDetails(courseId: Long, courseExId: Long): TeacherExDetails? {
    return transaction {
        (Course innerJoin CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(Course.id, CourseExercise.id, CourseExercise.softDeadline, CourseExercise.hardDeadline,
                        CourseExercise.gradeThreshold, CourseExercise.studentVisible, CourseExercise.assessmentsStudentVisible,
                        ExerciseVer.validTo, ExerciseVer.title, ExerciseVer.textHtml, ExerciseVer.graderType, ExerciseVer.validFrom)
                .select {
                    Course.id eq courseId and
                            (CourseExercise.id eq courseExId) and
                            ExerciseVer.validTo.isNull()
                }
                .map {
                    TeacherExDetails(
                            it[ExerciseVer.title],
                            it[ExerciseVer.textHtml],
                            it[CourseExercise.softDeadline],
                            it[CourseExercise.hardDeadline],
                            it[ExerciseVer.graderType],
                            it[CourseExercise.gradeThreshold],
                            it[ExerciseVer.validFrom],
                            it[CourseExercise.studentVisible],
                            it[CourseExercise.assessmentsStudentVisible]
                    )
                }
                .singleOrNull()
    }
}
