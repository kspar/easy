package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Exercise
import core.db.ExerciseVer
import core.ems.service.assertExerciseIsAutoGradable
import core.ems.service.assertUnauthAccessToExercise
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.singleOrInvalidRequest
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AnonymousReadExerciseDetails {

    data class Resp(@JsonProperty("title") val title: String, @JsonProperty("text_html") val textHtml: String?)

    @GetMapping("/unauth/exercises/{exerciseId}/anonymous/details")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String): Resp {

        log.debug { "Getting exercise details for anonymous for exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        assertUnauthAccessToExercise(exerciseId)
        assertExerciseIsAutoGradable(exerciseId)

        return selectAnonymousExerciseDetails(exerciseId)
    }

    private fun selectAnonymousExerciseDetails(exerciseId: Long): Resp = transaction {
        (Exercise innerJoin ExerciseVer)
            .slice(
                ExerciseVer.title, ExerciseVer.textHtml
            )
            .select {
                Exercise.id eq exerciseId and ExerciseVer.validTo.isNull()
            }
            .map {
                Resp(
                    it[ExerciseVer.title],
                    it[ExerciseVer.textHtml]
                )
            }
            .singleOrInvalidRequest()
    }
}
