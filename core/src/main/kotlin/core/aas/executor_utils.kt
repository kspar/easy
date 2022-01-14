package core.aas

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.SysConf
import core.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.web.client.RestTemplate
import java.time.Duration


private const val EXECUTOR_GRADE_URL = "/v1/grade"
const val EXECUTOR_REQUEST_TIMEOUT_SECONDS_KEY = "executor-request-timeout-seconds"

private val log = KotlinLogging.logger {}


data class ExecutorResponse(
    @JsonProperty("grade") val grade: Int,
    @JsonProperty("feedback") val feedback: String
)

data class ExecutorRequest(
    @JsonProperty("submission") val submission: String,
    @JsonProperty("grading_script") val gradingScript: String,
    @JsonProperty("assets") val assets: List<ExecutorRequestAsset>,
    @JsonProperty("image_name") val imageName: String,
    @JsonProperty("max_time_sec") val maxTime: Int,
    @JsonProperty("max_mem_mb") val maxMem: Int
)

data class ExecutorRequestAsset(
    @JsonProperty("file_name") val fileName: String,
    @JsonProperty("file_content") val fileContent: String
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

fun getExecutorMaxLoad(executorId: Long): Int {
    return transaction {
        Executor.slice(Executor.maxLoad)
            .select { Executor.id eq executorId }
            .map { it[Executor.maxLoad] }
            .first()
    }
}

fun getAvailableExecutorIds(): List<Long> {
    return transaction { Executor.slice(Executor.id).selectAll().map { it[Executor.id].value } }
}

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

internal fun getAutoExerciseDetails(autoExerciseId: Long): AutoAssessExerciseDetails {
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
                    it[AutoExercise.containerImage].value,
                    it[AutoExercise.maxTime],
                    it[AutoExercise.maxMem],
                    assets
                )
            }
            .first()
    }
}

internal fun getCapableExecutors(autoExerciseId: Long): Set<CapableExecutor> {
    return transaction {
        (AutoExercise innerJoin ContainerImage innerJoin ExecutorContainerImage innerJoin Executor)
            .select { AutoExercise.id eq autoExerciseId }
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
}

private fun timeoutRestTemplate(): RestTemplate {
    val timeout = SysConf.getProp(EXECUTOR_REQUEST_TIMEOUT_SECONDS_KEY)?.toLong() ?: 3600L

    return RestTemplateBuilder()
        .setConnectTimeout(Duration.ofSeconds(timeout))
        .setReadTimeout(Duration.ofSeconds(timeout))
        .build()
}

internal fun callExecutor(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
    log.info { "Calling executor ${executor.name}" }

    val responseEntity = timeoutRestTemplate().postForEntity(
        executor.baseUrl + EXECUTOR_GRADE_URL, request, ExecutorResponse::class.java
    )

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
}

