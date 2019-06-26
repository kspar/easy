package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.canStudentAccessCourse
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.AutoGradeStatus
import ee.urgas.ems.db.AutomaticAssessment
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Submission
import ee.urgas.ems.db.TeacherAssessment
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentReadLatestSubmissionController {


    data class StudentSubmissionResp(@JsonProperty("solution") val solution: String,
                                     @JsonSerialize(using = DateTimeSerializer::class)
                                     @JsonProperty("submission_time") val submissionTime: DateTime,
                                     @JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
                                     @JsonProperty("grade_auto") val gradeAuto: Int?,
                                     @JsonProperty("feedback_auto") val feedbackAuto: String?,
                                     @JsonProperty("grade_teacher") val gradeTeacher: Int?,
                                     @JsonProperty("feedback_teacher") val feedbackTeacher: String?)

    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest")
    fun readLatestSubmission(@PathVariable("courseId") courseIdString: String,
                             @PathVariable("courseExerciseId") courseExerciseIdString: String,
                             response: HttpServletResponse, caller: EasyUser): StudentSubmissionResp? {

        val callerId = caller.id
        val courseId = courseIdString.toLong()
        val courseExId = courseExerciseIdString.toLong()

        if (!canStudentAccessCourse(callerId, courseId)) {
            throw ForbiddenException("Student $callerId does not have access to course $courseId")
        }

        val submission = selectLatestStudentSubmission(courseId, courseExId, callerId)
        return if (submission != null) {
            submission
        } else {
            response.status = HttpStatus.NO_CONTENT.value()
            null
        }
    }
}


private fun selectLatestStudentSubmission(courseId: Long, courseExId: Long, studentId: String):
        StudentReadLatestSubmissionController.StudentSubmissionResp? {

    data class SubmissionPartial(val id: Long, val solution: String, val time: DateTime,
                                 val autogradeStatus: AutoGradeStatus)

    return transaction {

        val lastSubmission =
                (CourseExercise innerJoin Submission)
                        .slice(Submission.createdAt, Submission.id, Submission.solution, Submission.autoGradeStatus)
                        .select {
                            CourseExercise.course eq courseId and
                                    (CourseExercise.id eq courseExId) and
                                    (Submission.student eq studentId)
                        }
                        .orderBy(Submission.createdAt to false)
                        .limit(1)
                        .map {
                            SubmissionPartial(it[Submission.id].value,
                                    it[Submission.solution],
                                    it[Submission.createdAt],
                                    it[Submission.autoGradeStatus])
                        }
                        .singleOrNull()


        if (lastSubmission != null) {
            val autoAssessment = lastAutoAssessment(lastSubmission.id)
            val teacherAssessment = lastTeacherAssessment(lastSubmission.id)

            StudentReadLatestSubmissionController.StudentSubmissionResp(
                    lastSubmission.solution,
                    lastSubmission.time,
                    lastSubmission.autogradeStatus,
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
