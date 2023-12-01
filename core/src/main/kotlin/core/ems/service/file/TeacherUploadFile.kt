package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StoredFile
import mu.KotlinLogging
import org.apache.tika.Tika
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class UploadStoredFiledController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("filename", required = true)
        @field:NotBlank
        @field:Size(max = 259)
        val filename: String,
        @field:NotBlank
        @field:Size(max = 134640000) // Approx 100,98 MB
        @JsonProperty("data", required = true) val data: String
    )

    data class Resp(@JsonProperty("id") val id: String)

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


