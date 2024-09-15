package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.db.Exercise
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid


@RestController
@RequestMapping("/v2")
class UpdateExercisePatch {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("anonymous_autoassess_enabled") val anonymousAutoassessEnabled: Boolean?,
        @JsonProperty("anonymous_autoassess_template") val anonymousAutoassessTemplate: String?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PatchMapping("/exercises/{exerciseId}")
    fun controller(
        @PathVariable("exerciseId") exIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Patch update exercise $exIdString by ${caller.id}" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        caller.assertAccess { libraryExercise(exerciseId, DirAccessLevel.PRAW) }

        updateExercise(exerciseId, req)
    }

    private fun updateExercise(exerciseId: Long, req: Req) = transaction {
        Exercise.update({ Exercise.id eq exerciseId }) {
            req.anonymousAutoassessEnabled?.let { v ->
                it[anonymousAutoassessEnabled] = v
            }
            req.anonymousAutoassessTemplate?.let { v ->
                it[anonymousAutoassessTemplate] = v
            }
        }
    }
}
