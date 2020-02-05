package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.StoredFile
import core.db.StoredFile.article
import core.db.StoredFile.createdAt
import core.db.StoredFile.exercise
import core.db.StoredFile.filename
import core.db.StoredFile.owner
import core.db.StoredFile.sizeBytes
import core.db.StoredFile.type
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadFileMetadataController {

    data class Resp(@JsonProperty("files") val files: List<RespFile>)

    data class RespFile(
            @JsonProperty("id") val id: String,
            @JsonProperty("article_id") val articleId: String,
            @JsonProperty("exercise_id") val exerciseId: String,
            @JsonProperty("filename") val filename: String,
            @JsonProperty("type") val type: String,
            @JsonProperty("size_bytes") val sizeBytes: Long,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("created_at") val createdAt: DateTime,
            @JsonProperty("created_by") val createdBy: String
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/files/metadata")
    fun controller(caller: EasyUser): Resp {

        log.debug { "${caller.id} is reading metadata of all the files." }
        return selectMetadata()
    }
}


private fun selectMetadata(): ReadFileMetadataController.Resp {
    return transaction {
        ReadFileMetadataController.Resp(
                StoredFile.slice(StoredFile.id, article, exercise, filename, type, sizeBytes, createdAt, owner)
                        .selectAll().map {
                            ReadFileMetadataController.RespFile(
                                    it[StoredFile.id].value,
                                    it[article]?.value.toString(),
                                    it[exercise]?.value.toString(),
                                    it[filename],
                                    it[type],
                                    it[sizeBytes],
                                    it[createdAt],
                                    it[owner].value
                            )
                        })
    }
}
