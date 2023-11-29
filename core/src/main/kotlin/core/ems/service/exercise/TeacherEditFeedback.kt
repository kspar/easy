package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherAssessment
import core.ems.service.AdocService
import core.ems.service.assertAssessmentControllerChecks
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherEditFeedbackController(val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("feedback_adoc", required = false) @field:Size(max = 300000) val feedbackAdoc: String?
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/feedback")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody assessment: Req,
        caller: EasyUser
    ) {

        log.info { "Set feedback by teacher ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val (callerId, _, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        updateFeedback(callerId, submissionId, assessment)
    }

    private fun updateFeedback(teacherId: String, submissionId: Long, assessment: Req) =
        transaction {

            val previousId = getPreviousTeacherAssessmentId(submissionId, teacherId)
            val previousGrade = TeacherAssessment
                .slice(TeacherAssessment.grade)
                .select { TeacherAssessment.id eq previousId }
                .map { it[TeacherAssessment.grade] }
                .singleOrNull()

            // If new adoc == null && prev grade null, delete to not leave empty assessment in the db,
            // otherwise update previous assessment
            if (previousGrade == null && assessment.feedbackAdoc == null) {
                TeacherAssessment.deleteWhere { TeacherAssessment.id eq previousId }
            } else {
                TeacherAssessment.update({ TeacherAssessment.id eq previousId }) {
                    it[editedAt] = DateTime.now()
                    it[feedbackAdoc] = assessment.feedbackAdoc
                    it[feedbackHtml] = assessment.feedbackAdoc?.let { adoc -> adocService.adocToHtml(adoc) }
                }
            }
        }

    private fun getPreviousTeacherAssessmentId(submissionId: Long, teacherId: String): Long =
        transaction {
            TeacherAssessment.slice(TeacherAssessment.id)
                .select {
                    TeacherAssessment.submission eq submissionId and (TeacherAssessment.teacher eq teacherId)
                }.orderBy(TeacherAssessment.mergeWindowStart, SortOrder.DESC)
                .map {
                    it[TeacherAssessment.id].value
                }.singleOrInvalidRequest()
        }
}
