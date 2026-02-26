package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.ems.service.*
import core.ems.service.access_control.admin
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class PutDirAccess {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("group_id") val groupId: String? = null,
        @param:JsonProperty("email") val email: String? = null,
        @param:JsonProperty("any_access") val anyAccess: Boolean = false,
        @param:JsonProperty("access_level") val level: DirAccessLevel?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/lib/dirs/{dirId}/access")
    fun controller(
        @Valid @RequestBody body: Req,
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ) {
        log.info { "Put dir access ${body.level} to group:${body.groupId}, email:${body.email} or any:${body.anyAccess} for dir $dirIdString by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()

        caller.assertAccess {
            libraryDir(dirId, DirAccessLevel.PRAWM)
            if (body.anyAccess)
                admin()
        }

        assertDirExists(dirId, true)

        val groupId = when {
            // TODO: assert that group exists
            body.groupId != null -> body.groupId.idToLongOrInvalidReq()
            body.email != null -> emailToImplicitGroup(body.email)
            body.anyAccess -> null
            else -> throw InvalidRequestException("Missing parameter", ReqError.INVALID_PARAMETER_VALUE)
        }

        if (body.level == DirAccessLevel.P)
            throw InvalidRequestException("Cannot assign P permission directly", ReqError.INVALID_PARAMETER_VALUE)

        // Cannot downgrade own access
        if (groupId == getImplicitGroupFromAccount(caller.id))
            throw InvalidRequestException("Cannot change your own group", ReqError.CANNOT_MODIFY_OWN)

        if (body.level == null)
            libraryDirRemoveAccess(dirId, groupId)
        else
            libraryDirPutAccess(dirId, groupId, body.level)
    }

    private fun emailToImplicitGroup(email: String): Long {
        val username = getUsernameByEmail(email) ?: throw InvalidRequestException(
            "Account with email $email not found", ReqError.ENTITY_WITH_ID_NOT_FOUND, "email" to email,
        )
        return getImplicitGroupFromAccount(username)
    }
}
