package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.AutomaticAssessment
import core.db.TeacherAssessment
import core.ems.service.TeacherResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.getSubmissionNumbers
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectTeacher
import core.exception.InvalidRequestException
import core.exception.ReqError
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

@RestController
@RequestMapping("/v2")
class ReadStudentAllExerciseActivities {
    private val log = KotlinLogging.logger {}

    data class FeedbackResp(
        @JsonProperty("feedback_html") val feedbackHtml: String, @JsonProperty("feedback_adoc") val feedbackAdoc: String
    )

    data class TeacherActivityResp(
        @JsonProperty("submission_id") val submissionId: Long,
        @JsonProperty("submission_number") val submissionNumber: Int,
        @JsonProperty("created_at") @JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
        @JsonProperty("grade") val grade: Int?,
        @JsonProperty("edited_at") @JsonSerialize(using = DateTimeSerializer::class) val editedAt: DateTime?,
        @JsonProperty("feedback") val feedback: FeedbackResp?,
        @JsonProperty("teacher") val teacher: TeacherResp
    )

    data class AutomaticAssessmentResp(
        @JsonProperty("submission_id") val submissionId: Long,
        @JsonProperty("grade") val grade: Int,
        @JsonProperty("feedback") val feedback: String?
    )

    data class Resp(
        @JsonProperty("teacher_activities") val teacherActivities: List<TeacherActivityResp>,
        @JsonProperty("auto_assessments") val autoAssessments: List<AutomaticAssessmentResp>
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/students/{studentId}/activities")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("studentId") studentId: String,
        caller: EasyUser
    ): Resp {

        log.info { "Getting activities for ${caller.id} by $studentId on course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId, true) }

        return selectTeacherAllSubmissions(courseExId, studentId)
    }

    private fun selectTeacherAllSubmissions(courseExId: Long, studentId: String): Resp = transaction {
        val submissionIdNumbers: Map<Long, Int> = getSubmissionNumbers(studentId, courseExId)

        val teacherActivities = TeacherAssessment
            .slice(
                TeacherAssessment.submission,
                TeacherAssessment.feedbackHtml,
                TeacherAssessment.feedbackAdoc,
                TeacherAssessment.mergeWindowStart,
                TeacherAssessment.grade,
                TeacherAssessment.editedAt,
                TeacherAssessment.teacher,
            ).select {
                TeacherAssessment.student eq studentId and (TeacherAssessment.courseExercise eq courseExId)
            }.orderBy(TeacherAssessment.mergeWindowStart, SortOrder.ASC)
            .map {
                val submissionId = it[TeacherAssessment.submission].value
                val html = it[TeacherAssessment.feedbackHtml]
                val adoc = it[TeacherAssessment.feedbackAdoc]

                TeacherActivityResp(
                    submissionId,
                    submissionIdNumbers[submissionId] ?: throw InvalidRequestException(
                        "Submission ID number $submissionId not mappable to order number (mapping to order not found)",
                        ReqError.ENTITY_WITH_ID_NOT_FOUND
                    ),
                    it[TeacherAssessment.mergeWindowStart],
                    it[TeacherAssessment.grade],
                    it[TeacherAssessment.editedAt],
                    if (html != null && adoc != null) FeedbackResp(html, adoc) else null,
                    selectTeacher(it[TeacherAssessment.teacher].value)
                )
            }

        val autoAssessments = AutomaticAssessment
            .slice(
                AutomaticAssessment.submission,
                AutomaticAssessment.grade,
                AutomaticAssessment.feedback
            ).select { AutomaticAssessment.student eq studentId and (AutomaticAssessment.courseExercise eq courseExId) }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.ASC)
            .map {
                AutomaticAssessmentResp(
                    it[AutomaticAssessment.submission].value,
                    it[AutomaticAssessment.grade],
                    it[AutomaticAssessment.feedback]
                )
            }

        Resp(teacherActivities, autoAssessments)
    }
}