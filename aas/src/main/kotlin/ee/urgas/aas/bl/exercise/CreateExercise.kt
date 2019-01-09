package ee.urgas.aas.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.conf.security.EasyUser
import ee.urgas.aas.db.Asset
import ee.urgas.aas.db.Executor
import ee.urgas.aas.db.Exercise
import ee.urgas.aas.db.ExerciseExecutor
import ee.urgas.aas.exception.InvalidRequestException
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class CreateExerciseController {

    data class CreateExBody(@JsonProperty("grading_script", required = true) val gradingScript: String,
                            @JsonProperty("container_image", required = true) val containerImage: String,
                            @JsonProperty("max_time_sec", required = true) val maxTime: Int,
                            @JsonProperty("max_mem_mb", required = true) val maxMem: Int,
                            @JsonProperty("assets", required = false) val assets: Set<CreateExAsset>?,
                            @JsonProperty("executors", required = true) val executors: Set<String>)

    data class CreateExAsset(@JsonProperty("file_name", required = true) val fileName: String,
                             @JsonProperty("file_content", required = true) val fileContent: String)

    data class CreatedExResponse(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER")
    @PostMapping("/exercises")
    fun createEx(@RequestBody body: CreateExBody, caller: EasyUser): CreatedExResponse {

        val callerEmail = caller.email

        validateCreateExBody(body)
        val newExercise = mapToNewExercise(body, callerEmail)
        val exerciseId = insertExercise(newExercise)
        return CreatedExResponse(exerciseId.toString())
    }

    private fun validateCreateExBody(body: CreateExBody) {
        if (body.executors.isEmpty()) {
            throw InvalidRequestException("Must have at least one executor")
        }
    }

    private fun mapToNewExercise(dto: CreateExBody, callerEmail: String): NewExercise {
        val assets = dto.assets?.map { NewAsset(it.fileName, it.fileContent) }?.toSet()
        val executors = dto.executors.map { it.toLong() }.toSet()
        return NewExercise(callerEmail, dto.gradingScript, dto.containerImage,
                dto.maxTime, dto.maxMem, assets, executors)
    }
}


private data class NewExercise(val ownerEmail: String, val gradingScript: String, val containerImage: String,
                               val maxTime: Int, val maxMem: Int, val assets: Set<NewAsset>?, val executorIds: Set<Long>)

private data class NewAsset(val fileName: String, val fileContent: String)

private fun insertExercise(newExercise: NewExercise): Long {
    return transaction {
        val exId = Exercise.insertAndGetId {
            it[ownerEmail] = newExercise.ownerEmail
            it[gradingScript] = newExercise.gradingScript
            it[containerImage] = newExercise.containerImage
            it[maxTime] = newExercise.maxTime
            it[maxMem] = newExercise.maxMem
        }

        newExercise.assets?.forEach { asset ->
            Asset.insert {
                it[exercise] = exId
                it[fileName] = asset.fileName
                it[fileContent] = asset.fileContent
            }
        }

        newExercise.executorIds.forEach { execId ->
            if (Executor.select { Executor.id eq execId }.count() == 0) {
                throw InvalidRequestException("Executor with id $execId does not exist")
            }

            ExerciseExecutor.insert {
                it[exercise] = exId
                it[executor] = EntityID(execId, Executor)
            }
        }

        exId
    }.value
}
