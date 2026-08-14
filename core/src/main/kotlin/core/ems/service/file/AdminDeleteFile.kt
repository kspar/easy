package core.ems.service.file

import core.conf.security.EasyUser
import core.db.StoredFile
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Removing a file an admin does not want kept — a mistaken upload, usually.
 *
 * **Deletes the row, not the object.** [core.ems.cron.StoredFileSweep] is the only thing in this
 * application that removes anything from the bucket, and keeping it that way is what makes every
 * partial failure collapse into "it runs again tomorrow" rather than leaving an object no row points
 * at and nothing will ever find. So the object survives until the next nightly run, at which point
 * it is an orphan and goes.
 *
 * The delay is also why this is not the primary way files disappear: the ordinary case is that a
 * file stops being referenced by any content and the sweep notices, with no endpoint involved.
 */
@RestController
@RequestMapping("/v2")
class DeleteFileController {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/files/{fileId}")
    fun controller(@PathVariable("fileId") fileId: String, caller: EasyUser) {

        log.info { "${caller.id} is deleting file ${fileId.take(6)}…" }

        val deleted = transaction {
            StoredFile.deleteWhere { StoredFile.id eq fileId }
        }

        if (deleted == 0) throw InvalidRequestException(
            "No file with id $fileId", ReqError.ENTITY_WITH_ID_NOT_FOUND, notify = false
        )
    }
}
