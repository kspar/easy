package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.StoredFile
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*
import javax.sql.rowset.serial.SerialBlob
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UploadStoredFiledController {

    data class Req(@JsonProperty("filename", required = true) @field:NotBlank @field:Size(max = 259) val filename: String,
                   @JsonProperty("data", required = true) val data: String)

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER")
    @PostMapping("/files")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {

        log.debug { "${caller.id} is uploading a file." }

        //TODO: limit base64? Yes, but how? Probably by size at some point.
        return Resp(insertStoredFile(caller.id, dto))
    }
}


private fun insertStoredFile(creator: String, req: UploadStoredFiledController.Req): String {
    return transaction {
        val time = DateTime.now()
        // TODO: detect it:
        val detectedType = "todo"
        //TODO: gen hash
        val hash = "someHASH" + time

        val content = Base64.getDecoder().decode(req.data)

        StoredFile.insertAndGetId {
            it[id] = EntityID(hash, StoredFile)
            it[type] = detectedType
            it[data] = SerialBlob(content)
            it[filename] = req.filename
            it[createdAt] = time
            it[owner] = EntityID(creator, Account)
        }.value

    }
}

