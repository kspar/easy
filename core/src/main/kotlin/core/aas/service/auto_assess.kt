package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.ExecutorException
import core.aas.ExecutorOverloadException
import core.aas.NoExecutorsException
import core.db.Asset
import core.db.AutoExercise
import core.db.AutoExerciseExecutor
import core.db.Executor
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.web.client.RestTemplate


// TODO: to conf
private const val EXECUTOR_GRADE_URL = "/v1/grade"

private val log = KotlinLogging.logger {}


/**
 * Autoassess a solution to an automatic exercise.
 * The assessment is performed synchronously and may take a long time.
 *
 * @throws NoExecutorsException if there are no executors capable of assessing this exercise
 * @throws ExecutorOverloadException if all executors capable of assessing this exercise are already overloaded
 * @throws ExecutorException if an executor fails
 */
fun autoAssess(autoExerciseId: EntityID<Long>, submission: String): AutoAssessment {
    val autoExercise = getAutoExerciseDetails(autoExerciseId)
    val request = mapToExecutorRequest(autoExercise, submission)
    val executors = getCapableExecutors(autoExerciseId)
    val selectedExecutor = selectExecutor(executors)
    return callExecutor(selectedExecutor, request)
}


private data class AutoAssessExerciseDetails(
        val gradingScript: String, val containerImage: String, val maxTime: Int, val maxMem: Int,
        val assets: List<AutoAssessExerciseAsset>)

private data class AutoAssessExerciseAsset(
        val fileName: String, val fileContent: String)

private data class CapableExecutor(
        val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int)

data class AutoAssessment(
        val grade: Int, val feedback: String)


private fun getAutoExerciseDetails(autoExerciseId: EntityID<Long>): AutoAssessExerciseDetails {
    return transaction {
        val assets = Asset
                .select { Asset.autoExercise eq autoExerciseId }
                .map {
                    AutoAssessExerciseAsset(
                            it[Asset.fileName],
                            it[Asset.fileContent]
                    )
                }

        AutoExercise.select { AutoExercise.id eq autoExerciseId }
                .map {
                    AutoAssessExerciseDetails(
                            it[AutoExercise.gradingScript],
                            it[AutoExercise.containerImage],
                            it[AutoExercise.maxTime],
                            it[AutoExercise.maxMem],
                            assets)
                }
                .first()
    }
}


data class ExecutorRequest(
        @JsonProperty("submission") val submission: String,
        @JsonProperty("grading_script") val gradingScript: String,
        @JsonProperty("assets") val assets: List<ExecutorRequestAsset>,
        @JsonProperty("image_name") val imageName: String,
        @JsonProperty("max_time_sec") val maxTime: Int,
        @JsonProperty("max_mem_mb") val maxMem: Int)

data class ExecutorRequestAsset(
        @JsonProperty("file_name") val fileName: String,
        @JsonProperty("file_content") val fileContent: String)


private fun mapToExecutorRequest(exercise: AutoAssessExerciseDetails, submission: String): ExecutorRequest =
        ExecutorRequest(
                submission,
                exercise.gradingScript,
                exercise.assets.map { ExecutorRequestAsset(it.fileName, it.fileContent) },
                exercise.containerImage,
                exercise.maxTime,
                exercise.maxMem
        )

private fun getCapableExecutors(autoExerciseId: EntityID<Long>): Set<CapableExecutor> {
    return transaction {
        (Executor innerJoin AutoExerciseExecutor)
                .select { AutoExerciseExecutor.autoExercise eq autoExerciseId }
                .map {
                    CapableExecutor(
                            it[Executor.id].value,
                            it[Executor.name],
                            it[Executor.baseUrl],
                            it[Executor.load],
                            it[Executor.maxLoad])
                }
                .toSet()
    }
}

private fun selectExecutor(executors: Set<CapableExecutor>): CapableExecutor {
    if (executors.isEmpty()) {
        throw NoExecutorsException("No capable executors found for this auto exercise")
    }

    val executor = executors.reduce { bestExec, currentExec ->
        if (currentExec.load / currentExec.maxLoad < bestExec.load / bestExec.maxLoad) currentExec else bestExec
    }

    if (executor.load >= executor.maxLoad) {
        throw ExecutorOverloadException("All capable executors at max load")
    }
    return executor
}


data class ExecutorResponse(
        @JsonProperty("grade") val grade: Int,
        @JsonProperty("feedback") val feedback: String)

private fun callExecutor(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
    incExecutorLoad(executor.id)
    try {
        log.info { "Calling executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }

        val template = RestTemplate()
        val responseEntity = template.postForEntity(
                executor.baseUrl + EXECUTOR_GRADE_URL, request, ExecutorResponse::class.java)

        if (responseEntity.statusCode.isError) {
            log.error { "Executor error ${responseEntity.statusCodeValue} with request $request" }
            throw ExecutorException("Executor error (${responseEntity.statusCodeValue})")
        }

        val response = responseEntity.body
        if (response == null) {
            log.error { "Executor response is empty with request $request" }
            throw ExecutorException("Executor error (empty body)")
        }

        return AutoAssessment(response.grade, response.feedback)

    } finally {
        decExecutorLoad(executor.id)
        log.info { "Call finished to executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }
    }
}

private fun incExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(load, load + 1)
            }
        }
    }
}

private fun decExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(load, load - 1)
            }
        }
    }
}

private fun getExecutorLoad(executorId: Long): Int {
    return transaction {
        Executor.slice(Executor.load)
                .select { Executor.id eq executorId }
                .map { it[Executor.load] }[0]
    }
}
