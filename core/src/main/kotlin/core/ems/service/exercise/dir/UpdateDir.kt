package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Dir
import core.db.DirAccessLevel
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.ems.service.assertDirExists
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class UpdateDir {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("name") @field:Size(max = 100) val name: String?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PatchMapping("/lib/dirs/{dirId}")
    fun controller(
        @PathVariable("dirId") dirIdString: String,
        @Valid @RequestBody body: Req, caller: EasyUser
    ) {
        log.debug { "Updating lib dir $dirIdString to $body by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()

        caller.assertAccess {
            libraryDir(dirId, DirAccessLevel.PRAW)
        }

        assertDirExists(dirId, allowImplicit = false)

        if (body.name != null && body.name.isBlank())
            throw InvalidRequestException(
                "Dir cannot have a blank name",
                ReqError.INVALID_PARAMETER_VALUE
            )

        updateDir(dirId, body)
    }

    private fun updateDir(dirId: Long, body: Req) = transaction {
        Dir.update({ Dir.id eq dirId }) {
            if (body.name != null)
                it[Dir.name] = body.name
            it[Dir.modifiedAt] = DateTime.now()
        }
    }
}
