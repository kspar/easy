package ee.urgas.aas.bl.autoassess

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.db.Executor
import ee.urgas.aas.db.ExerciseExecutor
import ee.urgas.aas.exception.ForbiddenException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class AutoAssessController {
    companion object {
        const val SIGNATURE_ALGORITHM = "HmacSHA256"
        const val ALLOWED_DELAY_SEC = 30
    }

    @Value("\${ems.psk}")
    private lateinit var keyString: String

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
        // Check timestamp
        val currentTime = System.currentTimeMillis() / 1000
        val timeDelta = currentTime - timestamp
        if (timeDelta > ALLOWED_DELAY_SEC) {
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

data class AutoAssessment(val grade: Int, val feedback: String)

data class CapableExecutor(val id: Long, val baseUrl: String, val load: Int, val maxLoad: Int)

private fun assess(submission: AutoAssessSubmission): AutoAssessment {
    val executors = getCapableExecutors(submission.exerciseId)
    val selectedExecutor = selectExecutor(executors)
    return callExecutor(selectedExecutor, submission)
}

private fun selectExecutor(executors: Set<CapableExecutor>): CapableExecutor {
    return executors.reduce { bestExec, currentExec ->
        if (currentExec.load / currentExec.maxLoad < bestExec.load / bestExec.maxLoad) currentExec else bestExec
    }
}

fun callExecutor(executor: CapableExecutor, submission: AutoAssessSubmission): AutoAssessment {
    // TODO
    println(executor)
    return AutoAssessment(42, "forty-two")
}

private fun incExecutorLoad(executor: CapableExecutor) {
    transaction {
        // TODO
    }
}

private fun decExecutorLoad(executor: CapableExecutor) {
    transaction {
        // TODO
    }
}

private fun getCapableExecutors(exerciseId: Long): Set<CapableExecutor> {
    return transaction {
        (Executor innerJoin ExerciseExecutor)
                .select { ExerciseExecutor.exercise eq exerciseId }
                .map { CapableExecutor(it[Executor.id].value, it[Executor.baseUrl], it[Executor.load], it[Executor.maxLoad]) }
                .toSet()
    }
}
