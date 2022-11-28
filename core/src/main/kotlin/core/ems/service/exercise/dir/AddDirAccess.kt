package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid


@RestController
@RequestMapping("/v2")
class AddDirController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("group_id") val groupId: String? = null,
        @JsonProperty("email") val email: String? = null,
        @JsonProperty("access_level") val level: DirAccessLevel?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/lib/dirs/{dirId}/access")
    fun controller(
        @Valid @RequestBody body: Req,
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ) {
        log.debug { "Add dir access ${body.level} to group ${body.groupId} or email ${body.email} for dir $dirIdString by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()

        caller.assertAccess { libraryDir(dirId, DirAccessLevel.PRAWM) }

        assertDirExists(dirId, true)

        // TODO should assert that group exists
        val groupId = body.groupId?.idToLongOrInvalidReq()
            ?: emailToImplicitGroup(body.email)
            ?: throw InvalidRequestException(
                "Account with email ${body.email} not found",
                ReqError.ENTITY_WITH_ID_NOT_FOUND,
                "email" to body.email.orEmpty(),
            )

        if (groupId == getImplicitGroupFromAccount(caller.id))
            throw InvalidRequestException("Cannot change your own group", ReqError.CANNOT_MODIFY_OWN)

        if (body.level == null)
        // TODO: remove access
        else
            libraryDirAddAccess(dirId, groupId, body.level)
    }

    private fun emailToImplicitGroup(email: String?): Long? {
        if (email == null)
            return null

        return getUsernameByEmail(email)?.let {
            getImplicitGroupFromAccount(it)
        }
    }
}

