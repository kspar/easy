package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Exercise
import core.db.ExerciseVer
import core.db.GraderType
import core.db.Teacher
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherUpdateExerciseController {

    data class UpdateExerciseBody(@JsonProperty("title", required = true) val title: String,
                                  @JsonProperty("text_html", required = false) val textHtml: String?,
                                  @JsonProperty("public", required = true) val public: Boolean)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/teacher/exercises/{exerciseId}")
    fun putExercise(@PathVariable("exerciseId") exIdString: String, @RequestBody dto: UpdateExerciseBody,
                    caller: EasyUser) {

        log.debug { "Update exercise $exIdString by ${caller.id}" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        updateExercise(exerciseId, caller.id, dto)
    }
}


private fun updateExercise(exerciseId: Long, authorId: String, body: TeacherUpdateExerciseController.UpdateExerciseBody) {
    data class CopyableExerciseVersion(val id: Long, val graderType: GraderType, val aasId: String?)

    val now = DateTime.now()

    transaction {
        Exercise.update({ Exercise.id eq exerciseId }) {
            it[public] = body.public
        }

        val lastVersion = ExerciseVer
                .select { ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }
                .map {
                    CopyableExerciseVersion(
                            it[ExerciseVer.id].value,
                            it[ExerciseVer.graderType],
                            it[ExerciseVer.aasId]
                    )
                }
                .first()

        ExerciseVer.update({ ExerciseVer.id eq lastVersion.id }) {
            it[validTo] = now
        }

        ExerciseVer.insert {
            it[exercise] = EntityID(exerciseId, Exercise)
            it[author] = EntityID(authorId, Teacher)
            it[validFrom] = now
            it[previous] = EntityID(lastVersion.id, ExerciseVer)
            it[graderType] = lastVersion.graderType
            it[aasId] = lastVersion.aasId
            it[title] = body.title
            it[textHtml] = body.textHtml
        }
    }
}
