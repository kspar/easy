package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.db.Exercise
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.idToLongOrInvalidReq
import jakarta.validation.Valid
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class UpdateExercisePatch {
    private val log = KotlinLogging.logger {}

    /**
     * A patch: null means "leave this alone", which is why every field is nullable.
     *
     * The template is therefore cleared by sending `""`, not `null` — the column is non-nullable
     * since changeset 020826-1 precisely so that "no template" has one spelling and is reachable
     * from any state.
     */
    data class Req(
        @param:JsonProperty("anonymous_autoassess_enabled") val anonymousAutoassessEnabled: Boolean?,
        @param:JsonProperty("anonymous_autoassess_template") val anonymousAutoassessTemplate: String?,
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
