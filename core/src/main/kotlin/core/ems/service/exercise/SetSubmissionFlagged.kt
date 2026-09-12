package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Submission
import core.ems.service.assertAssessmentControllerChecks
import jakarta.validation.Valid
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


/**
 * "Come back to this" — one mark on the submission, shared by every teacher on the course.
 *
 * Deliberately not per teacher, unlike [SetSubmissionSeen]. A flag is a note left for whoever grades
 * next, which is often somebody else: a suspected copy, an answer worth showing in class, a case
 * that needs the lecturer rather than the assistant. A private flag would be a reminder; this is a
 * hand-off.
 *
 * It replaces a `localStorage` key the web app kept per browser, which no colleague could see and a
 * cleared cache lost. It rides on the column that used to be `submission.seen` (changeset 130926-1).
 */
@RestController
@RequestMapping("/v2")
class SetSubmissionFlagged {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("submissions") val submissions: List<SubmissionReq>,
        @param:JsonProperty("flagged") val flagged: Boolean
    )

    data class SubmissionReq(@param:JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/flagged")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Setting submissions flagged=${req.flagged} by ${caller.id} for submissions ${req.submissions}" }

        val submissions = req.submissions.map {
            val (_, _, submissionId) = assertAssessmentControllerChecks(
                caller,
                it.id,
                courseExerciseIdString,
                courseIdString,
            )
            submissionId
        }

        setSubmissionFlagged(submissions, req.flagged)
    }

    private fun setSubmissionFlagged(submissionIds: List<Long>, flagged: Boolean) = transaction {
        Submission.update({ Submission.id inList submissionIds }) {
            it[Submission.flagged] = flagged
        }
    }
}
