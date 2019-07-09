package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import ee.urgas.ems.bl.access.assertTeacherOrAdminHasAccessToCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.exception.InvalidRequestException
import ee.urgas.ems.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherCreateCourseExerciseController {

    data class NewCourseExerciseBody(@JsonProperty("exercise_id", required = true) val exerciseId: String,
                                     @JsonProperty("threshold", required = true) val threshold: Int,
                                     @JsonDeserialize(using = DateTimeDeserializer::class)
                                     @JsonProperty("soft_deadline", required = false) val softDeadline: DateTime?,
                                     @JsonDeserialize(using = DateTimeDeserializer::class)
                                     @JsonProperty("hard_deadline", required = false) val hardDeadline: DateTime?,
                                     @JsonProperty("student_visible", required = true) val studentVisible: Boolean,
                                     @JsonProperty("assessments_student_visible", required = true) val assStudentVisible: Boolean,
                                     @JsonProperty("instructions_html", required = false) val instructionsHtml: String?,
                                     @JsonProperty("title_alias", required = false) val titleAlias: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises")
    fun addExerciseToCourse(@PathVariable("courseId") courseIdString: String,
                            @RequestBody body: NewCourseExerciseBody,
                            caller: EasyUser) {

        log.debug { "Adding exercise ${body.exerciseId} to course $courseIdString by ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val exerciseId = body.exerciseId.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        if (isExerciseOnCourse(courseId, exerciseId)) {
            throw InvalidRequestException("Exercise $exerciseId is already on course $courseId")
        }

        insertCourseExercise(courseId, body)
    }
}


private fun isExerciseOnCourse(courseId: Long, exerciseId: Long): Boolean {
    return transaction {
        CourseExercise.select {
            CourseExercise.course eq courseId and (CourseExercise.exercise eq exerciseId)
        }.count() > 0
    }
}

private fun insertCourseExercise(courseId: Long, body: TeacherCreateCourseExerciseController.NewCourseExerciseBody) {
    val exerciseId = body.exerciseId.idToLongOrInvalidReq()
    transaction {
        CourseExercise.insert {
            it[course] = EntityID(courseId, Course)
            it[exercise] = EntityID(exerciseId, Exercise)
            it[gradeThreshold] = body.threshold
            it[softDeadline] = body.softDeadline
            it[hardDeadline] = body.hardDeadline
            it[orderIdx] = 0 // TODO: not set at the moment
            it[studentVisible] = body.studentVisible
            it[assessmentsStudentVisible] = body.assStudentVisible
            it[instructionsHtml] = body.instructionsHtml
            it[titleAlias] = body.titleAlias
        }
    }
}
