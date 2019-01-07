package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.canTeacherAccessCourse
import ee.urgas.ems.db.AutomaticAssessment
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Submission
import ee.urgas.ems.db.TeacherAssessment
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
class TeacherReadSubmissionController {

    data class TeacherSubmissionResp(@JsonSerialize(using = DateTimeSerializer::class)
                                     @JsonProperty("created_at") val createdAt: DateTime,
                                     @JsonProperty("grade_auto") val gradeAuto: Int?,
                                     @JsonProperty("feedback_auto") val feedbackAuto: String?,
                                     @JsonProperty("grade_teacher") val gradeTeacher: Int?,
                                     @JsonProperty("feedback_teacher") val feedbackTeacher: String?,
                                     @JsonProperty("solution") val solution: String)

    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students/{studentEmail}")
    fun readTeacherSubmission(@PathVariable("courseId") courseIdString: String,
                              @PathVariable("courseExerciseId") courseExerciseIdString: String,
                              @PathVariable("studentEmail") studentEmail: String): TeacherSubmissionResp {
        // TODO: get from auth
        val callerEmail = "ford"
        val courseId = courseIdString.toLong()
        val courseExId = courseExerciseIdString.toLong()

        if (!canTeacherAccessCourse(callerEmail, courseId)) {
            throw ForbiddenException("Teacher $callerEmail does not have access to course $courseId")
        }

        val submission = selectTeacherSubmission(courseId, courseExId, studentEmail)
                ?: throw InvalidRequestException(
                        "No submission found for student $studentEmail on exercise $courseExId on course $courseId")

        return mapToTeacherSubmissionResp(submission)
    }

    private fun mapToTeacherSubmissionResp(sub: TeacherSubmission): TeacherSubmissionResp =
            TeacherSubmissionResp(sub.createdAt, sub.gradeAuto, sub.feedbackAuto, sub.gradeTeacher, sub.feedbackTeacher, sub.solution)

}


data class TeacherSubmission(val solution: String, val createdAt: DateTime, val gradeAuto: Int?, val feedbackAuto: String?,
                             val gradeTeacher: Int?, val feedbackTeacher: String?)


private fun selectTeacherSubmission(courseId: Long, courseExId: Long, studentEmail: String): TeacherSubmission? {

    data class SubmissionPartial(val id: Long, val solution: String, val createdAt: DateTime)

    return transaction {
        val lastSubmission =
                (Course innerJoin CourseExercise innerJoin Submission)
                        .slice(Course.id, CourseExercise.id, Submission.student, Submission.createdAt, Submission.id, Submission.solution)
                        .select {
                            Course.id eq courseId and
                                    (CourseExercise.id eq courseExId) and
                                    (Submission.student eq studentEmail)
                        }
                        .orderBy(Submission.createdAt to false)
                        .limit(1)
                        .map {
                            SubmissionPartial(it[Submission.id].value,
                                    it[Submission.solution],
                                    it[Submission.createdAt])
                        }
                        .singleOrNull()

        if (lastSubmission != null) {
            val autoAssessment = lastAutoAssessment(lastSubmission.id)
            val teacherAssessment = lastTeacherAssessment(lastSubmission.id)

            TeacherSubmission(
                    lastSubmission.solution,
                    lastSubmission.createdAt,
                    autoAssessment?.grade,
                    autoAssessment?.feedback,
                    teacherAssessment?.grade,
                    teacherAssessment?.feedback
            )
        } else {
            null
        }
    }
}

data class AssessmentSummary(val grade: Int, val feedback: String?)

private fun lastAutoAssessment(submissionId: Long): AssessmentSummary? {
    return AutomaticAssessment.select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to false)
            .limit(1)
            .map { AssessmentSummary(it[AutomaticAssessment.grade], it[AutomaticAssessment.feedback]) }
            .firstOrNull()
}

private fun lastTeacherAssessment(submissionId: Long): AssessmentSummary? {
    return TeacherAssessment.select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to false)
            .limit(1)
            .map { AssessmentSummary(it[TeacherAssessment.grade], it[TeacherAssessment.feedback]) }
            .firstOrNull()
}
