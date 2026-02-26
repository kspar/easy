package core.aas

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.SysConf
import core.db.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.web.client.RestTemplate
import java.time.Duration


private const val EXECUTOR_GRADE_URL = "/v1/grade"
const val EXECUTOR_REQUEST_TIMEOUT_SECONDS_KEY = "executor-request-timeout-seconds"

private val log = KotlinLogging.logger {}


data class ExecutorResponse(
    @get:JsonProperty("grade") val grade: Int,
    @get:JsonProperty("feedback") val feedback: String
)

data class ExecutorRequest(
    @param:JsonProperty("submission") val submission: String,
    @param:JsonProperty("grading_script") val gradingScript: String,
    @param:JsonProperty("assets") val assets: List<ExecutorRequestAsset>,
    @param:JsonProperty("image_name") val imageName: String,
    @param:JsonProperty("max_time_sec") val maxTime: Int,
    @param:JsonProperty("max_mem_mb") val maxMem: Int
)

data class ExecutorRequestAsset(
    @param:JsonProperty("file_name") val fileName: String,
    @param:JsonProperty("file_content") val fileContent: String
)

data class CapableExecutor(
    val id: Long, val name: String, val baseUrl: String, val maxLoad: Int, val drain: Boolean
)

data class AutoAssessment(val grade: Int, val feedback: String)

internal data class AssociatedExecutor(
    val capableExecutor: CapableExecutor,
    val functionScheduler: FunctionScheduler<AutoAssessment>
)

internal data class AutoAssessExerciseAsset(val fileName: String, val fileContent: String)

internal data class AutoAssessExerciseDetails(
    val gradingScript: String,
    val containerImage: String,
    val maxTime: Int,
    val maxMem: Int,
    val assets: List<AutoAssessExerciseAsset>
)

fun getExecutorMaxLoad(executorId: Long): Int = transaction {
    Executor.select(Executor.maxLoad)
        .where { Executor.id eq executorId }
        .map { it[Executor.maxLoad] }
        .first()
}

fun getAvailableExecutorIds(): List<Long> = transaction { Executor.select(Executor.id).map { it[Executor.id].value } }

internal fun CapableExecutor.associateWithSchedulerOrNull(functionScheduler: FunctionScheduler<AutoAssessment>?): AssociatedExecutor? {
    return if (functionScheduler == null) null else AssociatedExecutor(this, functionScheduler)
}

internal fun AutoAssessExerciseDetails.mapToExecutorRequest(submission: String): ExecutorRequest =
    ExecutorRequest(
        submission,
        gradingScript,
        assets.map { ExecutorRequestAsset(it.fileName, it.fileContent) },
        containerImage,
        maxTime,
        maxMem
    )

internal fun getAutoExerciseDetails(autoExerciseId: Long): AutoAssessExerciseDetails = transaction {
    val assets = Asset
        .selectAll().where { Asset.autoExercise eq autoExerciseId }
        .map {
            AutoAssessExerciseAsset(
                it[Asset.fileName],
                it[Asset.fileContent]
            )
        }

    AutoExercise.selectAll().where { AutoExercise.id eq autoExerciseId }
        .map {
            AutoAssessExerciseDetails(
                it[AutoExercise.gradingScript],
                it[AutoExercise.containerImage].value,
                it[AutoExercise.maxTime],
                it[AutoExercise.maxMem],
                assets
            )
        }
        .first()
}

internal fun getCapableExecutors(autoExerciseId: Long): Set<CapableExecutor> = transaction {
    (AutoExercise innerJoin ContainerImage innerJoin ExecutorContainerImage innerJoin Executor)
        .selectAll().where { AutoExercise.id eq autoExerciseId }
        .map {
            CapableExecutor(
                it[Executor.id].value,
                it[Executor.name],
                it[Executor.baseUrl],
                it[Executor.maxLoad],
                it[Executor.drain]
            )
        }
        .toSet()
}

private fun timeoutRestTemplate(): RestTemplate {
    val timeout = SysConf.getProp(EXECUTOR_REQUEST_TIMEOUT_SECONDS_KEY)?.toLong() ?: 3600L

    return RestTemplateBuilder()
        .connectTimeout(Duration.ofSeconds(timeout))
        .readTimeout(Duration.ofSeconds(timeout))
        .build()
}

internal fun callExecutor(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
    log.info { "Calling executor ${executor.name}" }

    val responseEntity = timeoutRestTemplate().postForEntity(
        executor.baseUrl + EXECUTOR_GRADE_URL, request, ExecutorResponse::class.java
    )

    if (responseEntity.statusCode.isError) {
        log.error { "Executor error ${responseEntity.statusCode.value()} with request $request" }
        throw ExecutorException("Executor error (${responseEntity.statusCode.value()})")
    }

    val response = responseEntity.body
    if (response == null) {
        log.error { "Executor response is empty with request $request" }
        throw ExecutorException("Executor error (empty body)")
    }

    return AutoAssessment(response.grade, response.feedback)
}

