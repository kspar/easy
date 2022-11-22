package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.ems.service.assertDirExists
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.libraryDirAddAccess
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid


@RestController
@RequestMapping("/v2")
class AddDirController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("group_id") val groupId: Long,
        @JsonProperty("access_level") val level: DirAccessLevel,
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/lib/dirs/{dirId}/access")
    fun controller(
        @Valid @RequestBody body: Req,
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ) {

        log.debug { "Add dir access ${body.level} to group ${body.groupId} for dir $dirIdString by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()

        caller.assertAccess { libraryDir(dirId, DirAccessLevel.PRAWM) }

        assertDirExists(dirId, true)
        // TODO should assert if group exists?
        libraryDirAddAccess(dirId, body.groupId, body.level)
    }
}

