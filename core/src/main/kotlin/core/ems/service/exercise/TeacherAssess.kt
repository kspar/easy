package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
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
class TeacherAssessController {

    data class AssessBody(@JsonProperty("grade", required = true) val grade: Int,
                          @JsonProperty("feedback", required = false) val feedback: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/assessments")
    fun assess(@PathVariable("courseId") courseIdString: String,
               @PathVariable("courseExerciseId") courseExerciseIdString: String,
               @PathVariable("submissionId") submissionIdString: String,
               @RequestBody assessment: AssessBody, caller: EasyUser) {

        log.debug { "Adding teacher assessment by ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val callerId = caller.id
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
        val submissionId = submissionIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        if (!submissionExists(submissionId, courseExId, courseId)) {
            throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
        }

        if (assessment.grade < 0 || assessment.grade > 100) {
            throw InvalidRequestException("Expected submission $submissionId grade on course exercise $courseExId on course $courseId to be [0,100], but got ${assessment.grade}")
        }

        insertTeacherAssessment(callerId, submissionId, mapToTeacherAssessment(assessment))
    }

    private fun mapToTeacherAssessment(body: AssessBody) = Assessment(body.grade, body.feedback)

}


data class Assessment(val grade: Int, val feedback: String?)


private fun submissionExists(submissionId: Long, courseExId: Long, courseId: Long): Boolean {
    return transaction {
        (Course innerJoin CourseExercise innerJoin Submission)
                .select {
                    Course.id eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (Submission.id eq submissionId)
                }.count() == 1
    }
}

private fun insertTeacherAssessment(teacherId: String, submissionId: Long, assessment: Assessment) {
    transaction {
        TeacherAssessment.insert {
            it[TeacherAssessment.submission] = EntityID(submissionId, Submission)
            it[TeacherAssessment.teacher] = EntityID(teacherId, Teacher)
            it[TeacherAssessment.createdAt] = DateTime.now()
            it[TeacherAssessment.grade] = assessment.grade
            it[TeacherAssessment.feedback] = assessment.feedback
        }
    }
}
