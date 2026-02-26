package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StoredFile
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.tika.Tika
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.*


@RestController
@RequestMapping("/v2")
class UploadStoredFiledController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("filename", required = true)
        @field:NotBlank
        @field:Size(max = 259)
        val filename: String,
        @field:NotBlank
        @field:Size(max = 134640000) // Approx 100,98 MB
        @param:JsonProperty("data", required = true) val data: String
    )

    data class Resp(@param:JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN", "ROLE_TEACHER")
    @PostMapping("/files")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {

        log.info { "${caller.id} is uploading a file." }
        return Resp(insertStoredFile(caller.id, dto))
    }

    private fun insertStoredFile(creator: String, req: Req): String = transaction {

        val time = DateTime.now()
        val hash = hashString(req.data + time.toInstant().millis, "SHA-256", 20)
        val content = Base64.getDecoder().decode(req.data)
        val mimeType: String = Tika().detect(content)

        StoredFile.insertAndGetId {
            it[id] = hash
            it[type] = mimeType
            it[data] = content
            it[filename] = req.filename
            it[createdAt] = time
            it[usageConfirmed] = false
            it[sizeBytes] = content.size.toLong()
            it[owner] = creator
        }.value
    }

    // https://gist.github.com/lovubuntu/164b6b9021f5ba54cefc67f60f7a1a25
    private fun hashString(input: String, algorithm: String, n: Int): String = MessageDigest
        .getInstance(algorithm)
        .digest(input.toByteArray())
        .take(n)
        .fold("") { str, it -> str + "%02x".format(it) }
}


