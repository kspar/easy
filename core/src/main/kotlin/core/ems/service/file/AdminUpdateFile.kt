package core.ems.service.file

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StoredFile
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Marking a file as persistent, or unmarking it. Admin-only.
 *
 * Persistent means *"referenced somewhere the sweep cannot check"* — linked from an e-mail, a slide,
 * a system message's `link_url`, or named in configuration — and **not** "important". A persistent
 * file is never reaped, ever, so this is deliberately not something a teacher can do to their own
 * uploads: the failure mode of the honest misreading is that everything ends up marked and nothing
 * is ever collected.
 *
 * A file referenced from content the sweep already scans does not need this. The right fix there is
 * to add the column to the scan list in [core.ems.cron.StoredFileSweep].
 */
@RestController
@RequestMapping("/v2")
class UpdateFileController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("persistent", required = true) val persistent: Boolean
    )

    @Secured("ROLE_ADMIN")
    @PutMapping("/files/{fileId}")
    fun controller(@Valid @RequestBody req: Req, @PathVariable("fileId") fileId: String, caller: EasyUser) {

        log.info { "${caller.id} is setting persistent=${req.persistent} on file ${fileId.take(6)}…" }

        val updated = transaction {
            StoredFile.update({ StoredFile.id eq fileId }) {
                it[persistent] = req.persistent
            }
        }

        if (updated == 0) throw InvalidRequestException(
            "No file with id $fileId", ReqError.ENTITY_WITH_ID_NOT_FOUND, notify = false
        )
    }
}
