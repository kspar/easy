package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Submission
import core.ems.service.assertAssessmentControllerChecks
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid


@RestController
@RequestMapping("/v2")
class SetSubmissionSeen {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("submissions") val submissions: List<SubmissionReq>,
        @JsonProperty("seen") val seen: Boolean
    )

    data class SubmissionReq(
        @JsonProperty("id") val id: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/seen")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Setting submissions seen by ${caller.id} for submissions ${req.submissions}" }

        val submissions = req.submissions.map {
            val (_, _, submissionId) = assertAssessmentControllerChecks(
                caller,
                it.id,
                courseExerciseIdString,
                courseIdString,
            )
            submissionId
        }

        setSubmissionSeen(submissions, req.seen)
    }

    private fun setSubmissionSeen(submissionIds: List<Long>, seen: Boolean) = transaction {
        Submission.update({ Submission.id inList submissionIds }) {
            it[Submission.seen] = seen
        }
    }
}