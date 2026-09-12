package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherSubmissionSeen
import core.ems.service.assertAssessmentControllerChecks
import jakarta.validation.Valid
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


/**
 * Whether the caller has read these submissions — one row per teacher, not a bit on the submission.
 *
 * The mark used to be shared by everyone on the course (`submission.seen`, now `submission.flagged`
 * doing a different job), which was defensible while it moved only when a teacher pressed a button.
 * The web app now sends this on opening a submission, and under a shared bit that would mean one
 * teacher's reading empties everyone else's queue. See changeset 130926-1.
 */
@RestController
@RequestMapping("/v2")
class SetSubmissionSeen {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("submissions") val submissions: List<SubmissionReq>,
        @param:JsonProperty("seen") val seen: Boolean
    )

    data class SubmissionReq(@param:JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/seen")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Setting submissions seen=${req.seen} by ${caller.id} for submissions ${req.submissions}" }

        val submissions = req.submissions.map {
            val (_, _, submissionId) = assertAssessmentControllerChecks(
                caller,
                it.id,
                courseExerciseIdString,
                courseIdString,
            )
            submissionId
        }

        setSubmissionSeen(submissions, caller.id, req.seen)
    }

    private fun setSubmissionSeen(submissionIds: List<Long>, teacherId: String, seen: Boolean) = transaction {
        if (seen) {
            // Upsert rather than insert: the browser marks a submission seen on opening it, so the
            // same row arrives again on every revisit, and a duplicate key would turn reading a
            // submission twice into a 500. The timestamp moves to the latest read.
            submissionIds.forEach { id ->
                TeacherSubmissionSeen.upsert(
                    TeacherSubmissionSeen.submission, TeacherSubmissionSeen.teacher,
                    onUpdateExclude = listOf(TeacherSubmissionSeen.submission, TeacherSubmissionSeen.teacher)
                ) {
                    it[submission] = id
                    it[teacher] = teacherId
                    it[seenAt] = DateTime.now()
                }
            }
        } else {
            TeacherSubmissionSeen.deleteWhere {
                submission inList submissionIds and (teacher eq teacherId)
            }
        }
    }
}
