package ee.urgas.aas.bl.autoassess

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.db.Asset
import ee.urgas.aas.db.Executor
import ee.urgas.aas.db.Exercise
import ee.urgas.aas.db.ExerciseExecutor
import ee.urgas.aas.exception.ForbiddenException
import ee.urgas.aas.exception.OverloadedException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


const val EXECUTOR_GRADE_URL = "/v1/grade"

private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v1")
class AutoAssessController {
    companion object {
        const val SIGNATURE_ALGORITHM = "HmacSHA256"
    }

    @Value("\${ems.psk}")
    private lateinit var keyString: String
    @Value("\${ems.allowed-delay-sec}")
    private var allowedDelaySec: Int = 0

    data class AutoAssessBody(@JsonProperty("submission", required = true) val submission: String,
                              @JsonProperty("timestamp", required = true) val timestamp: Int,
                              @JsonProperty("signature", required = true) val signature: String)

    data class AutoAssessResponse(@JsonProperty("grade") val grade: Int,
                                  @JsonProperty("feedback") val feedback: String)

    @PostMapping("/exercises/{exerciseId}/automatic-assessment")
    fun autoAssess(@PathVariable("exerciseId") exerciseId: String, @RequestBody body: AutoAssessBody): AutoAssessResponse {
        validateMessage(exerciseId, body.submission, body.timestamp, body.signature)
        val submission = mapToAutoAssessSubmission(exerciseId, body)
        return mapToAutoAssessResponse(assess(submission))
    }

    private fun validateMessage(exerciseId: String, submission: String, timestamp: Int, signature: String) {
        if (allowedDelaySec == 0) {
            log.warn { "Allowed request latency is 0 (might be uninitialized)" }
        }

        // Check timestamp
        val currentTime = System.currentTimeMillis() / 1000
        val timeDelta = currentTime - timestamp
        if (timeDelta > allowedDelaySec) {
            log.warn { "Request timestamp too old: $timestamp, current time: $currentTime, delta: $timeDelta" }
            throw ForbiddenException("Request timestamp is too old")
        }
        if (timeDelta < 0) {
            log.warn { "Request timestamp in the future: $timestamp, current time: $currentTime" }
            throw ForbiddenException("Request timestamp is in the future")
        }

        // Verify signature
        val keyBytes = keyString.toByteArray(Charsets.UTF_8)
        val toBeSigned = exerciseId + submission + timestamp.toString()
        val calculatedSignature = calculateSignature(toBeSigned, keyBytes)
        if (calculatedSignature != signature) {
            log.warn { "Incorrect signature, expected: $calculatedSignature, actual: $signature" }
            throw ForbiddenException("Invalid signature")
        }
    }

    private fun calculateSignature(input: String, key: ByteArray): String {
        val mac = Mac.getInstance(SIGNATURE_ALGORITHM)
        mac.init(SecretKeySpec(key, SIGNATURE_ALGORITHM))
        val signatureBytes = mac.doFinal(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(signatureBytes)
    }

    private fun bytesToHex(byteArray: ByteArray) =
            byteArray.joinToString("") { String.format("%02x", (it.toInt() and 0xff)) }

    private fun mapToAutoAssessSubmission(exerciseId: String, body: AutoAssessBody): AutoAssessSubmission =
            AutoAssessSubmission(exerciseId.toLong(), body.submission)

    private fun mapToAutoAssessResponse(assessment: AutoAssessment): AutoAssessResponse =
            AutoAssessResponse(assessment.grade, assessment.feedback)
}


data class AutoAssessSubmission(val exerciseId: Long, val submission: String)

data class AutoAssessExercise(val gradingScript: String, val containerImage: String, val maxTime: Int, val maxMem: Int,
                              val assets: List<AutoAssessExerciseAsset>)

data class AutoAssessExerciseAsset(val fileName: String, val fileContent: String)

data class AutoAssessment(val grade: Int, val feedback: String)

data class CapableExecutor(val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int)


private fun assess(submission: AutoAssessSubmission): AutoAssessment {
    val exercise = getExerciseDetails(submission.exerciseId)
    val request = mapToExecutorRequest(exercise, submission)
    val executors = getCapableExecutors(submission.exerciseId)
    val selectedExecutor = selectExecutor(executors)
    return callExecutor(selectedExecutor, request)
}

private fun selectExecutor(executors: Set<CapableExecutor>): CapableExecutor {
    val executor = executors.reduce { bestExec, currentExec ->
        if (currentExec.load / currentExec.maxLoad < bestExec.load / bestExec.maxLoad) currentExec else bestExec
    }
    if (executor.load >= executor.maxLoad) {
        throw OverloadedException("All executors overloaded")
    }
    return executor
}


data class ExecutorRequest(@JsonProperty("submission") val submission: String,
                           @JsonProperty("grading_script") val gradingScript: String,
                           @JsonProperty("assets") val assets: List<ExecutorRequestAsset>,
                           @JsonProperty("image_name") val imageName: String,
                           @JsonProperty("max_time_sec") val maxTime: Int,
                           @JsonProperty("max_mem_mb") val maxMem: Int)

data class ExecutorRequestAsset(@JsonProperty("file_name") val fileName: String,
                                @JsonProperty("file_content") val fileContent: String)

data class ExecutorResponse(@JsonProperty("grade") val grade: Int,
                            @JsonProperty("feedback") val feedback: String)


private fun mapToExecutorRequest(exercise: AutoAssessExercise, submission: AutoAssessSubmission): ExecutorRequest =
        ExecutorRequest(
                submission.submission,
                exercise.gradingScript,
                exercise.assets.map { ExecutorRequestAsset(it.fileName, it.fileContent) },
                exercise.containerImage,
                exercise.maxTime,
                exercise.maxMem
        )


private fun callExecutor(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
    incExecutorLoad(executor.id)
    try {
        log.info { "Calling executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }

        val template = RestTemplate()
        val responseEntity = template.postForEntity(
                executor.baseUrl + EXECUTOR_GRADE_URL, request, ExecutorResponse::class.java)

        if (responseEntity.statusCode.isError) {
            log.error { "Executor error ${responseEntity.statusCodeValue} with request $request" }
            throw RuntimeException("Executor error (${responseEntity.statusCodeValue})")
        }

        val response = responseEntity.body
        if (response == null) {
            log.error { "Executor response is empty with request $request" }
            throw RuntimeException("Executor error (empty body)")
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
                it.update(Executor.load, Executor.load + 1)
            }
        }
    }
}

private fun decExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(Executor.load, Executor.load - 1)
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

private fun getCapableExecutors(exerciseId: Long): Set<CapableExecutor> {
    return transaction {
        (Executor innerJoin ExerciseExecutor)
                .select { ExerciseExecutor.exercise eq exerciseId }
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

private fun getExerciseDetails(exerciseId: Long): AutoAssessExercise {
    return transaction {
        val assets = Asset.select { Asset.exercise eq exerciseId }
                .map { AutoAssessExerciseAsset(it[Asset.fileName], it[Asset.fileContent]) }

        Exercise.select { Exercise.id eq exerciseId }
                .map {
                    AutoAssessExercise(
                            it[Exercise.gradingScript],
                            it[Exercise.containerImage],
                            it[Exercise.maxTime],
                            it[Exercise.maxMem],
                            assets)
                }[0]
    }
}
