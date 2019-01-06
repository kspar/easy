package ee.urgas.ems.bl.autoassess

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.client.RestTemplate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


// TODO: get from conf
const val AAS_URL = "http://aas.lahendus.ut.ee"
const val AAS_AUTOGRADE_PATH_TEMPL = "/v1/exercises/{exerciseId}/automatic-assessment"

data class AutoAssessResponse(val grade: Int, val feedback: String?)

fun autoAssess(aasId: String, solution: String, aasKey: String): AutoAssessResponse {
    val timestamp = System.currentTimeMillis() / 1000
    val toBeSigned = aasId + solution + timestamp
    val signature = getSignature(toBeSigned, aasKey)

    val req = AasRequest(solution, timestamp.toInt(), signature)

    val template = RestTemplate()
    val resp = template.postForObject(AAS_URL + AAS_AUTOGRADE_PATH_TEMPL, req, AasResponse::class.java,
            mapOf("exerciseId" to aasId))
            ?: throw RuntimeException("Null response from :aas")

    return AutoAssessResponse(resp.grade, resp.feedback)
}


data class AasRequest(@JsonProperty("submission") val solution: String,
                      @JsonProperty("timestamp") val timestamp: Int,
                      @JsonProperty("signature") val signature: String)

data class AasResponse(@JsonProperty("grade", required = true) val grade: Int,
                       @JsonProperty("feedback", required = false) val feedback: String?)


private fun getSignature(message: String, key: String): String {
    val signatureAlgorithm = "HmacSHA256"
    val keyBytes = key.toByteArray(Charsets.UTF_8)
    val keySpec = SecretKeySpec(keyBytes, signatureAlgorithm)
    val mac = Mac.getInstance(signatureAlgorithm)
    mac.init(keySpec)
    val signatureBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
    return bytesToHex(signatureBytes)
}

private fun bytesToHex(byteArray: ByteArray) =
        byteArray.joinToString("") { String.format("%02x", (it.toInt() and 0xff)) }
