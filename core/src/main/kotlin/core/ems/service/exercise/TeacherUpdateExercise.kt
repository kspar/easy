package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.insertAutoExercise
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
class UpdateExerciseCont {

    data class Req(
            @JsonProperty("title", required = true) val title: String,
            @JsonProperty("text_html", required = false) val textHtml: String?,
            @JsonProperty("public", required = true) val public: Boolean,
            @JsonProperty("grader_type", required = true) val graderType: GraderType,
            @JsonProperty("grading_script", required = false) val gradingScript: String?,
            @JsonProperty("container_image", required = false) val containerImage: String?,
            @JsonProperty("max_time_sec", required = false) val maxTime: Int?,
            @JsonProperty("max_mem_mb", required = false) val maxMem: Int?,
            @JsonProperty("assets", required = false) val assets: List<ReqAsset>?,
            @JsonProperty("executors", required = false) val executors: List<ReqExecutor>?)

    data class ReqAsset(
            @JsonProperty("file_name", required = true) val fileName: String,
            @JsonProperty("file_content", required = true) val fileContent: String)

    data class ReqExecutor(
            @JsonProperty("executor_id", required = true) val executorId: String)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/exercises/{exerciseId}")
    fun controller(@PathVariable("exerciseId") exIdString: String,
                   @RequestBody dto: Req,
                   caller: EasyUser) {

        log.debug { "Update exercise $exIdString by ${caller.id}" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        updateExercise(exerciseId, caller.id, dto)
    }
}


private fun updateExercise(exerciseId: Long, authorId: String, req: UpdateExerciseCont.Req) {

    val now = DateTime.now()

    transaction {

        val newAutoExerciseId =
                if (req.graderType == GraderType.AUTO) {
                    insertAutoExercise(req.gradingScript, req.containerImage, req.maxTime, req.maxMem,
                            req.assets?.map { it.fileName to it.fileContent },
                            req.executors?.map { it.executorId.idToLongOrInvalidReq() })

                } else null


        Exercise.update({ Exercise.id eq exerciseId }) {
            it[public] = req.public
        }

        val lastVersionId = ExerciseVer
                .select { ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }
                .map { it[ExerciseVer.id].value }
                .first()

        ExerciseVer.update({ ExerciseVer.id eq lastVersionId }) {
            it[validTo] = now
        }

        ExerciseVer.insert {
            it[exercise] = EntityID(exerciseId, Exercise)
            it[author] = EntityID(authorId, Teacher)
            it[validFrom] = now
            it[previous] = EntityID(lastVersionId, ExerciseVer)
            it[graderType] = req.graderType
            it[title] = req.title
            it[textHtml] = req.textHtml
            it[autoExerciseId] = newAutoExerciseId
        }
    }
}
