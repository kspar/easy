package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToExercise
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadAnonymousSubmissionsController {

    data class SubmissionResp(
        @JsonProperty("id") val submissionId: String,
        @JsonProperty("solution") val solution: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime,
        @JsonProperty("grade") val grade: Int,
        @JsonProperty("feedback") val feedback: String?
    )

    data class Resp(@JsonProperty("submissions") val submissions: List<SubmissionResp>)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/exercises/{exerciseId}/anonymous/submissions")
    fun controller(@PathVariable("exerciseId") exerciseIdString: String, caller: EasyUser): Resp {

        log.debug { "Getting anonymous submissions for '${caller.id}' on exercise '$exerciseIdString'" }
        val exerciseId = exerciseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToExercise(caller, exerciseId)

        return selectAllAnonymousSubmissions(exerciseId)
    }

    private fun selectAllAnonymousSubmissions(exerciseId: Long): Resp = transaction {
        Resp(
            AnonymousSubmission.slice(
                AnonymousSubmission.id,
                AnonymousSubmission.solution,
                AnonymousSubmission.createdAt,
                AnonymousSubmission.grade,
                AnonymousSubmission.feedback
            ).select {
                AnonymousSubmission.exercise eq exerciseId
            }.orderBy(
                AnonymousSubmission.createdAt, SortOrder.DESC
            ).map {
                SubmissionResp(
                    it[AnonymousSubmission.id].value.toString(),
                    it[AnonymousSubmission.solution],
                    it[AnonymousSubmission.createdAt],
                    it[AnonymousSubmission.grade],
                    it[AnonymousSubmission.feedback],
                )
            }
        )
    }
}
