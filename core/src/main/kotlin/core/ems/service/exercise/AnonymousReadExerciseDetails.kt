package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Exercise
import core.db.ExerciseVer
import core.db.GraderType
import core.ems.service.access_control.assertExerciseHasTextEditorSubmission
import core.ems.service.access_control.assertUnauthAccessToExercise
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class AnonymousReadExerciseDetails {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("title") val title: String,
        @JsonProperty("text_html") val textHtml: String?,
        @JsonProperty("anonymous_autoassess_template") val anonymousAutoassessTemplate: String?,
        @JsonProperty("submit_allowed") val allowSubmit: Boolean,
    )

    @GetMapping("/unauth/exercises/{exerciseId}/anonymous/details")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String): Resp {

        log.info { "Getting anonymous exercise details for exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        assertUnauthAccessToExercise(exerciseId)
        assertExerciseHasTextEditorSubmission(exerciseId)

        return selectAnonymousExerciseDetails(exerciseId)
    }

    private fun selectAnonymousExerciseDetails(exerciseId: Long): Resp = transaction {
        (Exercise innerJoin ExerciseVer).select(
            ExerciseVer.title, ExerciseVer.textHtml, ExerciseVer.graderType, Exercise.anonymousAutoassessTemplate
        ).where {
            Exercise.id eq exerciseId and ExerciseVer.validTo.isNull()
        }.map {
            Resp(
                it[ExerciseVer.title],
                it[ExerciseVer.textHtml],
                it[Exercise.anonymousAutoassessTemplate],
                it[ExerciseVer.graderType] == GraderType.AUTO,
            )
        }.singleOrInvalidRequest()
    }
}
