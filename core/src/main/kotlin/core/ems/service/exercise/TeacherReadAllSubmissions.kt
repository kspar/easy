package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
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
class TeacherReadAllSubmissionsController {

    data class SubmissionResp(
            @JsonProperty("id") val submissionId: String,
            @JsonProperty("solution") val solution: String,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("created_at") val createdAt: DateTime,
            @JsonProperty("grade_auto") val gradeAuto: Int?,
            @JsonProperty("feedback_auto") val feedbackAuto: String?,
            @JsonProperty("grade_teacher") val gradeTeacher: Int?,
            @JsonProperty("feedback_teacher") val feedbackTeacher: String?)

    data class Resp(@JsonProperty("submissions") val submissions: List<SubmissionResp>)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/all/students/{studentId}")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @PathVariable("courseExerciseId") courseExerciseIdString: String,
                   @PathVariable("studentId") studentId: String,
                   caller: EasyUser): Resp {

        log.debug { "Getting all submissions for ${caller.id} by $studentId on course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return selectTeacherAllSubmissions(courseId, courseExId, studentId)
    }
}


private fun selectTeacherAllSubmissions(courseId: Long, courseExId: Long, studentId: String): TeacherReadAllSubmissionsController.Resp {
    return transaction {
        TeacherReadAllSubmissionsController.Resp(
                (Course innerJoin CourseExercise innerJoin Submission)
                        .slice(Course.id, CourseExercise.id, Submission.student, Submission.createdAt, Submission.id, Submission.solution)
                        .select {
                            Course.id eq courseId and
                                    (CourseExercise.id eq courseExId) and
                                    (Submission.student eq studentId)
                        }
                        .orderBy(Submission.createdAt, SortOrder.DESC)
                        .map {
                            val id = it[Submission.id].value

                            val autoAssessment = lastAutoAssessment(id)
                            val teacherAssessment = lastTeacherAssessment(id)

                            TeacherReadAllSubmissionsController.SubmissionResp(
                                    id.toString(),
                                    it[Submission.solution],
                                    it[Submission.createdAt],
                                    autoAssessment?.grade,
                                    autoAssessment?.feedback,
                                    teacherAssessment?.grade,
                                    teacherAssessment?.feedback)
                        })

    }
}

private fun lastAutoAssessment(submissionId: Long): AssessmentSummary? {
    return AutomaticAssessment.select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { AssessmentSummary(it[AutomaticAssessment.grade], it[AutomaticAssessment.feedback]) }
            .firstOrNull()
}

private fun lastTeacherAssessment(submissionId: Long): AssessmentSummary? {
    return TeacherAssessment.select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { AssessmentSummary(it[TeacherAssessment.grade], it[TeacherAssessment.feedback]) }
            .firstOrNull()
}
