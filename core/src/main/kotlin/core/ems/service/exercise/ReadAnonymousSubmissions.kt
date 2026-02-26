package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.AnonymousSubmission
import core.db.DirAccessLevel
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class ReadAnonymousSubmissions {
    private val log = KotlinLogging.logger {}

    data class SubmissionResp(
        @get:JsonProperty("id") val submissionId: String,
        @get:JsonProperty("solution") val solution: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("grade") val grade: Int,
        @get:JsonProperty("feedback") val feedback: String?
    )

    data class Resp(@get:JsonProperty("submissions") val submissions: List<SubmissionResp>)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/exercises/{exerciseId}/anonymous/submissions")
    fun controller(@PathVariable("exerciseId") exerciseIdString: String, caller: EasyUser): Resp {

        log.info { "Getting anonymous submissions for '${caller.id}' on exercise '$exerciseIdString'" }
        val exerciseId = exerciseIdString.idToLongOrInvalidReq()

        caller.assertAccess { libraryExercise(exerciseId, DirAccessLevel.PR) }

        return selectAllAnonymousSubmissions(exerciseId)
    }

    private fun selectAllAnonymousSubmissions(exerciseId: Long): Resp = transaction {
        Resp(
            AnonymousSubmission.selectAll().where { AnonymousSubmission.exercise eq exerciseId }.orderBy(
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
