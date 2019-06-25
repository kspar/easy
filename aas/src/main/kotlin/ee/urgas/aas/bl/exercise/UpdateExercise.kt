package ee.urgas.aas.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.conf.security.EasyUser
import ee.urgas.aas.db.Asset
import ee.urgas.aas.db.Executor
import ee.urgas.aas.db.Exercise
import ee.urgas.aas.db.ExerciseExecutor
import ee.urgas.aas.exception.ForbiddenException
import ee.urgas.aas.exception.InvalidRequestException
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class UpdateExerciseController {

    data class UpdateExBody(@JsonProperty("grading_script", required = true) val gradingScript: String,
                            @JsonProperty("container_image", required = true) val containerImage: String,
                            @JsonProperty("max_time_sec", required = true) val maxTime: Int,
                            @JsonProperty("max_mem_mb", required = true) val maxMem: Int,
                            @JsonProperty("assets", required = false) val assets: Set<UpdateExAsset>?,
                            @JsonProperty("executors", required = true) val executors: Set<String>)

    data class UpdateExAsset(@JsonProperty("file_name", required = true) val fileName: String,
                             @JsonProperty("file_content", required = true) val fileContent: String)

    @Secured("ROLE_TEACHER")
    @PutMapping("/exercises/{exerciseId}")
    fun modifyExercise(@PathVariable("exerciseId") exerciseIdString: String, @RequestBody body: UpdateExBody,
                       caller: EasyUser) {

        val callerId = caller.id
        val exerciseId = exerciseIdString.toLong()
        validateUpdateEx(exerciseId, body, callerId)
        val updExercise = mapToUpdExercise(body)
        updateExercise(exerciseId, updExercise)
    }

    private fun validateUpdateEx(exerciseId: Long, body: UpdateExBody, callerId: String) {
        if (body.executors.isEmpty()) {
            throw InvalidRequestException("Must have at least one executor")
        }
        if (callerId != getExerciseOwner(exerciseId)) {
            throw ForbiddenException("$callerId is not the owner of the exercise and is not allowed to modify it")
        }
    }

    private fun mapToUpdExercise(dto: UpdateExBody): UpdExercise {
        val assets = dto.assets?.map { UpdAsset(it.fileName, it.fileContent) }
        val executors = dto.executors.map { it.toLong() }
        return UpdExercise(dto.gradingScript, dto.containerImage, dto.maxTime, dto.maxMem, assets, executors)
    }
}


private data class UpdExercise(val gradingScript: String, val containerImage: String, val maxTime: Int, val maxMem: Int,
                               val assets: List<UpdAsset>?, val executorIds: List<Long>)

private data class UpdAsset(val fileName: String, val fileContent: String)

private fun getExerciseOwner(exId: Long): String {
    return transaction {
        Exercise.slice(Exercise.ownerId)
                .select { Exercise.id eq exId }
                .map { it[Exercise.ownerId] }[0]
    }
}

private fun updateExercise(exId: Long, updExercise: UpdExercise) {
    transaction {
        Exercise.update({ Exercise.id eq exId }) {
            it[gradingScript] = updExercise.gradingScript
            it[containerImage] = updExercise.containerImage
            it[maxTime] = updExercise.maxTime
            it[maxMem] = updExercise.maxMem
        }

        Asset.deleteWhere { Asset.exercise eq exId }

        updExercise.assets?.forEach { asset ->
            Asset.insert {
                it[exercise] = EntityID(exId, Exercise)
                it[fileName] = asset.fileName
                it[fileContent] = asset.fileContent
            }
        }

        ExerciseExecutor.deleteWhere { ExerciseExecutor.exercise eq exId }

        updExercise.executorIds.forEach { execId ->
            if (Executor.select { Executor.id eq execId }.count() == 0) {
                throw InvalidRequestException("Executor with id $execId does not exist")
            }

            ExerciseExecutor.insert {
                it[exercise] = EntityID(exId, Exercise)
                it[executor] = EntityID(execId, Executor)
            }
        }
    }
}