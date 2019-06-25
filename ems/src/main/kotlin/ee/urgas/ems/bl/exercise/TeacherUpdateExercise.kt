package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.db.GraderType
import ee.urgas.ems.db.Teacher
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class TeacherUpdateExerciseController {

    data class UpdateExerciseBody(@JsonProperty("title", required = true) val title: String,
                                  @JsonProperty("text_html", required = false) val textHtml: String?,
                                  @JsonProperty("public", required = true) val public: Boolean)

    @Secured("ROLE_TEACHER")
    @PutMapping("/teacher/exercises/{exerciseId}")
    fun putExercise(@PathVariable("exerciseId") exIdString: String, @RequestBody dto: UpdateExerciseBody,
                    caller: EasyUser) {

        val callerId = caller.id
        val exerciseId = exIdString.toLong()

        log.debug { "Update exercise $exIdString by $callerId" }

        updateExercise(exerciseId, callerId, dto)
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
