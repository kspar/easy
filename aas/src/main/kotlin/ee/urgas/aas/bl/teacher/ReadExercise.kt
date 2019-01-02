package ee.urgas.aas.bl.teacher

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.db.Asset
import ee.urgas.aas.db.Executor
import ee.urgas.aas.db.Exercise
import ee.urgas.aas.db.ExerciseExecutor
import ee.urgas.aas.exception.InvalidRequestException
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v1")
class ReadExerciseController {

    data class ReadExerciseResponse(@JsonProperty("grading_script") val gradingScript: String,
                                    @JsonProperty("container_image") val containerImage: String,
                                    @JsonProperty("max_time_sec") val maxTime: Int,
                                    @JsonProperty("max_mem_mb") val maxMem: Int,
                                    @JsonProperty("assets") val assets: List<ReadExerciseResponseAsset>,
                                    @JsonProperty("executors") val executors: List<ReadExerciseResponseExecutor>)

    data class ReadExerciseResponseAsset(@JsonProperty("file_name") val fileName: String,
                                         @JsonProperty("file_content") val fileContent: String)

    data class ReadExerciseResponseExecutor(@JsonProperty("id") val id: String,
                                            @JsonProperty("name") val name: String,
                                            @JsonProperty("base_url") val baseUrl: String,
                                            @JsonProperty("load") val load: Int,
                                            @JsonProperty("max_load") val maxLoad: Int)

    @GetMapping("/exercises/{exerciseId}")
    fun readExercise(@PathVariable("exerciseId") exerciseId: String): ReadExerciseResponse {
        val exercise = selectExercise(exerciseId.toLong())
        return mapToReadExerciseResponse(exercise)
    }

    private fun mapToReadExerciseResponse(exercise: ReadExercise): ReadExerciseResponse =
            ReadExerciseResponse(
                    exercise.gradingScript,
                    exercise.containerImage,
                    exercise.maxTime,
                    exercise.maxMem,
                    exercise.assets.map { ReadExerciseResponseAsset(it.fileName, it.fileContent) },
                    exercise.executors.map { ReadExerciseResponseExecutor(it.id.toString(), it.name, it.baseUrl, it.load, it.maxLoad) }
            )
}

private data class ReadExercise(val gradingScript: String, val containerImage: String, val maxTime: Int, val maxMem: Int,
                                val assets: List<ReadAsset>, val executors: List<ReadExerciseExecutor>)

private data class ReadAsset(val fileName: String, val fileContent: String)
private data class ReadExerciseExecutor(val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int)

private fun selectExercise(exerciseId: Long): ReadExercise {
    return transaction {

        val assets = Asset
                .select { Asset.exercise eq exerciseId }
                .map { ReadAsset(it[Asset.fileName], it[Asset.fileContent]) }

        val executors = (Executor innerJoin ExerciseExecutor)
                .select { ExerciseExecutor.exercise eq exerciseId }
                .map {
                    ReadExerciseExecutor(
                            it[Executor.id].value,
                            it[Executor.name],
                            it[Executor.baseUrl],
                            it[Executor.load],
                            it[Executor.maxLoad]
                    )
                }

        val exercises = Exercise
                .select { Exercise.id eq exerciseId }
                .map {
                    ReadExercise(
                            it[Exercise.gradingScript],
                            it[Exercise.containerImage],
                            it[Exercise.maxTime],
                            it[Exercise.maxMem],
                            assets, executors
                    )
                }

        if (exercises.isEmpty()) {
            throw InvalidRequestException("No exercise found with id $exerciseId")
        }
        if (exercises.size > 1) {
            throw RuntimeException("Several exercises found")
        }
        exercises[0]
    }
}
