package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.ServerTimeoutException
import core.util.DateTimeSerializer
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
class StudentReadLatestAutoGradedSubmissionController {


    data class StudentSubmissionResp(@JsonProperty("solution") val solution: String,
                                     @JsonSerialize(using = DateTimeSerializer::class)
                                     @JsonProperty("submission_time") val submissionTime: DateTime,
                                     @JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
                                     @JsonProperty("grade_auto") val gradeAuto: Int?,
                                     @JsonProperty("feedback_auto") val feedbackAuto: String?,
                                     @JsonProperty("grade_teacher") val gradeTeacher: Int?,
                                     @JsonProperty("feedback_teacher") val feedbackTeacher: String?)

    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/autograded")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExerciseIdStr: String,
                   response: HttpServletResponse, caller: EasyUser): StudentSubmissionResp? {

        log.debug { "Getting latest submission for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)

        val submission = selectLatestStudentSubmission(courseId, courseExId, caller.id)
        return if (submission != null) {
            submission
        } else {
            response.status = HttpStatus.NO_CONTENT.value()
            null
        }
    }
}


private fun selectLatestStudentSubmission(courseId: Long, courseExId: Long, studentId: String):
        StudentReadLatestAutoGradedSubmissionController.StudentSubmissionResp? {

    data class SubmissionPartial(val id: Long,
                                 val solution: String,
                                 val time: DateTime,
                                 val autogradeStatus: AutoGradeStatus)
    //TODO: From conf? From db? From request?
    val sleepStart = 500
    val sleepStep = 250
    val timeoutSteps = 10
    var sleepCounter = 0
    while (true) {
        log.debug { "Waiting for AutoGrad... Iteration $sleepCounter" }

        val response = transaction {

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

                StudentReadLatestAutoGradedSubmissionController.StudentSubmissionResp(
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


        if (response == null || response.autoGradeStatus != AutoGradeStatus.IN_PROGRESS) {
            log.debug { "Received AutoGrad results on iteration $sleepCounter" }
            return response
        } else {
            sleepCounter++
            Thread.sleep((sleepStart + sleepCounter * sleepStep).toLong())
        }

        if (sleepCounter > timeoutSteps) {
            throw ServerTimeoutException("Waiting for AutoGrader reached timeout after $timeoutSteps steps.")
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
