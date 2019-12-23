package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseExercise
import core.db.Exercise
import core.ems.service.AdocService
import core.ems.service.IDX_STEP
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.PositiveOrZero
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherCreateCourseExerciseController(private val adocService: AdocService) {

    data class Req(@JsonProperty("exercise_id", required = true) @field:NotBlank @field:Size(max = 100) val exerciseId: String,
                   @JsonProperty("threshold", required = true) @field:PositiveOrZero val threshold: Int,
                   @JsonDeserialize(using = DateTimeDeserializer::class)
                   @JsonProperty("soft_deadline", required = false) val softDeadline: DateTime?,
                   @JsonDeserialize(using = DateTimeDeserializer::class)
                   @JsonProperty("hard_deadline", required = false) val hardDeadline: DateTime?,
                   @JsonProperty("student_visible", required = true) val studentVisible: Boolean,
                   @JsonProperty("assessments_student_visible", required = true) val assStudentVisible: Boolean,
                   @JsonProperty("instructions_adoc", required = false) @field:Size(max = 300000) val instructionsAdoc: String?,
                   @JsonProperty("title_alias", required = false) @field:Size(max = 100) val titleAlias: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @Valid @RequestBody body: Req,
                   caller: EasyUser) {

        log.debug { "Adding exercise ${body.exerciseId} to course $courseIdString by ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val exerciseId = body.exerciseId.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        if (!isCoursePresent(courseId)) {
            throw InvalidRequestException("Course $courseId does not exist")
        }

        if (isExerciseOnCourse(courseId, exerciseId)) {
            throw InvalidRequestException("Exercise $exerciseId is already on course $courseId")
        }

        when (body.instructionsAdoc) {
            null -> insertCourseExercise(courseId, body, null)
            else -> insertCourseExercise(courseId, body, adocService.adocToHtml(body.instructionsAdoc))
        }
    }
}


private fun isExerciseOnCourse(courseId: Long, exerciseId: Long): Boolean {
    return transaction {
        CourseExercise.select {
            CourseExercise.course eq courseId and (CourseExercise.exercise eq exerciseId)
        }.count() > 0
    }
}

private fun isCoursePresent(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId
        }.count() > 0
    }
}

private fun insertCourseExercise(courseId: Long, body: TeacherCreateCourseExerciseController.Req, html: String?) {
    val exerciseId = body.exerciseId.idToLongOrInvalidReq()
    transaction {
        val orderIdxMaxColumn = CourseExercise.orderIdx.max()

        val currentMaxOrderIdx = CourseExercise
                .slice(orderIdxMaxColumn)
                .select {
                    CourseExercise.course eq courseId
                }
                .groupBy(CourseExercise.course)
                .map { it[orderIdxMaxColumn] }
                .singleOrNull()

        val orderIndex = (currentMaxOrderIdx ?: 0) + IDX_STEP

        CourseExercise.insert {
            it[course] = EntityID(courseId, Course)
            it[exercise] = EntityID(exerciseId, Exercise)
            it[gradeThreshold] = body.threshold
            it[softDeadline] = body.softDeadline
            it[hardDeadline] = body.hardDeadline
            it[orderIdx] = orderIndex
            it[studentVisible] = body.studentVisible
            it[assessmentsStudentVisible] = body.assStudentVisible
            it[instructionsHtml] = html
            it[instructionsAdoc] = body.instructionsAdoc
            it[titleAlias] = body.titleAlias
        }
    }
}
