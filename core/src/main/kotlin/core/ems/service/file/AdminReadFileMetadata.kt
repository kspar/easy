package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.StoredFile
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Every uploaded file, for an admin.
 *
 * The `persistent` filter is the point of the whole listing rather than a convenience. A persistent
 * file is by definition never reaped, so without a way to ask "show me everything that will never be
 * cleaned up, newest first" the flag is permanent storage nobody can audit — and the first time
 * anyone asks why the bucket is large there is no answer.
 *
 * Newest first, and no paging: this is an admin tool over a table with thousands of rows of
 * metadata. `?offset=&limit=` is purely additive if that stops being true.
 */
@RestController
@RequestMapping("/v2")
class ReadFileMetadataController {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("files") val files: List<RespFile>)

    data class RespFile(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("filename") val filename: String,
        @get:JsonProperty("mime_type") val mimeType: String,
        @get:JsonProperty("size_bytes") val sizeBytes: Long,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("created_by") val createdBy: String,
        @get:JsonProperty("persistent") val persistent: Boolean,
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/files/metadata")
    fun controller(
        @RequestParam("persistent", required = false) persistentFilter: Boolean?,
        caller: EasyUser
    ): Resp {
        log.info { "${caller.id} is reading file metadata (persistent filter: $persistentFilter)" }
        return selectMetadata(persistentFilter)
    }

    private fun selectMetadata(persistentFilter: Boolean?): Resp = transaction {
        Resp(
            StoredFile.selectAll()
                .apply { if (persistentFilter != null) where { StoredFile.persistent eq persistentFilter } }
                .orderBy(StoredFile.createdAt to SortOrder.DESC)
                .map {
                    RespFile(
                        it[StoredFile.id].value,
                        it[StoredFile.filename],
                        it[StoredFile.mimeType],
                        it[StoredFile.sizeBytes],
                        it[StoredFile.createdAt],
                        it[StoredFile.owner].value,
                        it[StoredFile.persistent],
                    )
                })
    }
}
