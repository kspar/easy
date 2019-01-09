package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.access.canTeacherAccessCourse
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Submission
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherAssessment
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class TeacherAssessController {

    data class AssessBody(@JsonProperty("grade", required = true) val grade: Int,
                          @JsonProperty("feedback", required = false) val feedback: String?)

    @Secured("ROLE_TEACHER")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/assessments")
    fun assess(@PathVariable("courseId") courseIdString: String,
               @PathVariable("courseExerciseId") courseExerciseIdString: String,
               @PathVariable("submissionId") submissionIdString: String,
               @RequestBody assessment: AssessBody, caller: EasyUser) {

        val callerEmail = caller.email
        val courseId = courseIdString.toLong()
        val courseExId = courseExerciseIdString.toLong()
        val submissionId = submissionIdString.toLong()

        if (!canTeacherAccessCourse(callerEmail, courseId)) {
            throw ForbiddenException("Teacher $callerEmail does not have access to course $courseId")
        }

        log.debug { "Adding teacher assessment $assessment to submission $submissionId" }
        if (!submissionExists(submissionId, courseExId, courseId)) {
            throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
        }

        insertTeacherAssessment(callerEmail, submissionId, mapToTeacherAssessment(assessment))
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

private fun insertTeacherAssessment(teacherEmail: String, submissionId: Long, assessment: Assessment) {
    transaction {
        TeacherAssessment.insert {
            it[TeacherAssessment.submission] = EntityID(submissionId, Submission)
            it[TeacherAssessment.teacher] = EntityID(teacherEmail, Teacher)
            it[TeacherAssessment.createdAt] = DateTime.now()
            it[TeacherAssessment.grade] = assessment.grade
            it[TeacherAssessment.feedback] = assessment.feedback
        }
    }
}
