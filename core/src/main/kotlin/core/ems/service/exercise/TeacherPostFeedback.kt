package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherFeedbackController(val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.activity.merge-window.s}")
    private lateinit var mergeWindowInSeconds: String

    data class Req(
        @JsonProperty("feedback_adoc", required = true)
        @field:Size(max = 300000)
        @field:NotBlank
        val feedbackAdoc: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/feedback")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody assessment: Req,
        caller: EasyUser
    ) {

        log.info { "Set feedback by teacher ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val (callerId, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        insertOrUpdateFeedback(callerId, submissionId, assessment, courseExId)
    }

    private fun insertOrUpdateFeedback(teacherId: String, submissionId: Long, assessment: Req, courseExId: Long) =
        transaction {
            val previousId = getIdIfShouldMerge(submissionId, teacherId, mergeWindowInSeconds.toInt())

            if (previousId != null) {
                TeacherAssessment.update({ TeacherAssessment.id eq previousId }) {
                    it[mergeWindowStart] = DateTime.now()
                    it[feedbackAdoc] = assessment.feedbackAdoc
                    it[feedbackHtml] = adocService.adocToHtml(assessment.feedbackAdoc)
                }
            } else {
                TeacherAssessment.insert {
                    it[student] = selectStudentBySubmissionId(submissionId)
                    it[courseExercise] = courseExId
                    it[submission] = submissionId
                    it[teacher] = teacherId
                    it[mergeWindowStart] = DateTime.now()
                    it[feedbackAdoc] = assessment.feedbackAdoc
                    it[feedbackHtml] = adocService.adocToHtml(assessment.feedbackAdoc)
                }
            }
        }

    private fun getIdIfShouldMerge(submissionId: Long, teacherId: String, mergeWindow: Int): Long? =
        transaction {
            TeacherAssessment.slice(TeacherAssessment.id, TeacherAssessment.mergeWindowStart)
                .select {
                    TeacherAssessment.submission eq submissionId and (TeacherAssessment.teacher eq teacherId)
                }.orderBy(TeacherAssessment.mergeWindowStart, SortOrder.DESC)
                .firstNotNullOfOrNull {
                    val timeIsInWindow = !it[TeacherAssessment.mergeWindowStart].hasSecondsPassed(mergeWindow)
                    val noFeedback = it[TeacherAssessment.feedbackAdoc] == null
                    if (timeIsInWindow && noFeedback) it[TeacherAssessment.id].value else null
                }
        }

}
