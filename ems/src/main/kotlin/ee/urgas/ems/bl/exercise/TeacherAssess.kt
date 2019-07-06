package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.access.canTeacherAccessCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.*
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.exception.InvalidRequestException
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

    @Secured("ROLE_TEACHER")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/assessments")
    fun assess(@PathVariable("courseId") courseIdString: String,
               @PathVariable("courseExerciseId") courseExerciseIdString: String,
               @PathVariable("submissionId") submissionIdString: String,
               @RequestBody assessment: AssessBody, caller: EasyUser) {

        val callerId = caller.id
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
        val submissionId = submissionIdString.idToLongOrInvalidReq()

        if (!canTeacherAccessCourse(callerId, courseId)) {
            throw ForbiddenException("Teacher $callerId does not have access to course $courseId")
        }

        log.debug { "Adding teacher assessment $assessment to submission $submissionId" }
        if (!submissionExists(submissionId, courseExId, courseId)) {
            throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
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
