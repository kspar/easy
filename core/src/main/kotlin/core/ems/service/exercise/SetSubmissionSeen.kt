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
        @JsonProperty("seen") val seen: Boolean,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/seen")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Setting submissions seen by ${caller.id} for submission $submissionIdString" }

        val (_, _, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        setSubmissionSeen(submissionId, req.seen)
    }

    private fun setSubmissionSeen(submissionId: Long, seen: Boolean) = transaction {
        Submission.update({ Submission.id eq submissionId }) {
            it[Submission.seen] = seen
        }
    }
}