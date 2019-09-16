package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.AutomaticAssessment
import core.db.CourseExercise
import core.db.Submission
import core.db.TeacherAssessment
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
import org.springframework.web.bind.annotation.*

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

    data class Resp(@JsonProperty("submissions") val submissions: List<SubmissionResp>,
                    @JsonProperty("count") val submissionCount: Int)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/all/students/{studentId}")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @PathVariable("courseExerciseId") courseExerciseIdString: String,
                   @PathVariable("studentId") studentId: String,
                   @RequestParam("limit", required = false) limitStr: String?,
                   @RequestParam("offset", required = false) offsetStr: String?,
                   caller: EasyUser): Resp {

        log.debug { "Getting submissions for ${caller.id} by $studentId on course exercise $courseExerciseIdString on course $courseIdString (limit: $limitStr, offset: $offsetStr)" }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return selectTeacherAllSubmissions(courseId, courseExId, studentId, limitStr?.toIntOrNull(), offsetStr?.toIntOrNull())
    }
}


private fun selectTeacherAllSubmissions(courseId: Long, courseExId: Long, studentId: String, limit: Int?, offset: Int?):
        TeacherReadAllSubmissionsController.Resp {
    return transaction {

        val query = (CourseExercise innerJoin Submission)
                .slice(Submission.createdAt, Submission.id, Submission.solution)
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (Submission.student eq studentId)
                }

        val count = query.count()

        TeacherReadAllSubmissionsController.Resp(
                query.orderBy(Submission.createdAt, SortOrder.DESC)
                        .limit(limit ?: count, offset ?: 0)
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
                        }, count)
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
