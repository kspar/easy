package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.AutoGradeStatus
import core.db.Submission
import core.db.TeacherSubmissionSeen
import core.ems.service.*
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadSubmissionDetails {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("submission_number") val submissionNumber: Int,
        @get:JsonProperty("solution") val solution: String,
        /** Whether the *caller* has opened this submission — not whether anybody has. */
        @get:JsonProperty("seen") val seen: Boolean,
        @get:JsonProperty("flagged") val flagged: Boolean,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("autograde_status") val autoGradeStatus: AutoGradeStatus,
        @get:JsonProperty("grade") val grade: GradeResp?,
        @get:JsonProperty("auto_assessment") val autoAssessments: AutomaticAssessmentResp?
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        caller: EasyUser
    ): Resp {

        log.info { "Getting submissions details for ${caller.id} on course exercise $courseExerciseIdString on course $courseIdString" }


        val (_, courseExId, submissionId) = assertAssessmentControllerChecks(
            caller,
            submissionIdString,
            courseExerciseIdString,
            courseIdString,
        )

        return selectSubmissionDetails(submissionId, courseExId, caller.id)
    }

    /**
     * Whether any of this student's attempts at this exercise carries the flag.
     *
     * Not the attempt on screen: the flag marks the student's work, the button that sets it only
     * appears on the newest attempt, and the two lists that show it read the newest too. Asking the
     * submission alone would have this page disagree with the list it was opened from as soon as
     * the student submitted again.
     */
    private fun isWorkFlagged(courseExId: Long, studentId: String): Boolean =
        Submission.select(Submission.id).where {
            Submission.courseExercise eq courseExId and (Submission.student eq studentId) and
                    (Submission.flagged eq true)
        }.limit(1).any()

    private fun selectSubmissionDetails(submissionId: Long, courseExId: Long, callerId: String): Resp = transaction {
        // Left-joined and filtered to the caller: seen is a row per teacher, and a colleague's row
        // is not an answer to whether this teacher has read it.
        val callerHasSeen = TeacherSubmissionSeen.submission.isNotNull()

        Submission
            .join(TeacherSubmissionSeen, JoinType.LEFT, Submission.id, TeacherSubmissionSeen.submission) {
                TeacherSubmissionSeen.teacher eq callerId
            }
            .select(
                Submission.id,
                Submission.grade,
                Submission.isAutoGrade,
                Submission.solution,
                Submission.createdAt,
                Submission.autoGradeStatus,
                callerHasSeen,
                Submission.student,
                Submission.number,
                Submission.isGradedDirectly
            )
            .where { Submission.id eq submissionId and (Submission.courseExercise eq courseExId) }
            .map {
                Resp(
                    it[Submission.id].value.toString(),
                    it[Submission.number],
                    it[Submission.solution],
                    it[callerHasSeen],
                    isWorkFlagged(courseExId, it[Submission.student].value),
                    it[Submission.createdAt],
                    it[Submission.autoGradeStatus],
                    toGradeRespOrNull(
                        it[Submission.grade],
                        it[Submission.isAutoGrade],
                        it[Submission.isGradedDirectly]
                    ),
                    getLatestAutomaticAssessmentRespOrNull(submissionId)
                )
            }.singleOrInvalidRequest()
    }
}
