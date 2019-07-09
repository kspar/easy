package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.assertTeacherOrAdminHasAccessToCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.*
import ee.urgas.ems.exception.InvalidRequestException
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
                                 @JsonProperty("assessments_student_visible") val assStudentVisible: Boolean,
                                 @JsonProperty("instructions_html") val instructionsHtml: String?,
                                 @JsonProperty("title_alias") val titleAlias: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}")
    fun readExDetails(@PathVariable("courseId") courseIdString: String,
                      @PathVariable("courseExerciseId") courseExerciseIdString: String,
                      caller: EasyUser): ExDetailsResponse {

        log.debug { "Getting exercise details for ${caller.id} for course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val exerciseDetails = selectTeacherCourseExerciseDetails(courseId, courseExerciseIdString.idToLongOrInvalidReq())
                ?: throw InvalidRequestException("No course exercise found with id $courseExerciseIdString from course $courseId")

        return mapToExDetailsResponse(exerciseDetails)
    }

    private fun mapToExDetailsResponse(exDetails: TeacherExDetails): ExDetailsResponse =
            ExDetailsResponse(exDetails.title, exDetails.text, exDetails.softDeadline, exDetails.hardDeadline,
                    exDetails.grader, exDetails.threshold, exDetails.lastModified, exDetails.studentVisible,
                    exDetails.assStudentVisible, exDetails.instructionsHtml, exDetails.titleAlias)
}


data class TeacherExDetails(val title: String, val text: String?, val softDeadline: DateTime?, val hardDeadline: DateTime?,
                            val grader: GraderType, val threshold: Int, val lastModified: DateTime,
                            val studentVisible: Boolean, val assStudentVisible: Boolean, val instructionsHtml: String?,
                            val titleAlias: String?)


private fun selectTeacherCourseExerciseDetails(courseId: Long, courseExId: Long): TeacherExDetails? {
    return transaction {
        (Course innerJoin CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(Course.id, CourseExercise.id, CourseExercise.softDeadline, CourseExercise.hardDeadline,
                        CourseExercise.gradeThreshold, CourseExercise.studentVisible, CourseExercise.assessmentsStudentVisible,
                        ExerciseVer.validTo, ExerciseVer.title, ExerciseVer.textHtml, ExerciseVer.graderType, ExerciseVer.validFrom,
                        CourseExercise.instructionsHtml, CourseExercise.titleAlias)
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
                            it[CourseExercise.assessmentsStudentVisible],
                            it[CourseExercise.instructionsHtml],
                            it[CourseExercise.titleAlias]
                    )
                }
                .singleOrNull()
    }
}
